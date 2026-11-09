package pl.codeloom.php.runtime;

import pl.codeloom.php.PhpContext;

/**
 * A platform thread of one context that runs fibers one after another (PhpFiber). Creating a thread cost ~200 us
 * against php-src's ~8 us for a fiber, and libraries start many short fibers, so a fiber that ends hands its thread
 * back to the context ({@link PhpContext#keepFiberThread}) and the next start takes it. The thread waits at
 * {@link #assigned} between fibers; a null fiber there retires it.
 */
public final class FiberThread {
    private final Thread thread;
    private final long stackSize;
    private final FiberGate assigned = new FiberGate();
    /** The fiber to run next, written before {@link #assigned} opens; null retires the thread. */
    private PhpFiber fiber;

    /** A new thread for context's fibers, not started. */
    public static FiberThread create(PhpContext context, long stackSize) {
        return new FiberThread(context, stackSize);
    }

    private FiberThread(PhpContext context, long stackSize) {
        this.stackSize = stackSize;
        this.thread = context.getEnv().newTruffleThreadBuilder(this::loop).stackSize(stackSize).build();
        // Truffle enters the context before loop(): if that fails (the context is closing), no fiber hands back
        thread.setUncaughtExceptionHandler((failed, problem) -> {
            PhpFiber waiting = fiber;
            if (waiting != null) {
                waiting.notRun(problem);
            }
        });
    }

    /** Whether the thread's stack is at least size (fiber.stack_size may change between fibers). */
    public boolean stackSizeAtLeast(long size) {
        return stackSize >= size;
    }

    public boolean isAlive() {
        return thread.isAlive();
    }

    /** Runs next on this thread: the first fiber starts the thread, a later one wakes it. */
    void run(PhpFiber next, boolean fresh) {
        fiber = next;
        assigned.open();
        if (fresh) {
            thread.start();
        }
    }

    /** Lets the waiting thread end. */
    public void retire() {
        fiber = null;
        assigned.open();
    }

    /**
     * Waits until the thread has ended, past Truffle's leaving of the context (PhpFiber's request end). A thread
     * can't wait for itself (exit() in a fiber).
     */
    public void join() {
        if (thread != Thread.currentThread() && thread.isAlive()) {
            com.oracle.truffle.api.TruffleSafepoint.setBlockedThreadInterruptible(null, Thread::join, thread);
        }
    }

    private void loop() {
        while (true) {
            assigned.await();
            PhpFiber next = fiber;
            if (next == null || !next.runOn(this)) {
                return;
            }
        }
    }
}
