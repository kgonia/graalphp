package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.Str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

/** sprintf()-family formatting, following php-src's ext/standard/formatted_print.c. */
final class Sprintf {
    private Sprintf() {
    }

    /** Formats with the format at argument formatIndex and the following arguments. */
    @TruffleBoundary
    static TruffleString format(Object[] a, int formatIndex) {
        Object[] values = new Object[Math.max(0, Args.count(a) - formatIndex - 1)];
        for (int i = 0; i < values.length; i++) {
            values[i] = Args.get(a, formatIndex + 1 + i);
        }
        return format(Args.str(a, formatIndex), values);
    }

    @TruffleBoundary
    static TruffleString formatArray(TruffleString format, PhpArray args) {
        Object[] values = new Object[args.size()];
        int i = 0;
        for (int p = args.firstPosition(); p >= 0; p = args.nextPosition(p)) {
            values[i++] = args.valueAt(p);
        }
        return format(format, values);
    }

    private static TruffleString format(TruffleString formatString, Object[] values) {
        byte[] f = Str.readBytes(formatString);
        ByteBuilder out = new ByteBuilder(f.length + 16);
        int next = 0;
        int i = 0;
        while (i < f.length) {
            byte c = f[i++];
            if (c != '%') {
                out.write(c);
                continue;
            }
            if (i < f.length && f[i] == '%') {
                out.write('%');
                i++;
                continue;
            }
            // argnum$
            int argIndex = -1;
            int save = i;
            int number = 0;
            while (i < f.length && f[i] >= '0' && f[i] <= '9') {
                number = number * 10 + (f[i++] - '0');
            }
            if (i < f.length && f[i] == '$' && i > save) {
                argIndex = number - 1;
                i++;
            } else {
                i = save;
            }
            boolean left = false;
            boolean plus = false;
            byte pad = ' ';
            while (i < f.length) {
                byte flag = f[i];
                if (flag == '-') {
                    left = true;
                } else if (flag == '+') {
                    plus = true;
                } else if (flag == '0') {
                    pad = '0';
                } else if (flag == ' ') {
                    pad = ' ';
                } else if (flag == '\'' && i + 1 < f.length) {
                    pad = f[++i];
                } else {
                    break;
                }
                i++;
            }
            int width = 0;
            while (i < f.length && f[i] >= '0' && f[i] <= '9') {
                width = width * 10 + (f[i++] - '0');
            }
            int precision = -1;
            if (i < f.length && f[i] == '.') {
                i++;
                precision = 0;
                while (i < f.length && f[i] >= '0' && f[i] <= '9') {
                    precision = precision * 10 + (f[i++] - '0');
                }
            }
            if (i >= f.length) {
                throw new PhpError("ValueError: Missing format specifier at end of string");
            }
            byte spec = f[i++];
            int index = argIndex >= 0 ? argIndex : next++;
            if (index >= values.length) {
                throw new PhpError("ArgumentCountError: " + (values.length + 1) + " arguments are required, "
                        + (index + 2) + " given");
            }
            Object value = values[index];
            String text;
            boolean numeric = false;
            switch (spec) {
                case 's': {
                    byte[] s = Str.readBytes(Convert.toStr(value));
                    if (precision >= 0 && precision < s.length) {
                        byte[] cut = new byte[precision];
                        System.arraycopy(s, 0, cut, 0, precision);
                        s = cut;
                    }
                    pad(out, s, width, pad, left, false);
                    continue;
                }
                case 'd': {
                    long v = Convert.toLong(value);
                    text = (plus && v >= 0 ? "+" : "") + v;
                    numeric = true;
                    break;
                }
                case 'u':
                    text = Long.toUnsignedString(Convert.toLong(value));
                    break;
                case 'x':
                    text = Long.toHexString(Convert.toLong(value));
                    break;
                case 'X':
                    text = Str.upper(Long.toHexString(Convert.toLong(value)));
                    break;
                case 'o':
                    text = Long.toOctalString(Convert.toLong(value));
                    break;
                case 'b':
                    text = Long.toBinaryString(Convert.toLong(value));
                    break;
                case 'c':
                    out.write((int) Convert.toLong(value));
                    continue;
                case 'f':
                case 'F': {
                    double v = Convert.toDouble(value);
                    text = fixed(v, precision < 0 ? 6 : precision, plus);
                    numeric = true;
                    break;
                }
                case 'e':
                case 'E': {
                    double v = Convert.toDouble(value);
                    text = scientific(v, precision < 0 ? 6 : precision, plus, spec == 'E');
                    numeric = true;
                    break;
                }
                case 'g':
                case 'G': {
                    double v = Convert.toDouble(value);
                    text = Convert.doubleToString(v);
                    if (spec == 'G') {
                        text = Str.upper(text);
                    }
                    numeric = true;
                    break;
                }
                default:
                    throw new PhpError("ValueError: Unknown format specifier \"" + (char) spec + "\"");
            }
            pad(out, text.getBytes(StandardCharsets.ISO_8859_1), width, pad, left, numeric);
        }
        return Str.of(out.toByteArray());
    }

    /** Pads to width; zero padding of numbers goes after the sign, as php_sprintf_appendstring does. */
    private static void pad(ByteBuilder out, byte[] text, int width, byte pad, boolean left,
                            boolean numeric) {
        int missing = width - text.length;
        if (missing <= 0) {
            out.write(text, 0, text.length);
            return;
        }
        if (left) {
            out.write(text, 0, text.length);
            for (int i = 0; i < missing; i++) {
                out.write(pad == '0' && !numeric ? '0' : pad == '0' ? ' ' : pad);
            }
            return;
        }
        int start = 0;
        if (numeric && pad == '0' && text.length > 0 && (text[0] == '-' || text[0] == '+')) {
            out.write(text[0]);
            start = 1;
        }
        for (int i = 0; i < missing; i++) {
            out.write(pad);
        }
        out.write(text, start, text.length - start);
    }

    private static String fixed(double v, int precision, boolean plus) {
        if (!Double.isFinite(v)) {
            return Double.isNaN(v) ? "NaN" : v > 0 ? "Inf" : "-Inf";
        }
        String text = new BigDecimal(v).setScale(precision, RoundingMode.HALF_EVEN).toPlainString();
        return plus && v >= 0 ? "+" + text : text;
    }

    private static String scientific(double v, int precision, boolean plus, boolean upper) {
        if (v == 0) {
            return (plus ? "+" : "") + "0." + "0".repeat(precision) + (upper ? "E" : "e") + "+0";
        }
        String formatted = String.format("%." + precision + "e", v);
        int e = formatted.indexOf('e');
        String mantissa = formatted.substring(0, e);
        int exponent = Integer.parseInt(formatted.substring(e + 1));
        String text = mantissa + (upper ? "E" : "e") + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
        return plus && v >= 0 ? "+" + text : text;
    }
}
