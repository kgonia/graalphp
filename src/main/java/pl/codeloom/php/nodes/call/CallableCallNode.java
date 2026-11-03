package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
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
    /** call_user_func's: the arguments are values. */
    private final boolean parameter;

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
        this.parameter = parameter != null;
    }

    /**
     * `$f(args)` resolves its callee before the arguments, which go by reference where its parameters do
     * (INIT_DYNAMIC_CALL, then SEND_VAR_EX); a method __call stands in for takes them by value.
     */
    @Override
    public Object executeRef(VirtualFrame frame) {
        Object target = callable.execute(frame);
        if (argsArray != null) {
            return dispatch.execute(target, arrayArguments(argsArray.execute(frame)));
        }
        if (parameter) {
            return dispatch.execute(target, args.values(frame));
        }
        Callables.Target resolved = resolve(target);
        return dispatch.execute(target, resolved.magicName() != null ? args.values(frame)
                : args.values(frame, resolved.function()));
    }

    @TruffleBoundary
    private Callables.Target resolve(Object target) {
        return Callables.resolveCall(PhpContext.get(this), target);
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
