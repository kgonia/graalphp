package pl.codeloom.php.nodes.call;

import pl.codeloom.php.runtime.PhpError;

/**
 * func_get_arg($position), compiled in place as php-src's ZEND_FUNC_GET_ARGS family: a declared parameter's
 * current value (its variable, as the function may have changed it), an extra argument as passed.
 */
public final class FrameArgNode {
    /** php-src's ValueError for a negative position. */
    public static PhpError negativeOffset() {
        return new PhpError("ValueError", "func_get_arg(): Argument #1 ($position) must be greater than or equal "
                + "to 0");
    }

    /** php-src's ValueError for a position past the arguments passed. */
    public static PhpError offsetPastArguments() {
        return new PhpError("ValueError", "func_get_arg(): Argument #1 ($position) must be less than the number "
                + "of the arguments passed to the currently executed function");
    }
}
