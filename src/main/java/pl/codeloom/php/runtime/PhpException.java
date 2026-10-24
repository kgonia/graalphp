package pl.codeloom.php.runtime;

import pl.codeloom.php.PhpContext;

/** A thrown PHP Throwable object, unwinding until a matching catch. */
public final class PhpException extends PhpThrowable {
    private static final long serialVersionUID = 1L;

    private final transient PhpObject throwable;

    public PhpException(PhpObject throwable) {
        super(String.valueOf(throwable.getProperty("message")));
        this.throwable = throwable;
    }

    public PhpObject getThrowable() {
        return throwable;
    }

    @Override
    public PhpObject toThrowable(PhpContext context) {
        return throwable;
    }
}
