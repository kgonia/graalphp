package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;

/**
 * Calls through a callable value: `$f(args)`, call_user_func($f, args...), and call_user_func_array($f, $array)
 * (argsArray set, args then empty).
 */
public final class CallableCallNode extends CallNode {
    @Child private ExprNode callable;
    @Child private ArgumentsNode args;
    @Child private ExprNode argsArray;
    @Child private CallableDispatchNode dispatch;

    /**
     * parameter: call_user_func(_array)'s callback parameter, null for `$f(args)`. keepRef: the callee's result
     * is the call's, a reference if it returns one: `$f()`, and call_user_func(_array) where php-src compiles it
     * into the call itself (zend_compile_func_cuf); the built-in function returns a value.
     */
    public CallableCallNode(ExprNode callable, ArgumentsNode args, ExprNode argsArray,
                            Callables.Parameter parameter, boolean keepRef) {
        this.callable = callable;
        this.args = args;
        this.argsArray = argsArray;
        this.dispatch = CallableDispatchNode.create(parameter, keepRef);
    }

    @Override
    public Object executeRef(VirtualFrame frame) {
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

    /**
     * The arguments in order; an element that is a reference (`array(&$x)`) is passed as one, to by-ref params. One
     * loop per layout, each bounded by the count: Graal's speculation on a loop over any layout (its type tests and
     * the index, hoisted out of the loop) failed natively for the layouts and sizes a site hadn't met.
     */
    private static Object[] toValues(PhpArray list) {
        int count = list.size();
        Object[] values = new Object[count];
        if (list.holdsObjects()) {
            for (int i = 0; i < count; i++) {
                values[i] = list.packedAt(i);
            }
        } else if (list.holdsLongs()) {
            for (int i = 0; i < count; i++) {
                values[i] = list.longAt(i);
            }
        } else {
            int p = list.firstPosition();
            for (int i = 0; i < count; i++) {
                values[i] = list.hashValueAt(p);
                p = list.nextHashPosition(p);
            }
        }
        return values;
    }
}
