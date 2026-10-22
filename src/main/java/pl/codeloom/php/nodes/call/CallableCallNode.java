package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;

/**
 * Calls through a callable value: `$f(args)`, call_user_func($f, args...), and call_user_func_array($f, $array)
 * (argsArray set, args then empty).
 */
public final class CallableCallNode extends ExprNode {
    @Child private ExprNode callable;
    @Child private ArgumentsNode args;
    @Child private ExprNode argsArray;
    @Child private CallableDispatchNode dispatch = CallableDispatchNodeGen.create();

    public CallableCallNode(ExprNode callable, ArgumentsNode args, ExprNode argsArray) {
        this.callable = callable;
        this.args = args;
        this.argsArray = argsArray;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object target = callable.execute(frame);
        Object[] values;
        if (argsArray != null) {
            Object array = argsArray.execute(frame);
            if (!(array instanceof PhpArray list)) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("call_user_func_array(): Argument #2 ($args) must be of type array");
            }
            values = toValues(list);
        } else {
            values = args.values(frame);
        }
        return dispatch.execute(target, values);
    }

    private static Object[] toValues(PhpArray list) {
        Object[] values = new Object[list.size()];
        int i = 0;
        for (int p = list.firstPosition(); p >= 0; p = list.nextPosition(p)) {
            values[i++] = list.valueAt(p);
        }
        return values;
    }
}
