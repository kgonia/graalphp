package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import pl.codeloom.php.PhpContext;
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
    @Child private IndirectCallNode call;

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
        if (parameter == null) {
            this.call = IndirectCallNode.create();
        } else {
            this.dispatch = CallableDispatchNode.create(parameter, keepRef);
        }
    }

    @Override
    public Object executeRef(VirtualFrame frame) {
        Object target = callable.execute(frame);
        if (call != null) {
            return call(frame, Callables.resolveCall(PhpContext.get(this), target, MethodCallNode.scope(frame, this)),
                    args, call);
        }
        Object[] values = argsArray != null ? arrayArguments(argsArray.execute(frame)) : args.values(frame);
        return dispatch.execute(target, values);
    }

    /**
     * `$f(args)` once its target is resolved (INIT_DYNAMIC_CALL): the arguments as the target's function takes
     * them (SEND_VAR_EX), by value for __call or __callStatic; the result as the callee returned it.
     */
    static Object call(VirtualFrame frame, Callables.Target target, ArgumentsNode args, IndirectCallNode call) {
        Object[] arguments = target.magicName() != null ? target.frameArguments(args.values(frame))
                : args.build(frame, target.function(), target.thisObject(), target.staticClass(), target.closure());
        return call.call(target.function().getCallTarget(), arguments);
    }

    /** call_user_func_array()'s arguments: the array's elements (see toValues); anything else is a TypeError. */
    public static Object[] arrayArguments(Object array) {
        if (!(array instanceof PhpArray list)) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("call_user_func_array(): Argument #2 ($args) must be of type array");
        }
        return toValues(list);
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
