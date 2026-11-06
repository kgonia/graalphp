package pl.codeloom.php.nodes.call;

import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpFunction;

/**
 * A call's frame arguments: [this, static class, closure, args...]. A call that unpacks binds its arguments in
 * SpreadArguments (positions, and string keys as names).
 */
public final class ArgumentsNode {
    private ArgumentsNode() {
    }

    /** Frame arguments from already-evaluated values. */
    public static Object[] frameArguments(Object thisObject, PhpClass staticClass, Object closure, Object[] values) {
        Object[] arguments = new Object[PhpFunction.ARG_FIRST + values.length];
        arguments[PhpFunction.ARG_THIS] = thisObject;
        arguments[PhpFunction.ARG_STATIC] = staticClass;
        arguments[PhpFunction.ARG_CLOSURE] = closure;
        System.arraycopy(values, 0, arguments, PhpFunction.ARG_FIRST, values.length);
        return arguments;
    }
}
