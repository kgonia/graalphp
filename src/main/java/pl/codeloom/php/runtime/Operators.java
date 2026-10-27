package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.ErrorFunctions;

/**
 * Generic (slow-path) arithmetic with PHP semantics: operands juggle to int or float, int results that overflow
 * become floats, and the division family raises DivisionByZeroError. An operand that isn't a number, a numeric
 * string, a bool or null is a TypeError (zend_binop_error). Nodes handle the common int/float cases themselves
 * and fall back here.
 */
public final class Operators {
    private Operators() {
    }

    public static Object addLongs(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) {
            return (double) a + (double) b;
        }
        return r;
    }

    public static Object subLongs(long a, long b) {
        long r = a - b;
        if (((a ^ b) & (a ^ r)) < 0) {
            return (double) a - (double) b;
        }
        return r;
    }

    public static Object mulLongs(long a, long b) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        if (high == 0 && low >= 0 || high == -1 && low < 0) {
            return low;
        }
        return (double) a * (double) b;
    }

    public static Object divLongs(long a, long b) {
        if (b == 0) {
            throw divisionByZero("Division by zero");
        }
        if (a % b == 0 && !(a == Long.MIN_VALUE && b == -1)) {
            return a / b;
        }
        return (double) a / (double) b;
    }

    public static long modLongs(long a, long b) {
        if (b == 0) {
            throw divisionByZero("Modulo by zero");
        }
        if (b == -1) {
            return 0;
        }
        return a % b;
    }

    @TruffleBoundary
    private static PhpError divisionByZero(String message) {
        return new PhpError("DivisionByZeroError", message);
    }

    // ---- operands (zend_operators.c) ----

    /**
     * zendi_try_convert_scalar_to_number: the number an arithmetic operand stands for, or null where php-src fails
     * (a non-numeric string, an array, an object, a resource), which zend_binop_error reports. A numeric string
     * with trailing data warns "A non-numeric value encountered" at the site (the node computing; null from a
     * built-in, whose caller is the place).
     */
    private static Object number(Node site, Object value) {
        if (value instanceof Long || value instanceof Double) {
            return value;
        }
        if (value instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (value == PhpNull.NULL) {
            return 0L;
        }
        if (value instanceof TruffleString s) {
            byte[] bytes = Str.readBytes(s);
            Object number = Convert.parseNumber(bytes, false);
            if (number == null) {
                number = Convert.parseNumber(bytes, true);
                if (number != null) {
                    notice(site, ErrorFunctions.E_WARNING, "A non-numeric value encountered");
                }
            }
            return number;
        }
        return null;
    }

    /**
     * zendi_try_get_long: an operand of %, the shifts and the bitwise operators, or null where php-src fails
     * (zend_binop_error). A float that isn't an integer, and a float-string, deprecate the lost precision.
     */
    private static Long integer(Node site, Object value) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (value == PhpNull.NULL) {
            return 0L;
        }
        if (value instanceof Double d) {
            long l = Convert.toLong(d);
            if (!Double.isFinite(d) || (double) l != d) {
                notice(site, ErrorFunctions.E_DEPRECATED, "Implicit conversion from float " + Convert.doubleToRepr(d)
                        + " to int loses precision");
            }
            return l;
        }
        if (value instanceof TruffleString s) {
            byte[] bytes = Str.readBytes(s);
            Object number = Convert.parseNumber(bytes, false);
            if (number == null) {
                number = Convert.parseNumber(bytes, true);
                if (number == null) {
                    return null;
                }
                notice(site, ErrorFunctions.E_WARNING, "A non-numeric value encountered");
            }
            if (number instanceof Long l) {
                return l;
            }
            long capped = Convert.stringToLong(s);
            if ((double) capped != (Double) number) {
                notice(site, ErrorFunctions.E_DEPRECATED, "Implicit conversion from float-string \""
                        + Str.java(s) + "\" to int loses precision");
            }
            return capped;
        }
        return null;
    }

    /** zend_binop_error: "Unsupported operand types: string + int". */
    private static PhpError binopError(String operator, Object a, Object b) {
        return new PhpError("TypeError", "Unsupported operand types: " + operandType(a) + " " + operator + " "
                + operandType(b));
    }

    /** zend_zval_type_name: an object by its class's name (zend_zval_value_name also names true and false). */
    private static String operandType(Object value) {
        if (value instanceof PhpObject object) {
            return object.getPhpClass().getName();
        }
        if (value instanceof PhpResource) {
            return "resource";
        }
        return Convert.debugType(value);
    }

    @TruffleBoundary
    public static Object add(Node site, Object a, Object b) {
        if (a instanceof PhpArray x && b instanceof PhpArray y) {
            return union(x, y);
        }
        Object x = number(site, a);
        Object y = x == null ? null : number(site, b);
        if (y == null) {
            throw binopError("+", a, b);
        }
        if (x instanceof Long l && y instanceof Long r) {
            return addLongs(l, r);
        }
        return ((Number) x).doubleValue() + ((Number) y).doubleValue();
    }

    @TruffleBoundary
    public static Object sub(Node site, Object a, Object b) {
        Object x = number(site, a);
        Object y = x == null ? null : number(site, b);
        if (y == null) {
            throw binopError("-", a, b);
        }
        if (x instanceof Long l && y instanceof Long r) {
            return subLongs(l, r);
        }
        return ((Number) x).doubleValue() - ((Number) y).doubleValue();
    }

    @TruffleBoundary
    public static Object mul(Node site, Object a, Object b) {
        Object x = number(site, a);
        Object y = x == null ? null : number(site, b);
        if (y == null) {
            throw binopError("*", a, b);
        }
        if (x instanceof Long l && y instanceof Long r) {
            return mulLongs(l, r);
        }
        return ((Number) x).doubleValue() * ((Number) y).doubleValue();
    }

    @TruffleBoundary
    public static Object div(Node site, Object a, Object b) {
        Object x = number(site, a);
        Object y = x == null ? null : number(site, b);
        if (y == null) {
            throw binopError("/", a, b);
        }
        if (x instanceof Long l && y instanceof Long r) {
            return divLongs(l, r);
        }
        double divisor = ((Number) y).doubleValue();
        if (divisor == 0) {
            throw divisionByZero("Division by zero");
        }
        return ((Number) x).doubleValue() / divisor;
    }

    @TruffleBoundary
    public static long mod(Node site, Object a, Object b) {
        Long x = integer(site, a);
        Long y = x == null ? null : integer(site, b);
        if (y == null) {
            throw binopError("%", a, b);
        }
        return modLongs(x, y);
    }

    @TruffleBoundary
    public static Object pow(Node site, Object a, Object b) {
        Object x = number(site, a);
        Object y = x == null ? null : number(site, b);
        if (y == null) {
            throw binopError("**", a, b);
        }
        if (x instanceof Long base && y instanceof Long exponent && exponent >= 0) {
            long result = 1;
            long factor = base;
            long e = exponent;
            boolean overflow = false;
            while (e > 0 && !overflow) {
                if ((e & 1) != 0) {
                    Object product = mulLongs(result, factor);
                    if (product instanceof Long l) {
                        result = l;
                    } else {
                        overflow = true;
                    }
                }
                e >>= 1;
                if (e > 0) {
                    Object square = mulLongs(factor, factor);
                    if (square instanceof Long l) {
                        factor = l;
                    } else {
                        overflow = true;
                    }
                }
            }
            if (!overflow) {
                return result;
            }
        }
        return Math.pow(((Number) x).doubleValue(), ((Number) y).doubleValue());
    }

    public static long shiftLeft(long a, long b) {
        if (b < 0) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("ArithmeticError", "Bit shift by negative number");
        }
        return b >= 64 ? 0 : a << b;
    }

    public static long shiftRight(long a, long b) {
        if (b < 0) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("ArithmeticError", "Bit shift by negative number");
        }
        return b >= 64 ? (a < 0 ? -1 : 0) : a >> b;
    }

    /** `-$a`, which php-src compiles as `$a * -1`. */
    @TruffleBoundary
    public static Object negate(Node site, Object a) {
        Object x = number(site, a);
        if (x == null) {
            throw binopError("*", a, -1L);
        }
        if (x instanceof Long l) {
            return l == Long.MIN_VALUE ? -(double) l : (Object) (-l);
        }
        return -((Double) x);
    }

    /** `<<` and `>>` on operands that aren't both ints. */
    @TruffleBoundary
    public static long shift(Node site, String operator, Object a, Object b) {
        Long x = integer(site, a);
        Long y = x == null ? null : integer(site, b);
        if (y == null) {
            throw binopError(operator, a, b);
        }
        return operator.equals("<<") ? shiftLeft(x, y) : shiftRight(x, y);
    }

    /**
     * `&`, `|` and `^` on operands that aren't both ints: two strings combine byte by byte (`|` to the longer
     * length, `&` and `^` to the shorter), as bitwise_or_function and friends do; otherwise both are ints.
     */
    @TruffleBoundary
    public static Object bitwise(Node site, String operator, Object a, Object b) {
        if (a instanceof TruffleString x && b instanceof TruffleString y) {
            byte[] left = Str.readBytes(x);
            byte[] right = Str.readBytes(y);
            boolean or = operator.equals("|");
            byte[] longer = left.length >= right.length ? left : right;
            byte[] shorter = longer == left ? right : left;
            byte[] result = new byte[or ? longer.length : shorter.length];
            for (int i = 0; i < result.length; i++) {
                result[i] = i >= shorter.length ? longer[i] : (byte) switch (operator) {
                    case "|" -> left[i] | right[i];
                    case "&" -> left[i] & right[i];
                    default -> left[i] ^ right[i];
                };
            }
            return Str.of(result);
        }
        Long x = integer(site, a);
        Long y = x == null ? null : integer(site, b);
        if (y == null) {
            throw binopError(operator, a, b);
        }
        return switch (operator) {
            case "|" -> x | y;
            case "&" -> x & y;
            default -> x ^ y;
        };
    }

    /** bitwise_not_function: `~` of a float (as an int) or a string (byte by byte); a TypeError otherwise. */
    @TruffleBoundary
    public static Object bitNot(Node site, Object value) {
        if (value instanceof Long l) {
            return ~l;
        }
        if (value instanceof Double) {
            return ~integer(site, value);
        }
        if (value instanceof TruffleString s) {
            byte[] bytes = Str.readBytes(s);
            byte[] result = new byte[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                result[i] = (byte) ~bytes[i];
            }
            return Str.of(result);
        }
        throw new PhpError("TypeError", "Cannot perform bitwise not on "
                + (value instanceof Boolean b ? b.toString() : operandType(value)));
    }

    /**
     * increment_function and decrement_function for what isn't an int or a float: null, numeric strings, a
     * non-numeric string (Perl-style increment, or no change), bools (no change); arrays, objects and resources are
     * a TypeError. The deprecations and warnings are php-src's, raised at the site.
     */
    @TruffleBoundary
    public static Object step(Node site, Object value, boolean increment) {
        if (value == PhpNull.NULL) {
            if (!increment) {
                notice(site, ErrorFunctions.E_WARNING,
                        "Decrement on type null has no effect, this will change in the next major version of PHP");
            }
            return increment ? (Object) 1L : PhpNull.NULL;
        }
        if (value instanceof Boolean) {
            notice(site, ErrorFunctions.E_WARNING, (increment ? "Increment" : "Decrement")
                    + " on type bool has no effect, this will change in the next major version of PHP");
            return value;
        }
        if (value instanceof TruffleString s) {
            Object number = Convert.numericValue(s);
            if (number instanceof Long l) {
                return increment ? addLongs(l, 1) : subLongs(l, 1);
            }
            if (number instanceof Double d) {
                return increment ? d + 1 : d - 1;
            }
            byte[] bytes = Str.readBytes(s);
            if (increment) {
                notice(site, ErrorFunctions.E_DEPRECATED,
                        "Increment on non-numeric string is deprecated, use str_increment() instead");
                return Str.of(incrementString(bytes));
            }
            if (bytes.length == 0) {
                notice(site, ErrorFunctions.E_DEPRECATED, "Decrement on empty string is deprecated as non-numeric");
                return -1L;
            }
            notice(site, ErrorFunctions.E_DEPRECATED, "Decrement on non-numeric string has no effect and is deprecated");
            return value;
        }
        throw new PhpError("TypeError", (increment ? "Cannot increment " : "Cannot decrement ")
                + operandType(value));
    }

    /**
     * increment_string: the last letter or digit steps, carrying leftwards through runs of z, Z and 9; a carry out
     * of the first adds a character of the last kind ("Az" to "Ba", "zz" to "aaa"). Other bytes stop the carry.
     */
    private static byte[] incrementString(byte[] bytes) {
        if (bytes.length == 0) {
            return new byte[] {'1'};
        }
        byte[] s = bytes.clone();
        int pos = s.length - 1;
        boolean carry = false;
        byte last = 0;
        while (true) {
            byte ch = s[pos];
            if (ch >= 'a' && ch <= 'z') {
                carry = ch == 'z';
                s[pos] = carry ? (byte) 'a' : (byte) (ch + 1);
                last = 'a';
            } else if (ch >= 'A' && ch <= 'Z') {
                carry = ch == 'Z';
                s[pos] = carry ? (byte) 'A' : (byte) (ch + 1);
                last = 'A';
            } else if (ch >= '0' && ch <= '9') {
                carry = ch == '9';
                s[pos] = carry ? (byte) '0' : (byte) (ch + 1);
                last = '1';
            } else {
                carry = false;
                break;
            }
            if (!carry || pos == 0) {
                break;
            }
            pos--;
        }
        if (!carry) {
            return s;
        }
        byte[] longer = new byte[s.length + 1];
        longer[0] = last;
        System.arraycopy(s, 0, longer, 1, s.length);
        return longer;
    }

    private static void notice(Node site, int type, String message) {
        ErrorFunctions.raise(PhpContext.get(null), type, Str.of(message),
                site == null ? SourceLocation.current() : SourceLocation.of(site));
    }

    /** `$a + $b` on arrays: $a's entries, then $b's entries whose keys $a lacks. */
    @TruffleBoundary
    public static PhpArray union(PhpArray a, PhpArray b) {
        PhpArray result = a.copy();
        for (int p = b.firstPosition(); p >= 0; p = b.nextPosition(p)) {
            Object key = b.keyAt(p);
            if (!result.containsKey(key)) {
                result.put(key, b.valueAt(p));
            }
        }
        return result;
    }
}
