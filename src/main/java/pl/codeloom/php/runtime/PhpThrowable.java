package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import pl.codeloom.php.nodes.stmt.TryNode;
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

    /** A later destructor exception owns the previous exception through its PHP previous property. */
    @TruffleBoundary
    public PhpThrowable duringUnwind(PhpThrowable previous) {
        if (previous != null && this != previous) {
            TryNode.setPrevious(PhpContext.get(null), this, previous);
            PhpException.releaseInFlight(previous);
        }
        return this;
    }

    /** The object a catch block sees. */
    public abstract PhpObject toThrowable(PhpContext context);
}
