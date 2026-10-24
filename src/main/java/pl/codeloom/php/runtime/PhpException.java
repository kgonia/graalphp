package pl.codeloom.php.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;

/** A thrown PHP Throwable object, unwinding until a matching catch. Its trace is the object's: none of Truffle's. */
public final class PhpException extends AbstractTruffleException {
    private static final long serialVersionUID = 1L;

    private final transient PhpObject throwable;

    public PhpException(PhpObject throwable) {
        super(String.valueOf(throwable.getProperty("message")), null, 0, null);
        this.throwable = throwable;
    }

    public PhpObject getThrowable() {
        return throwable;
    }
}
