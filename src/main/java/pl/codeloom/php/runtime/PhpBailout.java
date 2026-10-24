package pl.codeloom.php.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;

/**
 * zend_bailout: a fatal error, already reported, ends the script. No catch block and no finally runs; the shutdown
 * functions and the end of the output still do.
 */
public final class PhpBailout extends AbstractTruffleException {
    private static final long serialVersionUID = 1L;

    public PhpBailout() {
        super("bailout after a fatal error");
    }
}
