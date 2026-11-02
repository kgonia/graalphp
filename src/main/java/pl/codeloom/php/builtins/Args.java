package pl.codeloom.php.builtins;

import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpResource;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

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
            throw new PhpError("ArgumentCountError", function + "() expects at least " + (index + 1)
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
        throw new PhpError("TypeError", function + "(): Argument #" + (index + 1) + " must be of type array, "
                + (value == null ? "none" : valueName(value)) + " given");
    }

    /** A by-reference argument. Built-ins declare these parameters by-ref, so callers pass a PhpRef. */
    public static PhpRef ref(Object[] arguments, int index) {
        Object value = has(arguments, index) ? arguments[PhpFunction.ARG_FIRST + index] : null;
        return value instanceof PhpRef ref ? ref : new PhpRef(value == null ? PhpNull.NULL : value);
    }

    // ---- zend_parse_parameters' rules, for the built-ins that check their parameters as php-src does ----

    /** zend_wrong_parameters_count_error: an ArgumentCountError unless min..max arguments were passed. */
    public static void arity(Object[] a, String function, int min, int max) {
        int count = count(a);
        if (count >= min && count <= max) {
            return;
        }
        String bound = min == max ? "exactly" : count < min ? "at least" : "at most";
        int expected = count < min ? min : max;
        throw new PhpError("ArgumentCountError", function + "() expects " + bound + " " + expected + " argument"
                + (expected == 1 ? "" : "s") + ", " + count + " given");
    }

    /** zend_zval_value_name: what a type error says was given. */
    public static String valueName(Object value) {
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof PhpResource) {
            return "resource";
        }
        return Convert.debugType(value);
    }

    public static PhpError typeError(String function, int position, String name, String type, Object value) {
        return new PhpError("TypeError", function + "(): Argument #" + position + " ($" + name + ") must be of type "
                + type + ", " + valueName(value) + " given");
    }

    /** Z_PARAM_STR(_OR_NULL) in coercive mode; null for a null the type allows. */
    public static TruffleString string(PhpContext context, Object[] a, int index, String function, String name,
                                       boolean nullable) {
        Object value = get(a, index, PhpNull.NULL);
        if (value instanceof TruffleString s) {
            return s;
        }
        if (value == PhpNull.NULL) {
            if (nullable) {
                return null;
            }
            deprecateNull(context, function, index, name, "string");
            return Str.EMPTY;
        }
        if (value instanceof Long || value instanceof Double || value instanceof Boolean) {
            return Convert.toStr(value);
        }
        if (value instanceof PhpObject object && object.getPhpClass().findMethod("__tostring") != null) {
            return object.toPhpString();
        }
        throw typeError(function, index + 1, name, nullable ? "?string" : "string", value);
    }

    /** The deprecation of null for a scalar parameter that doesn't allow it (8.1). */
    public static void deprecateNull(PhpContext context, String function, int index, String name, String type) {
        ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, Str.of(function + "(): Passing null to parameter #"
                + (index + 1) + " ($" + name + ") of type " + type + " is deprecated"), SourceLocation.current());
    }
}
