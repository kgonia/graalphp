package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;

/**
 * A generator's state, the internal of a Generator object: the frame of the suspended function call, the body to
 * resume, and what the last yield produced. Resuming calls the body on the same frame; the body's resumable nodes
 * skip what already ran (GeneratorNodes), and a yield stops it again by unwinding to the body's root.
 */
public final class PhpGenerator {
    /** What the body's root returns when it stopped at a yield rather than returned. */
    public static final Object SUSPENDED = new Object();

    private enum Status { NEW, SUSPENDED, RUNNING, DONE }

    private final MaterializedFrame frame;
    private final CallTarget body;
    private Status status = Status.NEW;
    private Object key = PhpNull.NULL;
    private Object value = PhpNull.NULL;
    private Object sent = PhpNull.NULL;
    private Object returned = PhpNull.NULL;
    private RuntimeException pendingThrow;
    private long largestIntKey = -1;
    /** Whether it went past the first yield (rewind() then fails). */
    private boolean advanced;

    public PhpGenerator(MaterializedFrame frame, CallTarget body) {
        this.frame = frame;
        this.body = body;
    }

    // ---- the yield side ----

    /** `yield value` (key null: the next integer key) or `yield key => value`. */
    public void yielded(Object yieldedKey, Object yieldedValue) {
        if (yieldedKey == null) {
            key = ++largestIntKey;
        } else {
            key = yieldedKey;
            if (yieldedKey instanceof Long k && k > largestIntKey) {
                largestIntKey = k;
            }
        }
        value = yieldedValue;
    }

    /** The value send() passed in (null for next()), as the yield expression's result. */
    public Object takeSent() {
        Object result = sent;
        sent = PhpNull.NULL;
        return result;
    }

    /** The exception throw() passed in, to be thrown at the yield; or null. */
    public RuntimeException takeThrow() {
        RuntimeException result = pendingThrow;
        pendingThrow = null;
        return result;
    }

    // ---- the Generator methods ----

    public Object current() {
        start();
        return status == Status.DONE ? PhpNull.NULL : value;
    }

    public Object key() {
        start();
        return status == Status.DONE ? PhpNull.NULL : key;
    }

    public void next() {
        if (status == Status.NEW) {
            start();
        }
        advanced = true;
        resume();
    }

    public Object send(Object sentValue) {
        start();
        sent = sentValue;
        advanced = true;
        resume();
        return status == Status.DONE ? PhpNull.NULL : value;
    }

    public boolean valid() {
        start();
        return status != Status.DONE;
    }

    public void rewind() {
        start();
        if (advanced) {
            throw new PhpError("Exception: Cannot rewind a generator that was already run");
        }
    }

    public Object getReturn() {
        if (status != Status.DONE) {
            throw new PhpError("Exception: Cannot get return value of a generator that hasn't returned");
        }
        return returned;
    }

    /** throw(): raises the exception at the current yield (after running to the first one). */
    public Object throwInto(RuntimeException exception) {
        start();
        if (status == Status.DONE) {
            throw exception;
        }
        pendingThrow = exception;
        advanced = true;
        resume();
        return status == Status.DONE ? PhpNull.NULL : value;
    }

    private void start() {
        if (status == Status.NEW) {
            resume();
        }
    }

    @TruffleBoundary
    private void resume() {
        if (status == Status.DONE) {
            return;
        }
        if (status == Status.RUNNING) {
            throw new PhpError("Cannot resume an already running generator");
        }
        status = Status.RUNNING;
        try {
            Object result = body.call(frame);
            if (result == SUSPENDED) {
                status = Status.SUSPENDED;
            } else {
                finish();
                returned = result;
            }
        } catch (RuntimeException e) {
            finish();
            throw e;
        }
    }

    private void finish() {
        status = Status.DONE;
        key = PhpNull.NULL;
        value = PhpNull.NULL;
    }
}
