package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.vars.LocalVarNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;

/**
 * func_get_arg($position), compiled in place as php-src's ZEND_FUNC_GET_ARGS family: a declared parameter's
 * current value (its variable, as the function may have changed it), an extra argument as passed.
 */
public final class FrameArgNode extends ExprNode {
    @Child private ExprNode position;
    /** The declared parameters' slots (the variadic one excluded: php-src's num_args); null at the top level. */
    private final int[] paramSlots;

    public FrameArgNode(ExprNode position, int[] paramSlots) {
        this.position = position;
        this.paramSlots = paramSlots;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        long offset = Convert.toLong(position.execute(frame));
        if (offset < 0) {
            throw negativeOffset();
        }
        if (paramSlots == null) {
            throw new PhpError("Error", "func_get_arg() cannot be called from the global scope");
        }
        Object[] arguments = frame.getArguments();
        if (offset >= arguments.length - PhpFunction.ARG_FIRST) {
            throw offsetPastArguments();
        }
        if (offset < paramSlots.length) {
            Object value = LocalVarNode.raw(frame, paramSlots[(int) offset]);
            return value == null ? PhpNull.NULL : PhpRef.deref(value);
        }
        return PhpRef.deref(arguments[PhpFunction.ARG_FIRST + (int) offset]);
    }

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
