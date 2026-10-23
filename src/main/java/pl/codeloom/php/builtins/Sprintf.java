package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.Str;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

/**
 * sprintf()-family formatting: a port of php-src's php_formatted_print (ext/standard/formatted_print.c), with
 * php_conv_fp (main/snprintf.c) for %e and %f and zend_gcvt for %g and %h.
 */
final class Sprintf {
    private static final int ARG_NUM_NEXT = -1;
    private static final int FLOAT_PRECISION = 6;
    private static final int MAX_FLOAT_PRECISION = 53;
    private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] HEX_UPPER = "0123456789ABCDEF".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] NAN = {'N', 'a', 'N'};
    private static final byte[] INF = {'I', 'N', 'F'};
    private static final byte[] MINUS_INF = {'-', 'I', 'N', 'F'};

    private Sprintf() {
    }

    /** Formats with the format at argument formatIndex and the following arguments (sprintf, printf). */
    @TruffleBoundary
    static TruffleString format(Object[] a, int formatIndex) {
        Object[] values = new Object[Math.max(0, Args.count(a) - formatIndex - 1)];
        for (int i = 0; i < values.length; i++) {
            values[i] = Args.get(a, formatIndex + 1 + i);
        }
        return format(Args.str(a, formatIndex), values, formatIndex + 1);
    }

    /** vsprintf: the arguments from an array. */
    @TruffleBoundary
    static TruffleString formatArray(TruffleString format, PhpArray args) {
        Object[] values = new Object[args.size()];
        int i = 0;
        for (int p = args.firstPosition(); p >= 0; p = args.nextPosition(p)) {
            values[i++] = args.valueAt(p);
        }
        return format(format, values, -1);
    }

    /** The format parser's position in the format string. */
    private static final class Cursor {
        final byte[] f;
        final int length;
        int pos;

        Cursor(byte[] f, int length) {
            this.f = f;
            this.length = length;
        }

        /** The current byte, or 0 at the end (C's terminating NUL). */
        int peek() {
            return pos < length ? f[pos] & 0xFF : 0;
        }

        boolean isDigit() {
            int c = peek();
            return c >= '0' && c <= '9';
        }

        /** php_sprintf_getnumber: a decimal number, -1 if it is INT_MAX or more. */
        int number() {
            long n = 0;
            while (isDigit()) {
                n = Math.min(n * 10 + (f[pos++] - '0'), Integer.MAX_VALUE);
            }
            return n >= Integer.MAX_VALUE ? -1 : (int) n;
        }

        /** php_sprintf_get_argnum: "n$" as a 0-based argument index, or ARG_NUM_NEXT. */
        int argnum() {
            int p = pos;
            while (p < length && f[p] >= '0' && f[p] <= '9') {
                p++;
            }
            if (p >= length || f[p] != '$') {
                return ARG_NUM_NEXT;
            }
            int argnum = number();
            if (argnum <= 0) {
                throw new PhpError("ValueError", "Argument number specifier must be greater than zero and less than "
                        + Integer.MAX_VALUE);
            }
            pos++;
            return argnum - 1;
        }
    }

    /**
     * nbAdditional is the number of arguments before the values (1 for sprintf's format), or -1 for vsprintf's
     * array, as php_formatted_print's nb_additional_parameters.
     */
    private static TruffleString format(TruffleString formatString, Object[] args, int nbAdditional) {
        int length = Str.length(formatString);
        Cursor format = new Cursor(Str.readBytes(formatString), length);
        byte[] f = format.f;
        ByteBuilder out = new ByteBuilder(length + 16);
        int currarg = 0;
        int maxMissingArgnum = -1;
        while (format.pos < length) {
            int percent = format.pos;
            while (percent < length && f[percent] != '%') {
                percent++;
            }
            out.write(f, format.pos, percent - format.pos);
            if (percent >= length) {
                break;
            }
            format.pos = percent + 1;
            if (format.peek() == '%') {
                out.write('%');
                format.pos++;
                continue;
            }
            boolean alignLeft = false;
            boolean adjustPrecision = false;
            byte padding = ' ';
            boolean alwaysSign = false;
            boolean expprec = false;
            int width;
            int precision;
            int argnum;
            int c = format.peek();
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                width = 0;
                precision = 0;
                argnum = ARG_NUM_NEXT;
            } else {
                argnum = format.argnum();
                for (;; format.pos++) {
                    c = format.peek();
                    if (c == ' ' || c == '0') {
                        padding = (byte) c;
                    } else if (c == '-') {
                        alignLeft = true;
                    } else if (c == '+') {
                        alwaysSign = true;
                    } else if (c == '\'') {
                        if (length - format.pos > 1) {
                            format.pos++;
                            padding = f[format.pos];
                        } else {
                            throw new PhpError("ValueError", "Missing padding character");
                        }
                    } else {
                        break;
                    }
                }
                if (format.peek() == '*') {
                    format.pos++;
                    int widthArgnum = format.argnum();
                    if (widthArgnum == ARG_NUM_NEXT) {
                        widthArgnum = currarg++;
                    }
                    if (widthArgnum >= args.length) {
                        maxMissingArgnum = Math.max(maxMissingArgnum, widthArgnum);
                        continue;
                    }
                    if (!(args[widthArgnum] instanceof Long w)) {
                        throw new PhpError("ValueError", "Width must be an integer");
                    }
                    if (w < 0 || w > Integer.MAX_VALUE) {
                        throw new PhpError("ValueError", "Width must be between 0 and " + Integer.MAX_VALUE);
                    }
                    width = (int) (long) w;
                } else if (format.isDigit()) {
                    width = format.number();
                    if (width < 0) {
                        throw new PhpError("ValueError", "Width must be between 0 and " + Integer.MAX_VALUE);
                    }
                } else {
                    width = 0;
                }
                if (format.peek() == '.') {
                    format.pos++;
                    adjustPrecision = true;
                    if (format.peek() == '*') {
                        format.pos++;
                        int precisionArgnum = format.argnum();
                        if (precisionArgnum == ARG_NUM_NEXT) {
                            precisionArgnum = currarg++;
                        }
                        if (precisionArgnum >= args.length) {
                            maxMissingArgnum = Math.max(maxMissingArgnum, precisionArgnum);
                            continue;
                        }
                        if (!(args[precisionArgnum] instanceof Long p)) {
                            throw new PhpError("ValueError", "Precision must be an integer");
                        }
                        if (p < -1 || p > Integer.MAX_VALUE) {
                            throw new PhpError("ValueError", "Precision must be between -1 and " + Integer.MAX_VALUE);
                        }
                        precision = (int) (long) p;
                        expprec = true;
                    } else if (format.isDigit()) {
                        precision = format.number();
                        if (precision < 0) {
                            throw new PhpError("ValueError", "Precision must be between 0 and " + Integer.MAX_VALUE);
                        }
                        expprec = true;
                    } else {
                        precision = 0;
                    }
                } else {
                    precision = 0;
                }
            }
            if (format.peek() == 'l') {
                format.pos++;
            }
            if (argnum == ARG_NUM_NEXT) {
                argnum = currarg++;
            }
            if (argnum >= args.length) {
                maxMissingArgnum = Math.max(maxMissingArgnum, argnum);
                continue;
            }
            int spec = format.peek();
            if (expprec && precision == -1 && spec != 'g' && spec != 'G' && spec != 'h' && spec != 'H') {
                throw new PhpError("ValueError", "Precision -1 is only supported for %g, %G, %h and %H");
            }
            Object value = args[argnum];
            switch (spec) {
                case 's': {
                    TruffleString s = value instanceof TruffleString t ? t : Convert.toStr(value);
                    appendString(out, Str.readBytes(s), 0, Str.length(s), width, precision, padding, alignLeft,
                            false, expprec, false);
                    break;
                }
                case 'd':
                    appendInt(out, Convert.toLong(value), width, padding, alignLeft, alwaysSign);
                    break;
                case 'u':
                    appendUint(out, Convert.toLong(value), width, padding, alignLeft);
                    break;
                case 'e':
                case 'E':
                case 'f':
                case 'F':
                case 'g':
                case 'G':
                case 'h':
                case 'H':
                    appendDouble(out, Convert.toDouble(value), width, padding, alignLeft, precision,
                            adjustPrecision, (char) spec, alwaysSign);
                    break;
                case 'c':
                    out.write((int) Convert.toLong(value));
                    break;
                case 'o':
                    append2n(out, Convert.toLong(value), width, padding, alignLeft, 3, HEX, expprec);
                    break;
                case 'x':
                    append2n(out, Convert.toLong(value), width, padding, alignLeft, 4, HEX, expprec);
                    break;
                case 'X':
                    append2n(out, Convert.toLong(value), width, padding, alignLeft, 4, HEX_UPPER, expprec);
                    break;
                case 'b':
                    append2n(out, Convert.toLong(value), width, padding, alignLeft, 1, HEX, expprec);
                    break;
                case '%':
                    out.write('%');
                    break;
                default:
                    if (spec == 0 && format.pos >= length) {
                        throw new PhpError("ValueError", "Missing format specifier at end of string");
                    }
                    // the message is a C string: a NUL specifier ends it
                    throw new PhpError("ValueError", "Unknown format specifier \"" + (spec == 0 ? ""
                            : new String(new byte[]{(byte) spec}, StandardCharsets.ISO_8859_1) + "\""));
            }
            format.pos++;
        }
        if (maxMissingArgnum >= 0) {
            if (nbAdditional == -1) {
                throw new PhpError("ValueError", "The arguments array must contain " + (maxMissingArgnum + 1)
                        + " items, " + args.length + " given");
            }
            throw new PhpError("ArgumentCountError", (maxMissingArgnum + nbAdditional + 1)
                    + " arguments are required, " + (args.length + nbAdditional) + " given");
        }
        return Str.of(out.toByteArray());
    }

    /**
     * php_sprintf_appendstring: add[offset, offset+len) padded to minWidth, cut to maxWidth when expprec. With
     * '0' padding on the right, a sign goes before the padding: '-' when neg, else '+' (for alwaysSign), in place
     * of the first byte.
     */
    private static void appendString(ByteBuilder out, byte[] add, int offset, int len, int minWidth, int maxWidth,
                                     byte padding, boolean alignLeft, boolean neg, boolean expprec,
                                     boolean alwaysSign) {
        int copyLen = expprec ? Math.min(maxWidth, len) : len;
        int npad = minWidth < copyLen ? 0 : minWidth - copyLen;
        if (!alignLeft) {
            if ((neg || alwaysSign) && padding == '0') {
                out.write(neg ? '-' : '+');
                offset++;
                copyLen--;
            }
            for (int i = 0; i < npad; i++) {
                out.write(padding);
            }
        }
        out.write(add, offset, copyLen);
        if (alignLeft) {
            for (int i = 0; i < npad; i++) {
                out.write(padding);
            }
        }
    }

    private static void appendInt(ByteBuilder out, long number, int width, byte padding, boolean alignLeft,
                                  boolean alwaysSign) {
        if (alignLeft && padding == '0') {
            // can't right-pad 0's on integers
            padding = ' ';
        }
        if (width == 0 && !alwaysSign) {
            out.writeLong(number);
            return;
        }
        byte[] digits = Long.toString(number).getBytes(StandardCharsets.ISO_8859_1);
        boolean neg = number < 0;
        if (!neg && alwaysSign) {
            byte[] signed = new byte[digits.length + 1];
            signed[0] = '+';
            System.arraycopy(digits, 0, signed, 1, digits.length);
            digits = signed;
        }
        appendString(out, digits, 0, digits.length, width, 0, padding, alignLeft, neg, false, alwaysSign);
    }

    private static void appendUint(ByteBuilder out, long number, int width, byte padding, boolean alignLeft) {
        if (alignLeft && padding == '0') {
            padding = ' ';
        }
        byte[] digits = Long.toUnsignedString(number).getBytes(StandardCharsets.ISO_8859_1);
        appendString(out, digits, 0, digits.length, width, 0, padding, alignLeft, false, false, false);
    }

    /** php_sprintf_append2n: binary, octal or hex digits of the unsigned value. */
    private static void append2n(ByteBuilder out, long number, int width, byte padding, boolean alignLeft, int n,
                                 byte[] chartable, boolean expprec) {
        byte[] buf = new byte[64];
        int i = buf.length;
        int andbits = (1 << n) - 1;
        long num = number;
        do {
            buf[--i] = chartable[(int) (num & andbits)];
            num >>>= n;
        } while (num != 0);
        appendString(out, buf, i, buf.length - i, width, 0, padding, alignLeft, false, expprec, false);
    }

    private static void appendDouble(ByteBuilder out, double number, int width, byte padding, boolean alignLeft,
                                     int precision, boolean adjustPrecision, char fmt, boolean alwaysSign) {
        if (!adjustPrecision) {
            precision = FLOAT_PRECISION;
        } else if (precision > MAX_FLOAT_PRECISION) {
            // php-src notices "Requested precision of %d digits was truncated to PHP maximum of 53 digits"
            precision = MAX_FLOAT_PRECISION;
        }
        if (Double.isNaN(number)) {
            appendString(out, NAN, 0, 3, 3, 0, padding, alignLeft, false, false, alwaysSign);
            return;
        }
        if (Double.isInfinite(number)) {
            boolean negative = number < 0;
            byte[] text = negative ? MINUS_INF : INF;
            appendString(out, text, 0, text.length, text.length, 0, padding, alignLeft, negative, false,
                    alwaysSign);
            return;
        }
        String s;
        boolean negative;
        switch (fmt) {
            case 'e':
            case 'E':
            case 'f':
            case 'F': {
                // __cvt treats both zeros as positive; other values keep their sign even when they round to 0
                negative = number < 0;
                String digits = fmt == 'f' || fmt == 'F' ? fixed(Math.abs(number), precision)
                        : exponential(Math.abs(number), precision, fmt);
                s = negative ? "-" + digits : alwaysSign ? "+" + digits : digits;
                break;
            }
            default: {
                if (precision == 0) {
                    precision = 1;
                }
                char expChar = fmt == 'G' || fmt == 'H' ? 'E' : 'e';
                s = Convert.gcvt(number, precision, expChar);
                negative = s.charAt(0) == '-';
                if (!negative && alwaysSign) {
                    s = "+" + s;
                }
                break;
            }
        }
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        appendString(out, bytes, 0, bytes.length, width, 0, padding, alignLeft, negative, false, alwaysSign);
    }

    /** php_conv_fp's 'F' format of a non-negative finite value: dtoa mode 3 rounding, digits zero-padded. */
    private static String fixed(double abs, int precision) {
        if (abs == 0) {
            return precision == 0 ? "0" : "0." + "0".repeat(precision);
        }
        return new BigDecimal(abs).setScale(precision, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** php_conv_fp's 'e'/'E' format of a non-negative finite value: precision + 1 significant digits. */
    private static String exponential(double abs, int precision, char fmt) {
        StringBuilder out = new StringBuilder(precision + 8);
        int exponent;
        if (abs == 0) {
            out.append('0');
            if (precision > 0) {
                out.append('.').append("0".repeat(precision));
            }
            exponent = 0;
        } else {
            BigDecimal rounded = new BigDecimal(abs).round(new MathContext(precision + 1, RoundingMode.HALF_EVEN));
            String digits = rounded.unscaledValue().toString();
            exponent = rounded.precision() - rounded.scale() - 1;
            out.append(digits.charAt(0));
            if (precision > 0) {
                out.append('.').append(digits, 1, digits.length());
                for (int i = digits.length(); i <= precision; i++) {
                    out.append('0');
                }
            }
        }
        out.append(fmt).append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
        return out.toString();
    }
}
