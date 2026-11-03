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

    /** An array parameter; the first is named $array, as nearly everywhere in php-src, the others are variadic. */
    public static PhpArray array(Object[] arguments, int index, String function) {
        return array(arguments, index, function, index == 0 ? "array" : null);
    }

    /** An array parameter of the name (null for a variadic one, which php-src's messages don't name). */
    public static PhpArray array(Object[] arguments, int index, String function, String name) {
        Object value = get(arguments, index);
        if (value instanceof PhpArray array) {
            return array;
        }
        throw new PhpError("TypeError", function + "(): Argument #" + (index + 1) + (name == null ? "" : " ($" + name
                + ")") + " must be of type array, " + (value == null ? "none" : valueName(value)) + " given");
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
        if (count < min || count > max) {
            throw wrongCount(function, min, max, count);
        }
    }

    public static PhpError wrongCount(String function, int min, int max, int count) {
        String bound = min == max ? "exactly" : count < min ? "at least" : "at most";
        int expected = count < min ? min : max;
        return new PhpError("ArgumentCountError", function + "() expects " + bound + " " + expected + " argument"
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

    /** Z_PARAM_NUMBER in coercive mode (zend_parse_arg_number_slow): a Long or a Double. */
    public static Object number(PhpContext context, Object[] a, int index, String function, String name) {
        Object value = get(a, index, PhpNull.NULL);
        if (value instanceof Long || value instanceof Double) {
            return value;
        }
        if (value instanceof TruffleString s && Convert.numericValue(s) instanceof Object number) {
            return number;
        }
        if (value == PhpNull.NULL) {
            deprecateNull(context, function, index, name, "int|float");
            return 0L;
        }
        if (value instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        throw typeError(function, index + 1, name, "int|float", value);
    }

    /** Z_PARAM_ARRAY_HT_OR_STR in coercive mode: the array, or the argument as a string. */
    public static Object arrayOrString(PhpContext context, Object[] a, int index, String function, String name) {
        Object value = PhpRef.deref(Args.get(a, index, PhpNull.NULL));
        if (value instanceof PhpArray || value instanceof TruffleString) {
            return value;
        }
        if (value == PhpNull.NULL) {
            deprecateNull(context, function, index, name, "array|string");
            return Str.EMPTY;
        }
        if (value instanceof Long || value instanceof Double || value instanceof Boolean) {
            return Convert.toStr(value);
        }
        if (value instanceof PhpObject object && object.getPhpClass().findMethod("__tostring") != null) {
            return object.toPhpString();
        }
        throw typeError(function, index + 1, name, "array|string", value);
    }

    /** The deprecation of null for a scalar parameter that doesn't allow it (8.1). */
    public static void deprecateNull(PhpContext context, String function, int index, String name, String type) {
        ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, Str.of(function + "(): Passing null to parameter #"
                + (index + 1) + " ($" + name + ") of type " + type + " is deprecated"), SourceLocation.current());
    }

    /**
     * Z_PARAM_LONG in coercive mode (zend_parse_arg_long_weak), for a parameter of the given type ("int", or
     * "array|int" for Z_PARAM_ARRAY_HT_OR_LONG's other branch): what the TypeError and the null deprecation name.
     */
    public static long integer(PhpContext context, Object[] a, int index, String function, String name,
                               String type) {
        Object value = get(a, index, PhpNull.NULL);
        if (value instanceof Long l) {
            return l;
        }
        if (value == PhpNull.NULL) {
            deprecateNull(context, function, index, name, type);
            return 0;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof Double d) {
            return floatToInteger(context, d, null, function, index, name, type);
        }
        if (value instanceof TruffleString s) {
            Object number = Convert.numericValue(s);
            if (number instanceof Long l) {
                return l;
            }
            if (number instanceof Double d) {
                return floatToInteger(context, d, s, function, index, name, type);
            }
        }
        throw typeError(function, index + 1, name, type, value);
    }

    /** A float (or float-string) argument to an int parameter: in range, else a TypeError; a fraction deprecated. */
    private static long floatToInteger(PhpContext context, double d, TruffleString string, String function,
                                       int index, String name, String type) {
        if (!(d >= -9.223372036854775808E18 && d < 9.223372036854775807E18)) {
            throw typeError(function, index + 1, name, type, string != null ? string : d);
        }
        long l = (long) d;
        if (l != d) {
            String message = string != null
                    ? "Implicit conversion from float-string \"" + Str.java(string) + "\" to int loses precision"
                    : "Implicit conversion from float " + Convert.doubleToRepr(d) + " to int loses precision";
            ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, Str.of(message), SourceLocation.current());
        }
        return l;
    }

    /** Z_PARAM_DOUBLE in coercive mode. */
    public static double dbl(PhpContext context, Object[] a, int index, String function, String name) {
        Object value = get(a, index, PhpNull.NULL);
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value == PhpNull.NULL) {
            deprecateNull(context, function, index, name, "float");
            return 0;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof TruffleString s && Convert.numericValue(s) instanceof Number n) {
            return n.doubleValue();
        }
        throw typeError(function, index + 1, name, "float", value);
    }

    /** Z_PARAM_BOOL in coercive mode (zend_parse_arg_bool_weak). */
    public static boolean bool(PhpContext context, Object[] a, int index, String function, String name) {
        Object value = get(a, index, PhpNull.NULL);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == PhpNull.NULL) {
            deprecateNull(context, function, index, name, "bool");
            return false;
        }
        if (value instanceof Long || value instanceof Double || value instanceof TruffleString) {
            return Convert.toBool(value);
        }
        throw typeError(function, index + 1, name, "bool", value);
    }
}
