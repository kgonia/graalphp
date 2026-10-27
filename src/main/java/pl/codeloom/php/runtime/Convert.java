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
        if (value instanceof PhpObject o && o.getPhpClass().getHandlers() != null
                && o.getPhpClass().getHandlers().castsToId()) {
            // a cast_object handler that gives the object's id (ext/curl's handles, once resources)
            return o.id();
        }
        return 1;
    }

    /**
     * php-src's zend_dval_to_lval: non-finite values become 0, out-of-range ones wrap modulo 2^64
     * (zend_dval_to_lval_slow).
     */
    public static long doubleToLong(double d) {
        if (d >= 9.223372036854775807E18 || d < -9.223372036854775808E18 || d != d) {
            return outOfRangeToLong(d);
        }
        return (long) d;
    }

    @TruffleBoundary
    private static long outOfRangeToLong(double d) {
        if (!Double.isFinite(d)) {
            return 0;
        }
        double twoPow64 = 18446744073709551616.0;
        double dmod = d % twoPow64;
        if (dmod < 0) {
            dmod += twoPow64;
        }
        // (zend_long)(zend_ulong)dmod
        return dmod >= 9.223372036854775807E18 ? (long) (dmod - 9.223372036854775807E18) + Long.MIN_VALUE
                : (long) dmod;
    }

    /** zval_get_long of a string: a float-looking number saturates (zend_dval_to_lval_cap), as strtol would. */
    @TruffleBoundary
    public static long stringToLong(TruffleString s) {
        Object number = parseNumber(Str.readBytes(s), true);
        if (number instanceof Long l) {
            return l;
        }
        if (number instanceof Double d) {
            if (!Double.isFinite(d)) {
                return 0;
            }
            if (d >= 9.223372036854775807E18 || d < -9.223372036854775808E18) {
                return d > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
            }
            return (long) (double) d;
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

    /**
     * A float with serialize_precision=-1, as zend_gcvt(d, 17) in zend_dtoa's mode 0 prints it (serialize,
     * var_dump; var_export and json_encode add their ".0"): the shortest digits that round-trip, closest to d.
     * Zeros print as "0" and "-0".
     */
    @TruffleBoundary
    public static String doubleToRepr(double d) {
        if (!Double.isFinite(d)) {
            return Double.isNaN(d) ? "NAN" : d > 0 ? "INF" : "-INF";
        }
        if (d == 0) {
            return 1 / d < 0 ? "-0" : "0";
        }
        double abs = Math.abs(d);
        BigDecimal shortest = new BigDecimal(Double.toString(abs));
        if (shortest.precision() == 2) {
            // Double.toString picks among 1- and 2-digit decimals when one digit would do (4.9E-324); dtoa
            // takes the 1-digit one closest to d
            BigDecimal down = shortest.round(new MathContext(1, RoundingMode.FLOOR));
            BigDecimal up = shortest.round(new MathContext(1, RoundingMode.CEILING));
            boolean downOk = down.doubleValue() == abs;
            boolean upOk = up.doubleValue() == abs;
            if (downOk || upOk) {
                BigDecimal exact = new BigDecimal(abs);
                shortest = downOk && (!upOk || exact.subtract(down).compareTo(up.subtract(exact)) <= 0) ? down : up;
            }
        }
        String digits = shortest.unscaledValue().toString();
        return (d < 0 ? "-" : "") + gcvt(digits, shortest.precision() - shortest.scale(), 17);
    }

    /**
     * zend_gcvt(d, precision, '.', exponent) of a finite float: precision significant digits (zend_dtoa's mode
     * 2), or the shortest round-trip digits (mode 0) when precision is negative.
     */
    @TruffleBoundary
    public static String gcvt(double d, int precision, char exponent) {
        String text = precision < 0 ? doubleToRepr(d) : formatDouble(d, precision);
        return exponent == 'E' ? text : text.replace('E', exponent);
    }

    /** var_export's float (smart_str_append_double with zero_frac): doubleToRepr, plus ".0" if integral. */
    @TruffleBoundary
    public static String doubleToExport(double d) {
        String repr = doubleToRepr(d);
        return Double.isFinite(d) && repr.indexOf('.') < 0 ? repr + ".0" : repr;
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

    /**
     * The arrays and objects whose comparison is in progress, innermost first: php-src's recursion protection of
     * the left operand (zend_hash_compare, zend_std_compare_objects).
     */
    private record Guard(Object container, Guard outer) {
        static boolean contains(Guard guard, Object container) {
            for (Guard g = guard; g != null; g = g.outer) {
                if (g.container == container) {
                    return true;
                }
            }
            return false;
        }
    }

    private static PhpError nestingTooDeep() {
        return new PhpError("Nesting level too deep - recursive dependency?");
    }

    /** `===`, as php-src's zend_is_identical. */
    @TruffleBoundary
    public static boolean strictEquals(Object a, Object b) {
        return identical(a, b, null);
    }

    private static boolean identical(Object a, Object b, Guard guard) {
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
            if (!(b instanceof PhpArray y)) {
                return false;
            }
            if (x == y) {
                return true;
            }
            if (x.size() != y.size()) {
                return false;
            }
            if (Guard.contains(guard, x)) {
                throw nestingTooDeep();
            }
            Guard inner = new Guard(x, guard);
            int p = x.firstPosition();
            int q = y.firstPosition();
            while (p >= 0) {
                Object kx = x.keyAt(p);
                Object ky = y.keyAt(q);
                if (kx instanceof Long lx ? !(ky instanceof Long ly && lx.longValue() == ly.longValue())
                        : !(ky instanceof TruffleString sy && Str.equal((TruffleString) kx, sy))) {
                    return false;
                }
                if (!identical(x.valueAt(p), y.valueAt(q), inner)) {
                    return false;
                }
                p = x.nextPosition(p);
                q = y.nextPosition(q);
            }
            return true;
        }
        return a == b;
    }

    /** `==`, as php-src's zend_compare(a, b) == 0 (zendi_smart_str_equals for two strings). */
    @TruffleBoundary
    public static boolean looseEquals(Object a, Object b) {
        if (a instanceof TruffleString x && b instanceof TruffleString y) {
            return smartStrEquals(x, y);
        }
        return compare(a, b) == 0;
    }

    /**
     * `<=>`, as php-src's zend_compare: -1, 0 or 1, where 1 also stands for uncomparable (objects of different
     * classes, arrays missing a key of the other).
     */
    @TruffleBoundary
    public static int compare(Object a, Object b) {
        return compare(a, b, null);
    }

    private static int compare(Object a, Object b, Guard guard) {
        if ((a instanceof Long || a instanceof Double) && (b instanceof Long || b instanceof Double)) {
            return compareNumbers(a, b);
        }
        if (a instanceof TruffleString x && b instanceof TruffleString y) {
            return smartStrcmp(x, y);
        }
        boolean converted = false;
        while (true) {
            int ta = typeOrder(a);
            int tb = typeOrder(b);
            if ((ta == LONG || ta == DOUBLE) && (tb == LONG || tb == DOUBLE)) {
                return compareNumbers(a, b);
            }
            if (ta == ARRAY && tb == ARRAY) {
                return compareArrays((PhpArray) a, (PhpArray) b, guard);
            }
            if (ta <= TRUE && tb <= TRUE) {
                // null and false are equal; true is above both
                return Integer.compare(ta == TRUE ? 1 : 0, tb == TRUE ? 1 : 0);
            }
            if (ta == STRING && tb == STRING) {
                return smartStrcmp((TruffleString) a, (TruffleString) b);
            }
            if (ta == NULL && tb == STRING) {
                return Str.length((TruffleString) b) == 0 ? 0 : -1;
            }
            if (ta == STRING && tb == NULL) {
                return Str.length((TruffleString) a) == 0 ? 0 : 1;
            }
            if (ta == LONG && tb == STRING) {
                return compareLongToString((Long) a, (TruffleString) b);
            }
            if (ta == STRING && tb == LONG) {
                return -compareLongToString((Long) b, (TruffleString) a);
            }
            if (ta == DOUBLE && tb == STRING) {
                double d = (Double) a;
                return Double.isNaN(d) ? 1 : compareDoubleToString(d, (TruffleString) b);
            }
            if (ta == STRING && tb == DOUBLE) {
                double d = (Double) b;
                return Double.isNaN(d) ? 1 : -compareDoubleToString(d, (TruffleString) a);
            }
            if (ta == OBJECT && tb == NULL) {
                return 1;
            }
            if (ta == NULL && tb == OBJECT) {
                return -1;
            }
            if (ta == OBJECT || tb == OBJECT) {
                if (ta == OBJECT && tb == OBJECT) {
                    if (a == b) {
                        return 0;
                    }
                } else if (ta == TRUE || ta == FALSE) {
                    // a plain object casts to true
                    return compare(a, Boolean.TRUE, guard);
                } else if (tb == TRUE || tb == FALSE) {
                    return compare(Boolean.TRUE, b, guard);
                }
                return compareObjects(a, b, ta == OBJECT, guard);
            }
            if (!converted) {
                boolean nanA = a instanceof Double d && Double.isNaN(d);
                boolean nanB = b instanceof Double d && Double.isNaN(d);
                if (nanA || nanB) {
                    // NAN used to be cast to true; php-src keeps that order by hand
                    if (ta < TRUE) {
                        return -1;
                    } else if (ta == TRUE || tb == TRUE) {
                        return 0;
                    } else if (tb < TRUE) {
                        return 1;
                    } else if (ta != DOUBLE) {
                        a = scalarToNumber(a);
                        converted = true;
                    } else if (tb != DOUBLE) {
                        b = scalarToNumber(b);
                        converted = true;
                    }
                } else if (ta < TRUE) {
                    return toBool(b) ? -1 : 0;
                } else if (ta == TRUE) {
                    return toBool(b) ? 0 : 1;
                } else if (tb < TRUE) {
                    return toBool(a) ? 1 : 0;
                } else if (tb == TRUE) {
                    return toBool(a) ? 0 : -1;
                } else {
                    a = scalarToNumber(a);
                    b = scalarToNumber(b);
                    converted = true;
                }
            } else if (ta == ARRAY) {
                return 1;
            } else if (tb == ARRAY) {
                return -1;
            } else {
                return 1;
            }
        }
    }

    private static final int NULL = 1;
    private static final int FALSE = 2;
    private static final int TRUE = 3;
    private static final int LONG = 4;
    private static final int DOUBLE = 5;
    private static final int STRING = 6;
    private static final int ARRAY = 7;
    private static final int OBJECT = 8;
    private static final int RESOURCE = 9;

    /** php-src's type codes, in their order (IS_NULL < IS_FALSE < IS_TRUE < ...). */
    private static int typeOrder(Object value) {
        if (value instanceof Long) {
            return LONG;
        }
        if (value instanceof Double) {
            return DOUBLE;
        }
        if (value instanceof TruffleString) {
            return STRING;
        }
        if (value instanceof Boolean b) {
            return b ? TRUE : FALSE;
        }
        if (value instanceof PhpArray) {
            return ARRAY;
        }
        if (value instanceof PhpObject || value instanceof PhpClosure) {
            return OBJECT;
        }
        if (value instanceof PhpResource) {
            return RESOURCE;
        }
        return NULL;
    }

    /** _zendi_convert_scalar_to_number_silent: strings by their leading number, resources by id. */
    private static Object scalarToNumber(Object value) {
        if (value instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (value == PhpNull.NULL) {
            return 0L;
        }
        if (value instanceof TruffleString s) {
            Object number = parseNumber(Str.readBytes(s), true);
            return number == null ? (Object) 0L : number;
        }
        if (value instanceof PhpResource r) {
            return (long) r.getId();
        }
        return value;
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

    private static int compareLongToString(long l, TruffleString s) {
        Object number = numericValue(s);
        if (number instanceof Long sl) {
            return Long.compare(l, sl);
        }
        if (number instanceof Double sd) {
            return compareNumbers((double) l, sd);
        }
        return Integer.signum(Str.compare(Str.of(Long.toString(l)), s));
    }

    private static int compareDoubleToString(double d, TruffleString s) {
        Object number = numericValue(s);
        if (number != null) {
            return compareNumbers(d, number instanceof Long sl ? (double) sl : (Double) number);
        }
        return Integer.signum(Str.compare(Str.of(doubleToString(d)), s));
    }

    /**
     * is_numeric_string_ex's oflow: the sign of an integer string too big for an int (parsed as a float), else 0.
     */
    private static int overflow(TruffleString s, Object number) {
        if (!(number instanceof Double d)) {
            return 0;
        }
        int length = Str.length(s);
        for (int i = 0; i < length; i++) {
            int c = Str.byteAt(s, i);
            if (c == '.' || c == 'e' || c == 'E') {
                return 0;
            }
        }
        return d > 0 ? 1 : -1;
    }

    /** zendi_smart_strcmp: numeric strings compare as numbers, others byte-wise. */
    private static int smartStrcmp(TruffleString x, TruffleString y) {
        if (x == y) {
            return 0;
        }
        Object nx = numericValue(x);
        Object ny = nx == null ? null : numericValue(y);
        if (nx != null && ny != null) {
            int oflowX = overflow(x, nx);
            int oflowY = overflow(y, ny);
            if (oflowX != 0 && oflowX == oflowY && (Double) nx - (Double) ny == 0.0) {
                // both integers overflowed to the same side: the float comparison may have lost the difference
                return Integer.signum(Str.compare(x, y));
            }
            if (nx instanceof Double || ny instanceof Double) {
                double dx;
                double dy;
                if (!(nx instanceof Double)) {
                    if (oflowY != 0) {
                        return -oflowY;
                    }
                    dx = (Long) nx;
                    dy = (Double) ny;
                } else if (!(ny instanceof Double)) {
                    if (oflowX != 0) {
                        return oflowX;
                    }
                    dx = (Double) nx;
                    dy = (Long) ny;
                } else {
                    dx = (Double) nx;
                    dy = (Double) ny;
                    if (dx == dy && !Double.isFinite(dx)) {
                        return Integer.signum(Str.compare(x, y));
                    }
                }
                double difference = dx - dy;
                return difference > 0 ? 1 : difference < 0 ? -1 : 0;
            }
            return Long.compare((Long) nx, (Long) ny);
        }
        return Integer.signum(Str.compare(x, y));
    }

    /** zendi_smart_str_equals: `==` of two strings. */
    @TruffleBoundary
    public static boolean smartStrEquals(TruffleString x, TruffleString y) {
        if (Str.equal(x, y)) {
            return true;
        }
        Object nx = numericValue(x);
        Object ny = nx == null ? null : numericValue(y);
        if (nx == null || ny == null) {
            return false;
        }
        int oflowX = overflow(x, nx);
        int oflowY = overflow(y, ny);
        if (oflowX != 0 && oflowX == oflowY && (Double) nx - (Double) ny == 0.0) {
            // both integers overflowed to the same side, and differ as strings
            return false;
        }
        if (nx instanceof Double || ny instanceof Double) {
            if (!(nx instanceof Double)) {
                return oflowY == 0 && (double) (Long) nx == (Double) ny;
            }
            if (!(ny instanceof Double)) {
                return oflowX == 0 && (Double) nx == (double) (Long) ny;
            }
            double dx = (Double) nx;
            double dy = (Double) ny;
            // two infinities of one sign compare as strings, which differ here
            return dx == dy && Double.isFinite(dx);
        }
        return ((Long) nx).longValue() == (Long) ny;
    }

    /**
     * zend_compare_symbol_tables: by size, then each element of a against the element under the same key in b
     * (1 when b lacks the key).
     */
    private static int compareArrays(PhpArray a, PhpArray b, Guard guard) {
        if (a == b) {
            return 0;
        }
        if (a.size() != b.size()) {
            return a.size() < b.size() ? -1 : 1;
        }
        if (Guard.contains(guard, a)) {
            throw nestingTooDeep();
        }
        Guard inner = new Guard(a, guard);
        for (int p = a.firstPosition(); p >= 0; p = a.nextPosition(p)) {
            Object other = b.get(a.keyAt(p));
            if (other == null) {
                return 1;
            }
            int c = compare(a.valueAt(p), PhpRef.deref(other), inner);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /**
     * The compare handler of the object operand (objectLeft: a, else b) against the other operand, which is not
     * null or a bool: zend_std_compare_objects, with ext/date's handlers for the prelude's date classes.
     */
    private static int compareObjects(Object a, Object b, boolean objectLeft, Guard guard) {
        Object object = objectLeft ? a : b;
        Object value = objectLeft ? b : a;
        if (typeOrder(value) != OBJECT) {
            Object casted;
            if (value instanceof TruffleString && object instanceof PhpObject o
                    && o.getPhpClass().findMethod("__tostring") != null) {
                casted = o.toPhpString();
            } else if (value instanceof Long) {
                // php-src notices "Object of class X could not be converted to int"
                casted = 1L;
            } else if (value instanceof Double) {
                casted = 1.0;
            } else {
                return objectLeft ? 1 : -1;
            }
            return objectLeft ? compare(casted, value, guard) : compare(value, casted, guard);
        }
        if (a == b) {
            return 0;
        }
        if (!(a instanceof PhpObject x) || !(b instanceof PhpObject y)) {
            // closures compare only to themselves
            return 1;
        }
        PhpClass classX = x.getPhpClass();
        if (classX.isSubclassOf("datetimeinterface") && y.getPhpClass().isSubclassOf("datetimeinterface")) {
            // date_object_compare_date: the instants (prelude/date.php's timestamp and microseconds)
            int c = compare(PhpRef.deref(x.getProperty("timestamp")), PhpRef.deref(y.getProperty("timestamp")));
            return c != 0 ? c : compare(PhpRef.deref(x.getProperty("microseconds")),
                    PhpRef.deref(y.getProperty("microseconds")));
        }
        if (classX != y.getPhpClass() || classX.isSubclassOf("dateinterval")) {
            // different classes, or DateInterval (php-src warns "Cannot compare DateInterval objects")
            return 1;
        }
        if (Guard.contains(guard, x)) {
            throw nestingTooDeep();
        }
        Guard inner = new Guard(x, guard);
        Object[] namesX = x.propertyNames();
        Object[] namesY = y.propertyNames();
        String[] declared = classX.declaredNames();
        if (onlyDeclared(namesX, classX) && onlyDeclared(namesY, classX)) {
            // no dynamic properties: the declared slots in order, an unset one above a set one
            for (String name : declared) {
                Object vx = x.getProperty(name);
                Object vy = y.getProperty(name);
                if (vx != null) {
                    if (vy == null) {
                        return 1;
                    }
                    int c = compare(PhpRef.deref(vx), PhpRef.deref(vy), inner);
                    if (c != 0) {
                        return c;
                    }
                } else if (vy != null) {
                    return 1;
                }
            }
            return 0;
        }
        if (namesX.length != namesY.length) {
            return namesX.length < namesY.length ? -1 : 1;
        }
        for (Object name : namesX) {
            Object vy = y.getProperty((String) name);
            if (vy == null) {
                return 1;
            }
            int c = compare(PhpRef.deref(x.getProperty((String) name)), PhpRef.deref(vy), inner);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static boolean onlyDeclared(Object[] names, PhpClass phpClass) {
        for (Object name : names) {
            if (!phpClass.isDeclaredProperty((String) name)) {
                return false;
            }
        }
        return true;
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
