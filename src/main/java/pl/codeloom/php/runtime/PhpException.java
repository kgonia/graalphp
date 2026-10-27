package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;

/** A thrown PHP Throwable object, unwinding until a matching catch. */
public final class PhpException extends PhpThrowable {
    private static final long serialVersionUID = 1L;

    private final transient PhpObject throwable;

    public PhpException(PhpObject throwable) {
        super(messageOf(throwable));
        this.throwable = throwable;
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
