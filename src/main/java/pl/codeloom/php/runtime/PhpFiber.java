package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import pl.codeloom.php.PhpContext;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Fiber's state (Zend/zend_fibers.c): its function runs on a virtual thread of its own, entered into the request's
 * context, so that it can suspend from any depth of the PHP stack. The fiber and the code that started or resumed it
 * hand over to each other: the one running opens the other's gate and waits at its own ({@link FiberGate}), so
 * exactly one thread of the context runs PHP code at any time, and the gates order the memory of what they pass.
 *
 * <p>The states are php-src's: {@link Status}, with its flags {@link #threw}, {@link #bailout} and
 * {@link #destroyed}. EG(active_fiber) is {@link PhpContext#currentFiber}, swapped at each hand-over.
 */
public final class PhpFiber {
    /** ZEND_FIBER_STATUS_*. */
    public enum Status {
        INIT, RUNNING, SUSPENDED, DEAD
    }

    /**
     * Thrown at the suspend() of a fiber destroyed while suspended (zend_fiber_object_destroy's graceful exit): the
     * finally blocks around it run as it leaves them, no catch block takes it, and the fiber's end swallows it.
     */
    public static final class Destroyed extends AbstractTruffleException {
        private static final long serialVersionUID = 1L;

        Destroyed() {
            super("fiber destroyed");
        }
    }

    /**
     * The contexts alive that have started a fiber (PhpLanguage.isThreadAccessAllowed). While there are none, no
     * context admits a second thread, as PHP is single-threaded. A context with fibers must admit threads it doesn't
     * know besides its fibers': Truffle's own, which close the context after exit() or a cancel. Truffle asks about
     * every thread of a context each time one enters, so the check is one read. Truffle doesn't say which context it
     * asks for, so while any context has fibers, every context admits threads.
     */
    private static final AtomicInteger CONTEXTS_WITH_FIBERS = new AtomicInteger();

    private final PhpContext context;
    private final PhpObject object;
    /** The callable, held while the fiber can still run it (php-src's fci.function_name), and its target. */
    private Object callable;
    private final Callables.Target target;
    /** Run first on the fiber's thread: it takes over the starting thread's thread-local state. */
    private final Runnable onThread;

    private Status status = Status.INIT;
    /** ZEND_FIBER_FLAG_THREW: the function ended by an exception. */
    private boolean threw;
    /** ZEND_FIBER_FLAG_BAILOUT: the function ended by a fatal error. */
    private boolean bailout;
    /** ZEND_FIBER_FLAG_DESTROYED: destroyed while suspended, it unwinds; it can't suspend again. */
    private boolean destroyed;

    /** The fiber's thread, once started. */
    private Thread thread;
    /** The fiber's frame arguments, from start() until its thread takes them. */
    private Object[] arguments;
    /** What the function returned, once it has. */
    private Object result;
    /** The fiber running when this one was started or last resumed: the active fiber once this one suspends. */
    private PhpFiber previous;
    /**
     * The pending releases (Refcount.pending) of the stack not running: the fiber's while its caller runs, the
     * caller's while the fiber runs. Built-ins drain them above their mark, so each stack keeps its own.
     */
    private java.util.ArrayList<Object> pending = new java.util.ArrayList<>();

    /** The value or the throwable handed over: to the fiber at resume() and throw(), from it at suspend() and end. */
    private Object value;
    private Throwable error;
    private final FiberGate toFiber = new FiberGate();
    private final FiberGate toCaller = new FiberGate();

    public PhpFiber(PhpContext context, PhpObject object, Object callable, Callables.Target target,
                    Runnable onThread) {
        this.context = context;
        this.object = object;
        this.callable = callable;
        Refcount.hold(callable);
        this.target = target;
        this.onThread = onThread;
    }

    public PhpObject object() {
        return object;
    }

    public Callables.Target target() {
        return target;
    }

    public Status status() {
        return status;
    }

    /** May a second thread enter a context: only once one has started a fiber. */
    public static boolean mayEnter() {
        return CONTEXTS_WITH_FIBERS.get() > 0;
    }

    /** A context started its first fiber. */
    public static void contextHasFibers() {
        CONTEXTS_WITH_FIBERS.incrementAndGet();
    }

    /** A context that had fibers is disposed. */
    public static void contextDisposed() {
        CONTEXTS_WITH_FIBERS.decrementAndGet();
    }

    /** Fiber::start(): runs the function with arguments (frame arguments) until it suspends or ends. */
    @TruffleBoundary
    public Object start(Object[] arguments) {
        if (status != Status.INIT) {
            throw fiberError("Cannot start a fiber that has already been started");
        }
        // zend_fiber_stack_allocate: a page and a guard page at least. The thread's stack is the JVM's, so the size
        // is only checked.
        if (context.ini().fiberStackSize() < 2 * 4096) {
            throw new PhpError("Exception", "Fiber stack size is too small, it needs to be at least 8192 bytes");
        }
        if (!context.getEnv().isCreateThreadAllowed()) {
            throw new PhpError("Error", "Fibers need a context that may create threads "
                    + "(Context.Builder.allowCreateThread)");
        }
        this.arguments = arguments;
        context.fiberStarted(this);
        thread = context.getEnv().newTruffleThreadBuilder(this::run).virtual(true).build();
        enter();
        thread.start();
        return awaitFiber();
    }

    /** Fiber::resume(): the suspend() it stopped at returns value. */
    @TruffleBoundary
    public Object resume(Object value) {
        checkSuspended();
        this.value = value;
        return switchIn();
    }

    /** Fiber::throw(): the suspend() it stopped at throws exception, a Throwable object. */
    @TruffleBoundary
    public Object throwInto(PhpObject exception) {
        checkSuspended();
        this.error = PhpException.inFlight(exception);
        return switchIn();
    }

    /** Fiber::getReturn(). */
    @TruffleBoundary
    public Object getReturn() {
        String message;
        if (status == Status.DEAD) {
            if (threw) {
                message = "The fiber threw an exception";
            } else if (bailout) {
                message = "The fiber exited with a fatal error";
            } else {
                return result;
            }
        } else if (status == Status.INIT) {
            message = "The fiber has not been started";
        } else {
            message = "The fiber has not returned";
        }
        throw fiberError("Cannot get fiber return value: " + message);
    }

    /** Fiber::suspend(): hands value to the code that started or resumed the running fiber, and waits. */
    @TruffleBoundary
    public static Object suspend(PhpContext context, Object value) {
        PhpFiber fiber = context.currentFiber();
        if (fiber == null) {
            throw fiberError("Cannot suspend outside of a fiber");
        }
        if (fiber.destroyed) {
            throw fiberError("Cannot suspend in a force-closed fiber");
        }
        fiber.status = Status.SUSPENDED;
        fiber.value = value;
        fiber.error = null;
        fiber.toCaller.open();
        fiber.toFiber.await();
        // resumed, thrown into or destroyed: switchIn set the state
        Throwable thrown = fiber.error;
        if (thrown != null) {
            fiber.error = null;
            throw sneaky(thrown);
        }
        Object resumed = fiber.value;
        fiber.value = null;
        return resumed;
    }

    /**
     * zend_fiber_object_destroy: a fiber destroyed while suspended unwinds from its suspend(), its finally blocks
     * running; what they throw reaches the code destroying it. A fiber not started or ended has nothing to do.
     */
    @TruffleBoundary
    public void destroy() {
        if (status != Status.SUSPENDED) {
            return;
        }
        destroyed = true;
        error = new Destroyed();
        switchIn();
    }

    /**
     * The request ends without destructors (after a fatal error) or the context closes: a suspended fiber unwinds
     * as a bailout does, no finally block running, so that its thread ends.
     */
    @TruffleBoundary
    public void abandon() {
        if (status != Status.SUSPENDED) {
            return;
        }
        destroyed = true;
        error = new PhpBailout();
        try {
            switchIn();
        } catch (PhpBailout expected) {
            // the fiber handed its unwinding back
        }
    }

    /**
     * zend_fiber_object_free: the callable of a fiber that never ran goes (one that ran gave it up at its end). The
     * result stays held, as a generator's return value does.
     */
    void free() {
        Object held = callable;
        if (held != null && status == Status.INIT) {
            callable = null;
            Refcount.unhold(held);
        }
    }

    /**
     * Waits until the fiber's thread has ended, past Truffle's leaving of the context: a request ending with
     * exit() or a fatal error closes the context through closeExited, which does it on a cleanup thread while
     * another thread is still active, and that thread races the embedder's own close (a deadlock in Truffle 25.4).
     * A thread can't wait for itself (exit() in this fiber).
     */
    /** Whether the fiber is over and its thread gone: the request's end has nothing to wait for. */
    public boolean ended() {
        return status == Status.DEAD && (thread == null || !thread.isAlive());
    }

    @TruffleBoundary
    public void join() {
        Thread ended = thread;
        if (ended != null && ended != Thread.currentThread() && status == Status.DEAD) {
            com.oracle.truffle.api.TruffleSafepoint.setBlockedThreadInterruptible(null, Thread::join, ended);
        }
    }

    private void checkSuspended() {
        if (status != Status.SUSPENDED) {
            throw fiberError("Cannot resume a fiber that is not suspended");
        }
    }

    /** The calling thread makes this fiber the active one. */
    private void enter() {
        previous = context.currentFiber();
        context.setCurrentFiber(this);
        pending = context.swapPendingReleases(pending);
        status = Status.RUNNING;
    }

    /** Hands over to the suspended fiber and waits until it suspends or ends. */
    private Object switchIn() {
        enter();
        toFiber.open();
        return awaitFiber();
    }

    /** On the calling thread: waits for the fiber to suspend or end; what it handed over is returned or thrown. */
    private Object awaitFiber() {
        toCaller.await();
        context.setCurrentFiber(previous);
        previous = null;
        pending = context.swapPendingReleases(pending);
        Throwable thrown = error;
        if (thrown != null) {
            error = null;
            throw sneaky(thrown);
        }
        Object handed = value;
        value = null;
        return handed;
    }

    /** The fiber's thread: the function, then the hand-back of its end. */
    private void run() {
        try {
            onThread.run();
            Object[] frame = arguments;
            arguments = null;
            // the call's result owns a count, which the fiber keeps for getReturn()
            result = PhpRef.deref(target.function().getCallTarget().call(frame));
            value = PhpNull.NULL;
        } catch (Destroyed unwound) {
            // the graceful exit of a destroyed fiber: it ends quietly
            value = PhpNull.NULL;
        } catch (Throwable thrown) {
            if (thrown instanceof PhpBailout) {
                bailout = true;
            } else {
                threw = true;
            }
            error = thrown;
        } finally {
            end();
            status = Status.DEAD;
            toCaller.open();
        }
    }

    /**
     * The function is done (zend_fiber_execute's cleanup): the callable goes, and so do the counts results of the
     * fiber's own calls still own. What a release runs (a destructor) runs on the fiber's stack, before it hands
     * back.
     */
    private void end() {
        try {
            Refcount.releasePending(context.pendingReleases(), 0);
            Object held = callable;
            callable = null;
            Refcount.unhold(held);
        } catch (Throwable thrown) {
            if (error == null) {
                threw = true;
                error = thrown;
            }
        }
    }

    private static PhpError fiberError(String message) {
        return new PhpError("FiberError", message);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable thrown) throws T {
        throw (T) thrown;
    }
}
