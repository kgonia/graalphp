package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.nodes.stmt.ForeachNode;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpRef;

import java.util.List;

/**
 * A call's argument list. Arguments are evaluated once the callee is known, because a by-reference parameter
 * takes a reference to the argument place instead of its value.
 */
public final class ArgumentsNode {
    @TruffleBoundary
    private static void add(List<Object> values, Object value) {
        values.add(value);
    }

    /**
     * `...value` (ZEND_SEND_UNPACK) by value: an array's elements, or a Traversable's, each as the iterator gives
     * it, by a new reference with php-src's warning where it lands on a by-reference parameter of callee (null: not
     * known yet). ponytail: string keys, php-src's named arguments, aren't taken by name (issue 017).
     */
    @TruffleBoundary
    public static void addSpread(List<Object> values, Object value, PhpFunction callee, Node site) {
        if (value instanceof PhpArray array) {
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                values.add(array.valueAt(p));
            }
            return;
        }
        if (!(value instanceof PhpObject object) || !object.getPhpClass().isSubclassOf("traversable")) {
            throw new PhpError("TypeError", "Only arrays and Traversables can be unpacked, " + Args.valueName(value)
                    + " given");
        }
        ForeachNode.traverse(site, object, (key, element) -> {
            if (!(key instanceof Long) && !(key instanceof TruffleString)) {
                throw new PhpError("Keys must be of type int|string during argument unpacking");
            }
            if (callee != null && callee.isByRef(values.size())) {
                ErrorFunctions.raise(PhpContext.get(site), ErrorFunctions.E_WARNING, Str.of("Cannot pass "
                        + "by-reference argument " + (values.size() + 1) + " of " + callee.displayName()
                        + "() by unpacking a Traversable, passing by-value instead"), SourceLocation.of(site));
                values.add(new PhpRef(element));
            } else {
                values.add(element);
            }
            return true;
        });
    }

    @TruffleBoundary
    private static int size(List<Object> values) {
        return values.size();
    }

    /** Whether one of count arguments from position on lands on a by-reference parameter. */
    public static boolean takesRef(PhpFunction callee, int position, int count) {
        for (int i = 0; i < count; i++) {
            if (callee.isByRef(position + i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * An unpacked array's elements, by reference where the parameter is: an element of a variable's array becomes
     * a reference in it; an element of a temporary one is passed as it is, as a by-reference argument that is no
     * place.
     */
    @TruffleBoundary
    public static void addSpreadRefs(List<Object> values, PhpArray array, PhpFunction callee, int position,
                                      boolean variable) {
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            if (!callee.isByRef(position++)) {
                values.add(array.valueAt(p));
            } else if (variable) {
                values.add(array.refAtPosition(p));
            } else {
                values.add(array.rawAt(p)); // a reference stays one; a value is bound as a by-reference argument
            }
        }
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
