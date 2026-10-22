package pl.codeloom.php.builtins;

import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;

/** Reads built-in arguments from frame arguments ([this, static, closure, args...]). */
public final class Args {
    private Args() {
    }

    public static int count(Object[] arguments) {
        return arguments.length - PhpFunction.ARG_FIRST;
    }

    public static boolean has(Object[] arguments, int index) {
        return index < count(arguments);
    }

    /** The argument's value, or null (Java) when not passed. */
    public static Object get(Object[] arguments, int index) {
        return has(arguments, index) ? PhpRef.deref(arguments[PhpFunction.ARG_FIRST + index]) : null;
    }

    public static Object get(Object[] arguments, int index, Object missing) {
        return has(arguments, index) ? PhpRef.deref(arguments[PhpFunction.ARG_FIRST + index]) : missing;
    }

    public static Object required(Object[] arguments, int index, String function) {
        if (!has(arguments, index)) {
            throw new PhpError("ArgumentCountError: " + function + "() expects at least " + (index + 1)
                    + " arguments, " + count(arguments) + " given");
        }
        return PhpRef.deref(arguments[PhpFunction.ARG_FIRST + index]);
    }

    public static TruffleString str(Object[] arguments, int index) {
        Object value = get(arguments, index, PhpNull.NULL);
        return value instanceof TruffleString s ? s : Convert.toStr(value);
    }

    public static TruffleString str(Object[] arguments, int index, TruffleString missing) {
        return has(arguments, index) ? str(arguments, index) : missing;
    }

    public static long lng(Object[] arguments, int index, long missing) {
        Object value = get(arguments, index);
        return value == null ? missing : Convert.toLong(value);
    }

    public static boolean bool(Object[] arguments, int index, boolean missing) {
        Object value = get(arguments, index);
        return value == null ? missing : Convert.toBool(value);
    }

    public static PhpArray array(Object[] arguments, int index, String function) {
        Object value = get(arguments, index);
        if (value instanceof PhpArray array) {
            return array;
        }
        throw new PhpError("TypeError: " + function + "(): Argument #" + (index + 1) + " must be of type array, "
                + (value == null ? "none" : Convert.typeName(value)) + " given");
    }

    /** A by-reference argument. Built-ins declare these parameters by-ref, so callers pass a PhpRef. */
    public static PhpRef ref(Object[] arguments, int index) {
        Object value = has(arguments, index) ? arguments[PhpFunction.ARG_FIRST + index] : null;
        return value instanceof PhpRef ref ? ref : new PhpRef(value == null ? PhpNull.NULL : value);
    }
}
