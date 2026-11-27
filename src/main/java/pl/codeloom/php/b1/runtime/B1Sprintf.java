package pl.codeloom.php.b1.runtime;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** php_formatted_print (ext/standard/formatted_print.c) for the b, c, d, u, o, x, X, s, f and F conversions. */
final class B1Sprintf {
    private B1Sprintf() {}
    private static final int MAX_FLOAT_PRECISION = 53;

    static byte[] format(B1RequestContext ctx, byte[] format) { return format(ctx,format,0,null,null); }
    static byte[] format(B1RequestContext ctx, byte[] format, Object arg) { return format(ctx,format,1,arg,null); }
    static byte[] format(B1RequestContext ctx, byte[] format, Object[] args) { return format(ctx,format,args.length,null,args); }

    /** The arguments are {@code single} when there is one, else {@code many}: the arity-specialized entries allocate no array. */
    private static byte[] format(B1RequestContext ctx, byte[] format, int count, Object single, Object[] many) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(format.length + 16);
        int next = 0, max = 0;
        for (int i = 0; i < format.length; i++) {
            byte c = format[i];
            if (c != '%') { out.write(c); continue; }
            if (++i == format.length) throw ctx.error("ValueError","Missing format specifier at end of string");
            if (format[i] == '%') { out.write('%'); continue; }
            int argnum = -1;
            int end = digitsEnd(format,i);
            if (end > i && end < format.length && format[end] == '$') {
                argnum = number(format,i,end);
                if (argnum <= 0) throw ctx.error("ValueError","Argument number specifier must be greater than zero and less than "
                        + Integer.MAX_VALUE);
                argnum--;
                i = end + 1;
            }
            boolean left = false, plus = false;
            byte pad = ' ';
            for (;; i++) {
                if (i == format.length) throw ctx.error("ValueError","Missing format specifier at end of string");
                byte f = format[i];
                if (f == '-') left = true;
                else if (f == '+') plus = true;
                else if (f == '0') pad = '0';
                else if (f == ' ') pad = ' ';
                else if (f == '\'') {
                    if (++i == format.length) throw ctx.error("ValueError","Missing padding character");
                    pad = format[i];
                } else break;
            }
            if (format[i] == '*') throw ctx.unsupported("sprintf widths from arguments");
            int width = 0;
            if (format[i] >= '0' && format[i] <= '9') {
                end = digitsEnd(format,i);
                width = number(format,i,end);
                if (width < 0) throw ctx.error("ValueError","Width must be between 0 and " + Integer.MAX_VALUE);
                i = end;
                if (i == format.length) throw ctx.error("ValueError","Missing format specifier at end of string");
            }
            // A '.' without digits sets precision 0 for floats but does not truncate strings (expprec).
            int precision = -1;
            boolean expprec = false;
            if (format[i] == '.') {
                precision = 0;
                if (++i == format.length) throw ctx.error("ValueError","Missing format specifier at end of string");
                if (format[i] == '*') throw ctx.unsupported("sprintf precisions from arguments");
                if (format[i] >= '0' && format[i] <= '9') {
                    end = digitsEnd(format,i);
                    precision = number(format,i,end);
                    if (precision < 0) throw ctx.error("ValueError","Precision must be between 0 and " + Integer.MAX_VALUE);
                    expprec = true;
                    i = end;
                    if (i == format.length) throw ctx.error("ValueError","Missing format specifier at end of string");
                }
            }
            if (format[i] == 'l') {
                if (++i == format.length) throw ctx.error("ValueError","Missing format specifier at end of string");
            }
            byte spec = format[i];
            int index = argnum >= 0 ? argnum : next++;
            max = Math.max(max,index + 1);
            if (index >= count) continue; // reported below with PHP's total count
            Object arg = many == null ? single : many[index];
            switch (spec) {
                case 's' -> {
                    byte[] text = ctx.stringBytes(arg);
                    append(out,text,width,precision,pad,left,false,false,expprec);
                }
                case 'd' -> {
                    long n = (long)ctx.castLong(arg);
                    byte[] digits = Long.toString(n).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    if (n >= 0 && plus) {
                        byte[] signed = new byte[digits.length + 1];
                        signed[0] = '+';
                        System.arraycopy(digits,0,signed,1,digits.length);
                        digits = signed;
                    }
                    append(out,digits,width,0,pad,left,n < 0,plus,false);
                }
                case 'u' -> append(out,Long.toUnsignedString((long)ctx.castLong(arg)).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        width,0,pad,left,false,false,false);
                case 'x', 'X', 'o', 'b' -> {
                    long n = (long)ctx.castLong(arg);
                    String digits = spec == 'o' ? Long.toOctalString(n) : spec == 'b' ? Long.toBinaryString(n) : Long.toHexString(n);
                    if (spec == 'X') digits = digits.toUpperCase(java.util.Locale.ROOT);
                    append(out,digits.getBytes(java.nio.charset.StandardCharsets.US_ASCII),width,0,pad,left,false,false,false);
                }
                case 'c' -> out.write((byte)(long)ctx.castLong(arg)); // php_sprintf_appendchar: no width or padding
                case 'f', 'F' -> {
                    if (precision > MAX_FLOAT_PRECISION) {
                        ctx.diagnostic("Notice","Requested precision of " + precision + " digits was truncated to PHP maximum of "
                                + MAX_FLOAT_PRECISION + " digits");
                        precision = MAX_FLOAT_PRECISION;
                    }
                    double d = toDouble(ctx,arg);
                    if (Double.isNaN(d)) { append(out,ascii("NaN"),width,0,pad,left,false,plus,false); continue; }
                    if (Double.isInfinite(d)) {
                        byte[] text = ascii(d < 0 ? "-Inf" : plus ? "+Inf" : "Inf");
                        append(out,text,width,0,pad,left,d < 0,plus,false);
                        continue;
                    }
                    // php_conv_fp: dtoa mode 3 on |d|, the sign of a negative value (not -0.0) in front.
                    boolean negative = d < 0;
                    String digits = new BigDecimal(Math.abs(d)).setScale(precision < 0 ? 6 : precision, RoundingMode.HALF_EVEN).toPlainString();
                    String text = (negative ? "-" : plus ? "+" : "") + digits;
                    append(out,ascii(text),width,0,pad,left,negative,plus,false);
                }
                case 'e', 'E', 'g', 'G', 'h', 'H' -> throw ctx.unsupported("sprintf %" + (char)spec);
                default -> throw ctx.error("ValueError","Unknown format specifier \"" + (char)(spec & 0xff) + "\"");
            }
        }
        if (max > count) throw ctx.error("ArgumentCountError",(max + 1) + " arguments are required, " + (count + 1) + " given");
        return out.toByteArray();
    }

    /** php_sprintf_appendstring: a zero pad goes after the sign of a right-aligned number. */
    private static void append(ByteArrayOutputStream out, byte[] text, int width, int precision, byte pad, boolean left,
                               boolean negative, boolean alwaysSign, boolean truncate) {
        int copy = truncate ? Math.min(precision,text.length) : text.length;
        int npad = width < copy ? 0 : width - copy;
        int from = 0;
        if (!left) {
            if ((negative || alwaysSign) && pad == '0' && copy > 0 && (text[0] == '-' || text[0] == '+')) {
                out.write(text[0]);
                from = 1;
            }
            for (int i = 0; i < npad; i++) out.write(pad);
        }
        out.write(text,from,copy - from);
        if (left) for (int i = 0; i < npad; i++) out.write(pad);
    }

    private static byte[] ascii(String text) { return text.getBytes(java.nio.charset.StandardCharsets.US_ASCII); }

    private static int digitsEnd(byte[] format, int i) {
        while (i < format.length && format[i] >= '0' && format[i] <= '9') i++;
        return i;
    }
    /** php_sprintf_getnumber: ZEND_STRTOL saturates, and INT_MAX or more is -1. */
    private static int number(byte[] format, int from, int to) {
        long n = 0;
        for (int i = from; i < to; i++) {
            n = n * 10 + (format[i] - '0');
            if (n >= Integer.MAX_VALUE) return -1;
        }
        return (int)n;
    }

    /** zval_get_double: leading-numeric strings; an object warns and is 1.0. */
    private static double toDouble(B1RequestContext ctx, Object value) {
        if (value instanceof Double d) return d;
        if (value instanceof Long n) return n;
        if (value instanceof Boolean flag) return flag ? 1 : 0;
        if (value == null) return 0;
        if (value instanceof B1Array array) return array.size() == 0 ? 0 : 1;
        if (value instanceof B1Bytes || value instanceof String) {
            Number n = pl.codeloom.php.ir.PhpNumericString.parse(B1Bytes.bytes(value),true);
            return n == null ? 0 : n.doubleValue();
        }
        ctx.diagnostic("Warning","Object of class " + ctx.typeName(value) + " could not be converted to float");
        return 1;
    }
}
