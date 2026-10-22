package pl.codeloom.php.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;

/** A PHP fatal error raised by the runtime itself (not a catchable PHP Throwable). */
public final class PhpError extends AbstractTruffleException {
    private static final long serialVersionUID = 1L;

    public PhpError(String message) {
        super(message);
    }
}
