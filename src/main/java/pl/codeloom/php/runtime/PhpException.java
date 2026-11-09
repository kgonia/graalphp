package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;

/** A thrown PHP Throwable object, unwinding until a matching catch. */
public final class PhpException extends PhpThrowable {
    private static final long serialVersionUID = 1L;

    private final transient PhpObject throwable;
    /** `throw` counted the object (php-src's EG(exception) holds it) and no catch has taken the count yet. */
    private transient boolean counted;

    public PhpException(PhpObject throwable) {
        super(messageOf(throwable));
        this.throwable = throwable;
    }

    /** `throw`'s exception: the object holds a count while it is in flight, which the catch taking it releases. */
    public static PhpException inFlight(PhpObject throwable) {
        throwable.addRef();
        PhpException exception = new PhpException(throwable);
        exception.counted = true;
        return exception;
    }

    /** Whether the exception held a count, given up now (a catch block took the object). */
    public boolean takeCount() {
        boolean held = counted;
        counted = false;
        return held;
    }

    @TruffleBoundary
    private static String messageOf(PhpObject throwable) {
        return String.valueOf(throwable.getProperty("message"));
    }

    public PhpObject getThrowable() {
        return throwable;
    }

    @Override
    public PhpObject toThrowable(PhpContext context) {
        return throwable;
    }
}
