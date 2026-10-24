package pl.codeloom.php.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import pl.codeloom.php.PhpContext;

/**
 * A PHP Throwable on its way to a catch: one PHP code threw, or a runtime error. Neither keeps a Truffle stack
 * trace: the Throwable object holds its own.
 */
public abstract sealed class PhpThrowable extends AbstractTruffleException permits PhpError, PhpException {
    private static final long serialVersionUID = 1L;

    protected PhpThrowable(String message) {
        super(message, null, 0, null);
    }

    /** The object a catch block sees. */
    public abstract PhpObject toThrowable(PhpContext context);
}
