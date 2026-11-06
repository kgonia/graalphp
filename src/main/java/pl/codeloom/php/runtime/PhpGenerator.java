package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.bytecode.PhpBytecodeRootNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A generator's state, the internal of a Generator object, as php-src's zend_generator (zend_generators.c): the
 * suspended call of the generator function, what the last yield produced, and the delegation of `yield from`.
 *
 * <p>A body on bytecode (PhpBytecodeRootNode) is suspended at a continuation: a yield stops it with the bytecode
 * index and the operand stack kept, as php-src keeps the generator's execute_data, and resuming continues after the
 * yield, with the sent value as the yield's result. A body on the tree (GeneratorNodes) is resumed by running it
 * again on its frame, its statements skipping what already ran.
 *
 * <p>`yield from` an array or a Traversable that isn't a Generator keeps it here ({@link #values}): resuming takes
 * its next element without running the body, which goes on after the `yield from` once none is left. `yield from`
 * a Generator links this one to it ({@link #parent}, php-src's node.parent): the generator that runs when this one
 * resumes is the end of that chain ({@link #currentGenerator}), and its yields are this one's. When it returns, the
 * `yield from` delegating to it gets its return value, and the delegating generator goes on. A Generator may be
 * delegated to by several (php-src's multi-child nodes).
 */
public final class PhpGenerator {
    /** What a body returns when it stopped at a yield rather than returned. */
    public static final Object SUSPENDED = new Object();
    /** What a body on bytecode returns when it stopped at a `yield from`. */
    public static final Object DELEGATED = new Object();
    /** The resume value that makes the yield throw the pending exception (throw()'s, or a delegate's). */
    private static final Object THROW = new Object();
    /** The resume value that makes the yield throw {@link Destroyed}: the generator is destroyed while suspended. */
    private static final Object DESTROY = new Object();

    /**
     * Thrown at the yield of a generator destroyed while suspended (zend_generator_dtor_storage): the finally blocks
     * around the yield run as it leaves them, no catch block takes it, and {@link #destroy} catches it.
     */
    public static final class Destroyed extends AbstractTruffleException {
        private static final long serialVersionUID = 1L;

        Destroyed() {
            super("generator destroyed");
        }
    }

    // ---- the body: on bytecode, or on the tree ----
    /** On bytecode: the continuation the body is suspended at, and the frame it runs on. */
    private ContinuationRootNode continuation;
    /** On bytecode: the continuation before the body's first statement. */
    private final ContinuationRootNode start;
    private final MaterializedFrame frame;
    /** On the tree: the body, run again on frame at each resume. */
    private final CallTarget treeBody;
    /** `function &gen()`: its yields give references. */
    private final boolean byReference;
    /** Whether the body has a finally block (php-src's ZEND_ACC_HAS_FINALLY_BLOCK): destroying it runs them. */
    private final boolean hasFinally;

    // ---- the state ----
    /** Whether the body can still run (php-src's execute_data): false once it returned, threw or was destroyed. */
    private boolean open = true;
    private boolean running;
    /** The last yield's key and value; null (php-src's UNDEF) before the first and after the end. */
    private Object key;
    private Object value;
    /** What the body returned; null while it hasn't (also when it ended by throwing). */
    private Object returned;
    private long largestIntKey = -1;
    /** Whether the yield it stopped at takes a sent value (php-src's send_target): not a `yield from`. */
    private boolean sendTarget;
    /** The value the next resume gives the body: send()'s, a delegate's return value, or null. */
    private Object resumeValue = PhpNull.NULL;
    /** The exception the next resume throws where the body stopped (throw()'s, a delegate's); or null. */
    private RuntimeException pendingThrow;
    /** Not past the first yield yet: rewind() is allowed (AT_FIRST_YIELD). */
    private boolean atFirstYield;
    /** Stopped at a `yield from` a Generator: the next resume doesn't advance it if it has started (DO_INIT). */
    private boolean doInit;
    /** Being destroyed (FORCED_CLOSE): a yield is an error. */
    private boolean forcedClose;

    // ---- yield from ----
    /** `yield from` an array (at valuesPosition) or an Iterator: php-src's values; else null. */
    private Object values;
    private int valuesPosition;
    private boolean iteratorStarted;
    /** The Generator this one delegates to (php-src's node.parent); else null. */
    private PhpGenerator parent;
    /** The generators delegating to this one. */
    private List<PhpGenerator> children;

    /** A generator whose body is on bytecode, suspended at start (before its first statement). */
    public PhpGenerator(ContinuationRootNode start, MaterializedFrame frame, boolean byReference,
                        boolean hasFinally) {
        this.continuation = start;
        this.start = start;
        this.frame = frame;
        this.treeBody = null;
        this.byReference = byReference;
        this.hasFinally = hasFinally;
    }

    /** A generator whose body is on the tree. */
    public PhpGenerator(MaterializedFrame frame, CallTarget body) {
        this.continuation = null;
        this.start = null;
        this.frame = frame;
        this.treeBody = body;
        this.byReference = false;
        this.hasFinally = false;
    }

    /**
     * What the generator holds, as php-src's zend_generator does, for the refcount census (Census.audit): while it
     * can run, its frame's locals (CVs and temporaries); the last key and value; the return value; what a
     * `yield from` walks; the value the next resume gives.
     */
    void censusChildren(java.util.function.Predicate<Object> frameWalked,
                        java.util.function.BiConsumer<String, Object> child) {
        if (open && continuation != null && !frameWalked.test(frame)) {
            com.oracle.truffle.api.bytecode.BytecodeLocation location = continuation.getLocation();
            if (location != null) {
                try {
                    Object[] locals = location.getBytecodeNode().getLocalValues(location.getBytecodeIndex(), frame);
                    for (int i = 0; i < locals.length; i++) {
                        child.accept(" frame local " + i, locals[i]);
                    }
                } catch (RuntimeException e) {
                    child.accept(" frame unreadable", null);
                }
            }
        }
        child.accept(" key", key);
        child.accept(" value", value);
        child.accept(" returned", returned);
        child.accept(" values", values);
        child.accept(" resume value", resumeValue);
    }

    /** zend_generator_close's freeing of a suspended body's frame: its variables and foreach loops let go. */
    private void releaseFrame() {
        if (continuation == null) {
            return;
        }
        com.oracle.truffle.api.bytecode.BytecodeRootNode code = continuation.getSourceRootNode();
        com.oracle.truffle.api.bytecode.BytecodeLocation location = continuation.getLocation();
        ((Refcount.Variables) code).releaseFrame(frame,
                location != null ? location.getBytecodeNode() : code.getBytecodeNode());
    }

    /** The Generator object over a new generator. */
    @TruffleBoundary
    public PhpObject newObject(PhpContext context) {
        PhpObject object = context.getClass("Generator").instantiateInternal();
        object.setInternal(this);
        if (hasFinally) {
            context.generatorWithFinally(this);
        }
        return object;
    }

    /**
     * The generator function's name as __debugInfo gives it (php-src's: Class::method, or the function's name, a
     * closure's {closure:...}).
     */
    @TruffleBoundary
    public String getFunctionName() {
        if (treeBody instanceof com.oracle.truffle.api.RootCallTarget target) {
            return target.getRootNode().getName();
        }
        return ((com.oracle.truffle.api.nodes.RootNode) start.getSourceRootNode()).getName();
    }

    /** Whether the generator has ended (it can't be resumed any more). */
    public boolean isClosed() {
        return !open;
    }

    // ---- the body's side ----

    /** ZEND_YIELD: `yield value` (key null: the next integer key) or `yield key => value`. */
    public void yielded(Object yieldedKey, Object yieldedValue) {
        if (forcedClose) {
            throw closedYield();
        }
        if (yieldedKey == null) {
            key = ++largestIntKey;
        } else {
            key = yieldedKey;
            if (yieldedKey instanceof Long k && k > largestIntKey) {
                largestIntKey = k;
            }
        }
        value = yieldedValue;
        sendTarget = true;
        resumeValue = PhpNull.NULL;
    }

    @TruffleBoundary
    private static PhpError closedYield() {
        return new PhpError("Cannot yield from finally in a force-closed generator");
    }

    /** The continuation the body on bytecode stopped at (a yield's own). */
    public void suspendAt(ContinuationRootNode at) {
        this.continuation = at;
    }

    /**
     * What a yield of a body on bytecode gives once resumed: the sent value; or it throws, the pending exception
     * (throw()), or {@link Destroyed} for a generator being destroyed.
     */
    public Object resumed(Object resume) {
        if (resume == THROW) {
            RuntimeException thrown = pendingThrow;
            pendingThrow = null;
            throw thrown;
        }
        if (resume == DESTROY) {
            throw destroyed();
        }
        return resume;
    }

    @TruffleBoundary
    private static Destroyed destroyed() {
        return new Destroyed();
    }

    /** The value send() passed in (null for next()), for the tree's yield. */
    public Object takeSent() {
        Object result = resumeValue;
        resumeValue = PhpNull.NULL;
        return result;
    }

    /** The exception throw() passed in, to be thrown at the tree's yield; or null. */
    public RuntimeException takeThrow() {
        RuntimeException result = pendingThrow;
        pendingThrow = null;
        return result;
    }

    /** The tree's `yield from` this generator: php-src delegates only to one that hasn't finished. */
    public void checkDelegable() {
        if (!open) {
            throw new PhpError("Generator passed to yield from was aborted without proper return and is unable to "
                    + "continue");
        }
    }

    /**
     * ZEND_YIELD_FROM: starts delegating to source, an array, a Traversable or a Generator. The body then stops
     * (the resume loop takes the elements); its `yield from` gets null, or the return value of the Generator
     * delegated to, as its resume value. A Generator that already returned gives its return value at once.
     */
    @TruffleBoundary
    public void yieldFrom(Node site, Object source) {
        if (forcedClose) {
            throw new PhpError("Cannot use \"yield from\" in a force-closed generator");
        }
        Object subject = PhpRef.deref(source);
        sendTarget = false;
        resumeValue = PhpNull.NULL;
        if (subject instanceof PhpArray array) {
            array.addHolder();
            values = array;
            valuesPosition = array.firstPosition();
        } else if (subject instanceof PhpObject inner && inner.getInternal() instanceof PhpGenerator delegate) {
            if (!delegate.open) {
                throw new PhpError("Generator passed to yield from was aborted without proper return and is unable "
                        + "to continue");
            }
            if (delegate.returned != null) {
                resumeValue = delegate.returned;
                return;
            }
            if (delegate.currentGenerator() == this) {
                throw new PhpError("Impossible to yield from the Generator being currently run");
            }
            parent = delegate;
            if (delegate.children == null) {
                delegate.children = new ArrayList<>(1);
            }
            delegate.children.add(this);
            doInit = true;
        } else if (subject instanceof PhpObject traversable && traversable.getPhpClass().isSubclassOf("traversable")) {
            PhpObject iterator = iterator(site, traversable);
            iterator.callMethod(site, "rewind");
            values = iterator;
            iteratorStarted = false;
        } else {
            throw new PhpError("Can use \"yield from\" only with arrays and Traversables");
        }
    }

    /** zend_user_it_get_new_iterator: getIterator() until an Iterator. */
    private static PhpObject iterator(Node site, PhpObject traversable) {
        PhpObject current = traversable;
        while (current.getPhpClass().isSubclassOf("iteratoraggregate")) {
            Object next = current.callMethod(site, "getiterator");
            if (!(next instanceof PhpObject object) || !object.getPhpClass().isSubclassOf("traversable")) {
                throw new PhpError("Exception", "Objects returned by " + current.getPhpClass().getName()
                        + "::getIterator() must be traversable or implement interface Iterator");
            }
            current = object;
        }
        return current;
    }

    /**
     * zend_generator_get_next_delegated_value: the next element of the array or Iterator, or false at its end (an
     * exception from the Iterator ends it too, and is thrown where the body stopped).
     */
    private boolean nextDelegatedValue() {
        if (values instanceof PhpArray array) {
            int position = valuesPosition;
            if (position < 0) {
                endValues();
                return false;
            }
            value = PhpRef.deref(array.valueAt(position));
            key = array.keyAt(position);
            valuesPosition = array.nextPosition(position);
            return true;
        }
        PhpObject iterator = (PhpObject) values;
        try {
            if (iteratorStarted) {
                iterator.callMethod("next");
            }
            iteratorStarted = true;
            if (!Convert.toBool(iterator.callMethod("valid"))) {
                endValues();
                return false;
            }
            value = PhpRef.deref(iterator.callMethod("current"));
            key = PhpRef.deref(iterator.callMethod("key"));
            return true;
        } catch (PhpThrowable e) {
            endValues();
            pendingThrow = e;
            return false;
        }
    }

    private void endValues() {
        if (values instanceof PhpArray array) {
            array.removeHolder();
        }
        values = null;
    }

    // ---- the delegation chain ----

    /**
     * zend_generator_get_current: the generator that runs when this one resumes, the end of its `yield from`
     * chain. If that one has finished, the `yield from` delegating to it gets its return value (or, if it ended
     * without one, a ClosedGeneratorException) and the delegating generator is current again; with exception
     * pending (one is on its way out), only the links change.
     */
    private PhpGenerator currentGenerator() {
        return currentGenerator(false);
    }

    private PhpGenerator currentGenerator(boolean exceptionPending) {
        if (parent == null) {
            return this;
        }
        // php-src caches the end of the chain (node.ptr.root): a chain only grows at its end until a link is
        // removed, which unlinkVersion counts, so a deep chain isn't walked again at every step
        PhpGenerator root = rootCache != null && rootCacheVersion == unlinkVersion ? rootCache : parent;
        while (root.parent != null) {
            root = root.parent;
        }
        rootCache = root;
        rootCacheVersion = unlinkVersion;
        if (root.open) {
            return root;
        }
        return updateCurrent(root, exceptionPending);
    }

    /** The end of this one's chain when it was last looked up, valid while no link was removed since. */
    private PhpGenerator rootCache;
    private int rootCacheVersion;
    /** Counts the links removed from delegation chains (in any context: it only invalidates caches). */
    private static int unlinkVersion;

    /** Removes the link from child to the generator it delegates to. */
    private static void unlink(PhpGenerator child) {
        child.parent.children.remove(child);
        child.parent = null;
        unlinkVersion++;
    }

    /** zend_generator_update_current: the end of the chain, oldRoot, has finished. */
    private PhpGenerator updateCurrent(PhpGenerator oldRoot, boolean exceptionPending) {
        // get_new_root: from the finished end towards this one, past the finished generators
        PhpGenerator newRoot = below(oldRoot);
        while (!newRoot.open && newRoot != this) {
            newRoot = below(newRoot);
        }
        PhpGenerator newRootParent = newRoot.parent;
        unlink(newRoot);
        if (newRoot != this) {
            rootCache = newRoot;
            rootCacheVersion = unlinkVersion;
        }
        if (!exceptionPending) {
            if (newRootParent.returned == null) {
                newRoot.pendingThrow = new PhpError("ClosedGeneratorException",
                        "Generator yielded from aborted, no return value available");
                if (!oldRoot.running) {
                    resume();
                    return currentGenerator();
                }
            } else {
                newRoot.value = newRootParent.value;
                newRoot.resumeValue = newRootParent.returned;
            }
        }
        return newRoot;
    }

    /**
     * The generator on the chain from this one that delegates to g: its only child, else (several delegate to it)
     * found by walking from this one, as get_new_root does.
     */
    private PhpGenerator below(PhpGenerator g) {
        if (g.children != null && g.children.size() == 1) {
            return g.children.get(0);
        }
        PhpGenerator below = this;
        while (below.parent != g) {
            below = below.parent;
        }
        return below;
    }

    // ---- running the body ----

    /** zend_generator_ensure_initialized: runs to the first yield if it hasn't started. */
    private void ensureInitialized() {
        if (value == null && open && parent == null) {
            resume();
            atFirstYield = true;
        }
    }

    /** zend_generator_resume: runs the current generator until it yields, and on through finished delegations. */
    @TruffleBoundary
    private void resume() {
        PhpGenerator generator = currentGenerator();
        if (!generator.open) {
            return;
        }
        PhpGenerator delegator = this;
        while (true) {
            if (generator.running) {
                throw new PhpError("Cannot resume an already running generator");
            }
            if (delegator.doInit) {
                delegator.doInit = false;
                if (generator.value != null) {
                    // an already started delegate isn't advanced on the first resumption
                    return;
                }
            }
            atFirstYield = false;
            generator.running = true;
            Object result;
            try {
                if (generator.values != null && (generator.values instanceof PhpArray ? generator.nextDelegatedValue()
                        : (Boolean) linked(generator, true))) {
                    generator.running = false;
                    return;
                }
                result = linked(generator, false);
            } catch (RuntimeException e) {
                generator.running = false;
                generator.close();
                if (generator == this) {
                    throw e;
                }
                // thrown again at the `yield from` of the generator delegating to it
                generator = currentGenerator(true);
                generator.pendingThrow = e;
                delegator = this;
                continue;
            }
            generator.running = false;
            if (result == DELEGATED) {
                delegator = generator;
                generator = currentGenerator();
                continue;
            }
            if (result != SUSPENDED) {
                generator.returned = result;
                generator.close();
                if (generator != this) {
                    delegator = this;
                    generator = currentGenerator();
                    continue;
                }
            }
            return;
        }
    }

    /**
     * The running generator's step, its body or (iterator) its Iterator's next value (true if one came), with the
     * frames zend_generator_resume links in: this one's and those down its chain to the running one, and the running
     * one's own for its Iterator, each suspended at its `yield from`. As php-src's placeholder frame, it is one
     * frame, however deep the chain, which {@link #linkedLevels} unfolds for a trace.
     */
    private Object linked(PhpGenerator running, boolean iterator) {
        if (this == running && !iterator) {
            return runBody();
        }
        PhpGenerator inner = iterator ? running : below(running);
        return ((PhpBytecodeRootNode) inner.start.getSourceRootNode()).linkedFrame().call(inner.frame,
                inner.continuation.getLocation(), this, running, iterator);
    }

    @TruffleBoundary
    private static Object linkedStep(PhpGenerator running, boolean iterator) {
        return iterator ? (Object) running.nextDelegatedValue() : running.runBody();
    }

    /**
     * The generators a {@link LinkedFrame}'s call stands for, innermost first: each one's code root, frame and the
     * location it is suspended at (zend_generator_check_placeholder_frame).
     */
    @TruffleBoundary
    public static List<Object[]> linkedLevels(Object[] arguments) {
        PhpGenerator running = (PhpGenerator) arguments[3];
        List<Object[]> levels = new ArrayList<>();
        for (PhpGenerator g = (PhpGenerator) arguments[2]; g != running || (Boolean) arguments[4]; g = g.parent) {
            levels.add(new Object[] {g.start.getSourceRootNode(), g.frame, g.continuation.getLocation()});
            if (g == running) {
                break;
            }
        }
        java.util.Collections.reverse(levels);
        return levels;
    }

    /**
     * A generator function's frame while its generator is the innermost one linked in ({@link #linked}): to the frame
     * walkers ({@link SourceLocation#codeRoot}), the generator's frame (argument 0) at its `yield from` (argument 1);
     * a trace unfolds the rest of the chain ({@link #linkedLevels}).
     */
    public static final class LinkedFrame extends RootNode {
        private final RootNode code;

        public LinkedFrame(TruffleLanguage<?> language, RootNode code) {
            super(language);
            this.code = code;
        }

        public RootNode code() {
            return code;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] arguments = frame.getArguments();
            return linkedStep((PhpGenerator) arguments[3], (Boolean) arguments[4]);
        }
    }

    /** Runs the body from where it stopped, with the resume value, or the pending exception. */
    private Object runBody() {
        if (treeBody != null) {
            // the tree's yields take the sent value and the exception themselves
            return treeBody.call(frame);
        }
        Object resume;
        if (pendingThrow != null) {
            resume = THROW;
        } else {
            resume = resumeValue;
            resumeValue = PhpNull.NULL;
        }
        return continuation.getCallTarget().call(frame, resume);
    }

    /**
     * zend_generator_close: nothing to resume any more. The last key and value stay (the methods give null for a
     * closed generator), as a `yield from` that delegated to it takes the value when it finishes.
     */
    private void close() {
        open = false;
        if (values != null) {
            endValues();
        }
    }

    // ---- the Generator methods ----

    public Object current() {
        ensureInitialized();
        PhpGenerator root = currentGenerator();
        return open && root.value != null ? PhpRef.deref(root.value) : PhpNull.NULL;
    }

    /** The current value as yielded: a reference from a generator that yields by reference (foreach by reference). */
    public Object currentRef() {
        ensureInitialized();
        PhpGenerator root = currentGenerator();
        return open && root.value != null ? root.value : PhpNull.NULL;
    }

    public Object key() {
        ensureInitialized();
        PhpGenerator root = currentGenerator();
        return open && root.key != null ? PhpRef.deref(root.key) : PhpNull.NULL;
    }

    public void next() {
        ensureInitialized();
        resume();
    }

    public Object send(Object sentValue) {
        ensureInitialized();
        if (!open) {
            return PhpNull.NULL;
        }
        PhpGenerator root = currentGenerator();
        if (root.sendTarget && !root.running) {
            root.resumeValue = sentValue;
        }
        resume();
        root = currentGenerator();
        return open && root.value != null ? PhpRef.deref(root.value) : PhpNull.NULL;
    }

    public boolean valid() {
        ensureInitialized();
        currentGenerator();
        return open;
    }

    public void rewind() {
        ensureInitialized();
        if (!atFirstYield) {
            throw new PhpError("Exception", "Cannot rewind a generator that was already run");
        }
    }

    /** zend_generator_get_iterator: foreach over the generator, before it rewinds. */
    public void startIteration(boolean byRef) {
        if (!open) {
            throw new PhpError("Exception", "Cannot traverse an already closed generator");
        }
        if (byRef && !byReference) {
            throw new PhpError("Exception", "You can only iterate a generator by-reference if it declared that it "
                    + "yields by-reference");
        }
    }

    public Object getReturn() {
        ensureInitialized();
        if (returned == null) {
            throw new PhpError("Exception", "Cannot get return value of a generator that hasn't returned");
        }
        return returned;
    }

    /** throw(): raises the exception where the current generator stopped (after running to the first yield). */
    public Object throwInto(RuntimeException exception) {
        ensureInitialized();
        if (!open) {
            throw exception;
        }
        PhpGenerator root = currentGenerator();
        if (root.running) {
            throw new PhpError("Cannot resume an already running generator");
        }
        root.pendingThrow = exception;
        if (root.values != null) {
            // an array or Iterator delegated to is left: the exception is thrown at the `yield from`
            root.endValues();
        }
        resume();
        root = currentGenerator();
        return open && root.value != null ? PhpRef.deref(root.value) : PhpNull.NULL;
    }

    /**
     * zend_generator_dtor_storage: a generator destroyed while suspended in a try block runs the finally blocks
     * around where it stopped, as if it returned there, then closes; a yield in them is an error. Any other just
     * closes.
     */
    @TruffleBoundary
    public void destroy() {
        if (values != null) {
            endValues();
        }
        if (parent != null) {
            unlink(this);
        }
        if (!open || running || !hasFinally || continuation == start) {
            if (open && !running) {
                // suspended (or not started): its frame is freed, the variables give up what they hold (the
                // finally path below unwinds, which releases them)
                releaseFrame();
            }
            close();
            return;
        }
        forcedClose = true;
        pendingThrow = null;
        running = true;
        try {
            continuation.getCallTarget().call(frame, DESTROY);
        } catch (Destroyed done) {
            // the finally blocks ran
        } finally {
            running = false;
            close();
        }
    }
}
