package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;

/**
 * Calls through a callable value: `$f(args)`, call_user_func($f, args...), and call_user_func_array($f, $array)
 * (argsArray set, args then empty).
 */
public final class CallableCallNode {
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
