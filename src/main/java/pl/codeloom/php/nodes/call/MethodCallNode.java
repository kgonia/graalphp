package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.Str;


/** `$obj->name(args)`: the method looked up per receiver class and called per method found (Dispatch). */
public final class MethodCallNode {
    /** The Closure class's other methods (bindTo, call) on a closure value, called from site. */
    @TruffleBoundary
    public static Object closureMethod(Node site, String name, PhpClosure closure, Object[] values) {
        PhpFunction method = PhpContext.get(site).getClass("Closure").findMethod(Str.lower(name));
        if (method == null) {
            throw new PhpError("Call to undefined method Closure::" + name + "()");
        }
        return Backtrace.callFrom(site, () -> method.getCallTarget().call(
                ArgumentsNode.frameArguments(closure, null, null, values)));
    }

    /**
     * A method called on a value that isn't an object (zend_invalid_method_call): the name as written, the value as
     * zend_zval_value_name names it.
     */
    @TruffleBoundary
    public static PhpError memberOnNonObject(String name, Object target) {
        return new PhpError("Call to a member function " + name + "() on " + Args.valueName(target));
    }

    /** `$obj->$name()` with a name that isn't a string (ZEND_INIT_METHOD_CALL). */
    @TruffleBoundary
    public static PhpError nameNotString() {
        return new PhpError("Method name must be a string");
    }

}
