package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * PHP 8's type juggling: conversions, numeric strings and comparisons, following php-src's zend_operators.c.
 * Values are Long, Double, Boolean, TruffleString, PhpArray, PhpObject or PhpNull.NULL, never PhpRef.
 */
public final class Convert {
    private Convert() {
    }

    // ---- to bool / int / float ----

    public static boolean toBool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Long l) {
            return l != 0;
        }
        if (value instanceof TruffleString s) {
            return toBool(s);
        }
        if (value instanceof Double d) {
            return d != 0.0;
        }
        if (value == PhpNull.NULL) {
            return false;
        }
        if (value instanceof PhpArray a) {
            return a.size() > 0;
        }
        return true;
    }

    @TruffleBoundary
    private static boolean toBool(TruffleString s) {
        int length = Str.length(s);
        return !(length == 0 || length == 1 && Str.byteAt(s, 0) == '0');
    }

    public static long toLong(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof Double d) {
            return doubleToLong(d);
        }
        if (value instanceof TruffleString s) {
            return stringToLong(s);
        }
        if (value == PhpNull.NULL) {
            return 0;
        }
        if (value instanceof PhpArray a) {
            return a.size() > 0 ? 1 : 0;
        }
        if (value instanceof PhpResource r) {
            return r.getId();
        }
        return 1;
    }

    /** php-src's zend_dval_to_lval: out-of-range and non-finite values become 0. */
    public static long doubleToLong(double d) {
        if (!Double.isFinite(d) || d >= 9.223372036854775807E18 || d < -9.223372036854775808E18) {
            return 0;
        }
        return (long) d;
    }

    @TruffleBoundary
    public static long stringToLong(TruffleString s) {
        Object number = parseNumber(Str.readBytes(s), true);
        if (number instanceof Long l) {
            return l;
        }
        if (number instanceof Double d) {
            return doubleToLong(d);
        }
        return 0;
    }

    public static double toDouble(Object value) {
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof TruffleString s) {
            return stringToDouble(s);
        }
        if (value == PhpNull.NULL) {
            return 0;
        }
        if (value instanceof PhpArray a) {
            return a.size() > 0 ? 1 : 0;
        }
        if (value instanceof PhpResource r) {
            return r.getId();
        }
        return 1;
    }

    @TruffleBoundary
    public static double stringToDouble(TruffleString s) {
        Object number = parseNumber(Str.readBytes(s), true);
        if (number instanceof Long l) {
            return l;
        }
        if (number instanceof Double d) {
            return d;
        }
        return 0;
    }

    /** The number an arithmetic operand stands for: Long or Double. */
    public static Object toNumber(Object value) {
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
            return stringToNumber(s);
        }
        if (value instanceof PhpArray) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Unsupported operand types: array");
        }
        return 1L;
    }

    @TruffleBoundary
    public static Object stringToNumber(TruffleString s) {
        Object number = parseNumber(Str.readBytes(s), true);
        return number == null ? (Object) 0L : number;
    }

    // ---- numeric strings ----

    private static boolean isWhitespace(int b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == 0x0B || b == '\f';
    }

    /**
     * Parses a PHP numeric string as php-src's _is_numeric_string_ex does: optional leading whitespace, sign,
     * digits, fraction and exponent, optional trailing whitespace. Returns Long or Double, or null when the
     * string isn't numeric. With allowTrailing, a numeric prefix followed by other bytes ("12abc") also counts,
     * as in arithmetic and (int) casts.
     */
    @TruffleBoundary
    public static Object parseNumber(byte[] s, boolean allowTrailing) {
        int n = s.length;
        int i = 0;
        while (i < n && isWhitespace(s[i])) {
            i++;
        }
        int start = i;
        if (i < n && (s[i] == '+' || s[i] == '-')) {
            i++;
        }
        int digitsStart = i;
        while (i < n && s[i] >= '0' && s[i] <= '9') {
            i++;
        }
        int intDigits = i - digitsStart;
        boolean isDouble = false;
        int fracDigits = 0;
        if (i < n && s[i] == '.') {
            int j = i + 1;
            while (j < n && s[j] >= '0' && s[j] <= '9') {
                j++;
            }
            fracDigits = j - i - 1;
            if (intDigits > 0 || fracDigits > 0) {
                isDouble = true;
                i = j;
            }
        }
        if (intDigits == 0 && fracDigits == 0) {
            return null;
        }
        if (i < n && (s[i] == 'e' || s[i] == 'E')) {
            int j = i + 1;
            if (j < n && (s[j] == '+' || s[j] == '-')) {
                j++;
            }
            int expStart = j;
            while (j < n && s[j] >= '0' && s[j] <= '9') {
                j++;
            }
            if (j > expStart) {
                isDouble = true;
                i = j;
            }
        }
        int end = i;
        while (i < n && isWhitespace(s[i])) {
            i++;
        }
        if (i < n && !allowTrailing) {
            return null;
        }
        String text = new String(s, start, end - start, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!isDouble) {
            try {
                return Long.parseLong(text.startsWith("+") ? text.substring(1) : text);
            } catch (NumberFormatException overflow) {
                return Double.parseDouble(text);
            }
        }
        return Double.parseDouble(text);
    }

    /** is_numeric() and the numeric-string test of comparisons: the whole string, whitespace allowed around. */
    @TruffleBoundary
    public static Object numericValue(TruffleString s) {
        return parseNumber(Str.readBytes(s), false);
    }

    // ---- to string ----

    @TruffleBoundary
    public static TruffleString toStr(Object value) {
        if (value instanceof TruffleString s) {
            return s;
        }
        if (value instanceof Long l) {
            return Str.of(Long.toString(l));
        }
        if (value instanceof Boolean b) {
            return b ? Str.of("1") : Str.EMPTY;
        }
        if (value instanceof Double d) {
            return Str.of(doubleToString(d));
        }
        if (value == PhpNull.NULL) {
            return Str.EMPTY;
        }
        if (value instanceof PhpArray) {
            return Str.of("Array");
        }
        if (value instanceof PhpObject o) {
            return o.toPhpString();
        }
        if (value instanceof PhpResource r) {
            return Str.of("Resource id #" + r.getId());
        }
        CompilerDirectives.transferToInterpreter();
        throw new PhpError("Cannot convert " + value + " to string");
    }

    /** A float as echo and string conversion print it: php-src's zend_gcvt with precision=14. */
    @TruffleBoundary
    public static String doubleToString(double d) {
        return formatDouble(d, 14);
    }

    /** A float with serialize_precision=-1 (var_export, json_encode): the shortest string that round-trips. */
    @TruffleBoundary
    public static String doubleToRepr(double d) {
        if (!Double.isFinite(d)) {
            return Double.isNaN(d) ? "NAN" : d > 0 ? "INF" : "-INF";
        }
        if (d == 0) {
            return 1 / d < 0 ? "-0.0" : "0.0";
        }
        BigDecimal exact = new BigDecimal(Double.toString(Math.abs(d)));
        return (d < 0 ? "-" : "") + gcvt(exact.unscaledValue().toString(), exact.precision() - exact.scale(), 17);
    }

    private static String formatDouble(double d, int precision) {
        if (Double.isNaN(d)) {
            return "NAN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "INF" : "-INF";
        }
        if (d == 0) {
            return 1 / d < 0 ? "-0" : "0";
        }
        BigDecimal rounded = new BigDecimal(Math.abs(d)).round(new MathContext(precision, RoundingMode.HALF_EVEN));
        String digits = rounded.unscaledValue().toString();
        int decimalPoint = rounded.precision() - rounded.scale();
        return (d < 0 ? "-" : "") + gcvt(digits, decimalPoint, precision);
    }

    /**
     * Lays out significant digits as zend_gcvt does: digits "d1d2..." with the decimal point after decimalPoint
     * digits; exponential form ("1.0E+25") when decimalPoint < -3 or > ndigit.
     */
    private static String gcvt(String rawDigits, int decimalPoint, int ndigit) {
        int significant = rawDigits.length();
        while (significant > 0 && rawDigits.charAt(significant - 1) == '0') {
            significant--;
        }
        String digits = significant == 0 ? "0" : rawDigits.substring(0, significant);
        if (decimalPoint < -3 || decimalPoint > ndigit) {
            StringBuilder out = new StringBuilder();
            out.append(digits.charAt(0)).append('.');
            out.append(digits.length() > 1 ? digits.substring(1) : "0");
            int exponent = decimalPoint - 1;
            out.append('E').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
            return out.toString();
        }
        if (decimalPoint <= 0) {
            return "0." + "0".repeat(-decimalPoint) + digits;
        }
        if (digits.length() <= decimalPoint) {
            return digits + "0".repeat(decimalPoint - digits.length());
        }
        return digits.substring(0, decimalPoint) + "." + digits.substring(decimalPoint);
    }

    // ---- comparisons ----

    /** `===`. */
    @TruffleBoundary
    public static boolean strictEquals(Object a, Object b) {
        if (a instanceof Long x) {
            return b instanceof Long y && x.longValue() == y.longValue();
        }
        if (a instanceof TruffleString x) {
            return b instanceof TruffleString y && Str.equal(x, y);
        }
        if (a instanceof Boolean x) {
            return b instanceof Boolean y && x.booleanValue() == y.booleanValue();
        }
        if (a instanceof Double x) {
            return b instanceof Double y && x.doubleValue() == y.doubleValue();
        }
        if (a == PhpNull.NULL) {
            return b == PhpNull.NULL;
        }
        if (a instanceof PhpArray x) {
            if (!(b instanceof PhpArray y) || x.size() != y.size()) {
                return false;
            }
            int p = x.firstPosition();
            int q = y.firstPosition();
            while (p >= 0) {
                if (!strictEquals(x.keyAt(p), y.keyAt(q)) || !strictEquals(x.valueAt(p), y.valueAt(q))) {
                    return false;
                }
                p = x.nextPosition(p);
                q = y.nextPosition(q);
            }
            return true;
        }
        return a == b;
    }

    /** `==`. */
    @TruffleBoundary
    public static boolean looseEquals(Object a, Object b) {
        if (a instanceof TruffleString x && b instanceof TruffleString y) {
            Object nx = numericValue(x);
            Object ny = nx == null ? null : numericValue(y);
            if (nx != null && ny != null) {
                return compareNumbers(nx, ny) == 0;
            }
            return Str.equal(x, y);
        }
        return compare(a, b) == 0;
    }

    /** `<=>`, as php-src's zend_compare (PHP 8 semantics for number/string pairs). */
    @TruffleBoundary
    public static int compare(Object a, Object b) {
        if ((a instanceof Long || a instanceof Double) && (b instanceof Long || b instanceof Double)) {
            return compareNumbers(a, b);
        }
        if (a instanceof TruffleString x && b instanceof TruffleString y) {
            Object nx = numericValue(x);
            Object ny = nx == null ? null : numericValue(y);
            if (nx != null && ny != null) {
                return compareNumbers(nx, ny);
            }
            return Integer.signum(Str.compare(x, y));
        }
        if (a instanceof Boolean || b instanceof Boolean || a == PhpNull.NULL || b == PhpNull.NULL) {
            if (a == PhpNull.NULL && b instanceof TruffleString y) {
                return Str.length(y) == 0 ? 0 : -1;
            }
            if (b == PhpNull.NULL && a instanceof TruffleString x) {
                return Str.length(x) == 0 ? 0 : 1;
            }
            if (a == PhpNull.NULL && b instanceof PhpObject) {
                return -1;
            }
            if (b == PhpNull.NULL && a instanceof PhpObject) {
                return 1;
            }
            return Boolean.compare(toBool(a), toBool(b));
        }
        if ((a instanceof Long || a instanceof Double) && b instanceof TruffleString y) {
            Object ny = numericValue(y);
            if (ny != null) {
                return compareNumbers(a, ny);
            }
            return Integer.signum(Str.compare(toStr(a), y));
        }
        if (a instanceof TruffleString x && (b instanceof Long || b instanceof Double)) {
            Object nx = numericValue(x);
            if (nx != null) {
                return compareNumbers(nx, b);
            }
            return Integer.signum(Str.compare(x, toStr(b)));
        }
        if (a instanceof PhpArray x && b instanceof PhpArray y) {
            if (x.size() != y.size()) {
                return x.size() < y.size() ? -1 : 1;
            }
            for (int p = x.firstPosition(); p >= 0; p = x.nextPosition(p)) {
                Object other = y.get(x.keyAt(p));
                if (other == null) {
                    return 1;
                }
                int c = compare(x.valueAt(p), other);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        }
        if (a instanceof PhpArray) {
            return 1;
        }
        if (b instanceof PhpArray) {
            return -1;
        }
        // PHP compares two objects of one class property by property; that is not implemented: unequal
        if (a == b) {
            return 0;
        }
        return 1;
    }

    private static int compareNumbers(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) {
            return Long.compare(x, y);
        }
        double x = ((Number) a).doubleValue();
        double y = ((Number) b).doubleValue();
        return x < y ? -1 : x > y ? 1 : x == y ? 0 : 1;
    }

    /** PHP type name as get_debug_type() and error messages report it: int, float, null, a class name... */
    public static String debugType(Object value) {
        if (value instanceof PhpObject object) {
            return object.getPhpClass().getName();
        }
        if (value instanceof PhpClosure) {
            return "Closure";
        }
        return switch (typeName(value)) {
            case "integer" -> "int";
            case "double" -> "float";
            case "boolean" -> "bool";
            case "NULL" -> "null";
            default -> typeName(value);
        };
    }

    /** PHP type name as gettype() reports it. */
    public static String typeName(Object value) {
        if (value instanceof Long) {
            return "integer";
        }
        if (value instanceof Double) {
            return "double";
        }
        if (value instanceof TruffleString) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof PhpArray) {
            return "array";
        }
        if (value == PhpNull.NULL) {
            return "NULL";
        }
        if (value instanceof PhpResource r) {
            return r.isClosed() ? "resource (closed)" : "resource";
        }
        return "object";
    }
}
