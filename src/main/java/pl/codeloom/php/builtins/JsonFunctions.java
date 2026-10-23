package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;

/** json_encode/json_decode after ext/json: the same error codes, number rules and escaping. */
final class JsonFunctions {
    private JsonFunctions() {
    }

    private static final long HEX_TAG = 1;
    private static final long HEX_AMP = 2;
    private static final long HEX_APOS = 4;
    private static final long HEX_QUOT = 8;
    private static final long FORCE_OBJECT = 16;
    private static final long UNESCAPED_SLASHES = 64;
    private static final long PRETTY_PRINT = 128;
    private static final long UNESCAPED_UNICODE = 256;
    private static final long PARTIAL_OUTPUT_ON_ERROR = 512;
    private static final long PRESERVE_ZERO_FRACTION = 1024;
    private static final long UNESCAPED_LINE_TERMINATORS = 2048;
    private static final long OBJECT_AS_ARRAY = 1;
    private static final long BIGINT_AS_STRING = 2;
    private static final long INVALID_UTF8_IGNORE = 1048576;
    private static final long INVALID_UTF8_SUBSTITUTE = 2097152;
    private static final long THROW_ON_ERROR = 4194304;

    private static final int ERROR_DEPTH = 1;
    private static final int ERROR_CTRL_CHAR = 3;
    private static final int ERROR_SYNTAX = 4;
    private static final int ERROR_UTF8 = 5;
    private static final int ERROR_INF_OR_NAN = 7;
    private static final int ERROR_UNSUPPORTED_TYPE = 8;
    private static final int ERROR_UTF16 = 10;

    private static int lastError;

    static void register(Registry r) {
        // per-process state starts over in each context (BatchMain runs many contexts in one JVM)
        lastError = 0;
        PhpContext context = r.context();
        r.fn("json_decode", a -> {
            long flags = Args.lng(a, 3, 0);
            Object assocArg = Args.get(a, 1, PhpNull.NULL);
            boolean assoc = assocArg == PhpNull.NULL ? (flags & OBJECT_AS_ARRAY) != 0 : Convert.toBool(assocArg);
            return decode(context, Args.str(a, 0), assoc, (int) Args.lng(a, 2, 512), flags);
        });
        r.fn("json_encode", a -> encode(Args.get(a, 0), Args.lng(a, 1, 0), (int) Args.lng(a, 2, 512)));
        r.fn("json_last_error", a -> (long) lastError);
        r.fn("json_last_error_msg", a -> Str.of(message(lastError)));
    }

    private static String message(int code) {
        return switch (code) {
            case 0 -> "No error";
            case ERROR_DEPTH -> "Maximum stack depth exceeded";
            case 2 -> "State mismatch (invalid or malformed JSON)";
            case ERROR_CTRL_CHAR -> "Control character error, possibly incorrectly encoded";
            case ERROR_SYNTAX -> "Syntax error";
            case ERROR_UTF8 -> "Malformed UTF-8 characters, possibly incorrectly encoded";
            case ERROR_INF_OR_NAN -> "Inf and NaN cannot be JSON encoded";
            case ERROR_UNSUPPORTED_TYPE -> "Type is not supported";
            case ERROR_UTF16 -> "Single unpaired UTF-16 surrogate in unicode escape";
            default -> "Unknown error";
        };
    }

    private static Object fail(int code, long flags) {
        if ((flags & THROW_ON_ERROR) != 0) {
            throw new PhpError("JsonException: " + message(code));
        }
        lastError = code;
        return null;
    }

    // ---- decoding ----

    /** Thrown inside the parser with a json_last_error() code. */
    private static final class JsonError extends RuntimeException {
        final int code;

        JsonError(int code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    @TruffleBoundary
    private static Object decode(PhpContext context, TruffleString text, boolean assoc, int depth, long flags) {
        lastError = 0;
        byte[] input = Str.readBytes(text);
        if (input.length == 0) {
            Object ignored = fail(ERROR_SYNTAX, flags);
            return PhpNull.NULL;
        }
        Parser parser = new Parser(context, input, assoc, depth, flags);
        try {
            parser.skipSpace();
            Object value = parser.value();
            parser.skipSpace();
            if (parser.pos != input.length) {
                throw new JsonError(ERROR_SYNTAX);
            }
            return value;
        } catch (JsonError e) {
            Object ignored = fail(e.code, flags);
            return PhpNull.NULL;
        }
    }

    private static final class Parser {
        final PhpContext context;
        final byte[] s;
        final boolean assoc;
        final int maxDepth;
        final long flags;
        int pos;
        int depth;

        Parser(PhpContext context, byte[] s, boolean assoc, int maxDepth, long flags) {
            this.context = context;
            this.s = s;
            this.assoc = assoc;
            this.maxDepth = maxDepth;
            this.flags = flags;
        }

        void skipSpace() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) {
                pos++;
            }
        }

        Object value() {
            if (pos >= s.length) {
                throw new JsonError(ERROR_SYNTAX);
            }
            byte c = s[pos];
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", PhpNull.NULL);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw new JsonError(ERROR_SYNTAX);
                }
            };
        }

        Object literal(String word, Object value) {
            for (int i = 0; i < word.length(); i++) {
                if (pos + i >= s.length || s[pos + i] != word.charAt(i)) {
                    throw new JsonError(ERROR_SYNTAX);
                }
            }
            pos += word.length();
            return value;
        }

        void enter() {
            if (++depth > maxDepth) {
                throw new JsonError(ERROR_DEPTH);
            }
        }

        Object array() {
            enter();
            pos++;
            PhpArray result = new PhpArray();
            skipSpace();
            if (pos < s.length && s[pos] == ']') {
                pos++;
                depth--;
                return result;
            }
            while (true) {
                skipSpace();
                result.append(value());
                skipSpace();
                if (pos >= s.length) {
                    throw new JsonError(ERROR_SYNTAX);
                }
                if (s[pos] == ',') {
                    pos++;
                } else if (s[pos] == ']') {
                    pos++;
                    depth--;
                    return result;
                } else {
                    throw new JsonError(ERROR_SYNTAX);
                }
            }
        }

        Object object() {
            enter();
            pos++;
            PhpArray array = assoc ? new PhpArray() : null;
            PhpObject object = assoc ? null : context.getClass("stdClass").instantiate();
            skipSpace();
            if (pos < s.length && s[pos] == '}') {
                pos++;
                depth--;
                return assoc ? array : object;
            }
            while (true) {
                skipSpace();
                if (pos >= s.length || s[pos] != '"') {
                    throw new JsonError(ERROR_SYNTAX);
                }
                TruffleString key = string();
                skipSpace();
                if (pos >= s.length || s[pos] != ':') {
                    throw new JsonError(ERROR_SYNTAX);
                }
                pos++;
                skipSpace();
                Object value = value();
                if (assoc) {
                    array.put(ArrayKey.of(key), value);
                } else {
                    if (value instanceof PhpArray nested) {
                        nested.addHolder();
                    }
                    object.setProperty(Str.java(key).intern(), value);
                }
                skipSpace();
                if (pos >= s.length) {
                    throw new JsonError(ERROR_SYNTAX);
                }
                if (s[pos] == ',') {
                    pos++;
                } else if (s[pos] == '}') {
                    pos++;
                    depth--;
                    return assoc ? array : object;
                } else {
                    throw new JsonError(ERROR_SYNTAX);
                }
            }
        }

        Object number() {
            int start = pos;
            boolean isDouble = false;
            if (s[pos] == '-') {
                pos++;
            }
            if (pos >= s.length) {
                throw new JsonError(ERROR_SYNTAX);
            }
            if (s[pos] == '0') {
                pos++;
            } else if (s[pos] >= '1' && s[pos] <= '9') {
                while (pos < s.length && isDigit(s[pos])) {
                    pos++;
                }
            } else {
                throw new JsonError(ERROR_SYNTAX);
            }
            if (pos < s.length && s[pos] == '.') {
                isDouble = true;
                pos++;
                if (pos >= s.length || !isDigit(s[pos])) {
                    throw new JsonError(ERROR_SYNTAX);
                }
                while (pos < s.length && isDigit(s[pos])) {
                    pos++;
                }
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true;
                pos++;
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                    pos++;
                }
                if (pos >= s.length || !isDigit(s[pos])) {
                    throw new JsonError(ERROR_SYNTAX);
                }
                while (pos < s.length && isDigit(s[pos])) {
                    pos++;
                }
            }
            String text = new String(s, start, pos - start, StandardCharsets.ISO_8859_1);
            if (!isDouble) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException e) {
                    if ((flags & BIGINT_AS_STRING) != 0) {
                        return Str.of(text);
                    }
                }
            }
            return Double.parseDouble(text);
        }

        TruffleString string() {
            pos++;
            ByteBuilder out = null;
            int runStart = pos;
            while (true) {
                if (pos >= s.length) {
                    throw new JsonError(ERROR_SYNTAX);
                }
                int c = s[pos] & 0xFF;
                if (c == '"') {
                    TruffleString result;
                    if (out == null) {
                        result = Str.of(s, runStart, pos - runStart);
                    } else {
                        out.write(s, runStart, pos - runStart);
                        result = Str.of(out.toByteArray());
                    }
                    pos++;
                    return result;
                }
                if (c < 0x20) {
                    throw new JsonError(ERROR_CTRL_CHAR);
                }
                if (c >= 0x80) {
                    int length = Utf8.sequenceLength(s, pos);
                    if (length <= 0) {
                        throw new JsonError(ERROR_UTF8);
                    }
                    pos += length;
                    continue;
                }
                if (c != '\\') {
                    pos++;
                    continue;
                }
                if (out == null) {
                    out = new ByteBuilder();
                }
                out.write(s, runStart, pos - runStart);
                pos++;
                if (pos >= s.length) {
                    throw new JsonError(ERROR_SYNTAX);
                }
                byte e = s[pos++];
                switch (e) {
                    case '"', '\\', '/' -> out.write(e);
                    case 'b' -> out.write('\b');
                    case 'f' -> out.write('\f');
                    case 'n' -> out.write('\n');
                    case 'r' -> out.write('\r');
                    case 't' -> out.write('\t');
                    case 'u' -> {
                        int unit = hex4();
                        if (unit >= 0xD800 && unit <= 0xDBFF) {
                            if (pos + 1 < s.length && s[pos] == '\\' && s[pos + 1] == 'u') {
                                pos += 2;
                                int low = hex4();
                                if (low < 0xDC00 || low > 0xDFFF) {
                                    throw new JsonError(ERROR_UTF16);
                                }
                                unit = 0x10000 + ((unit - 0xD800) << 10) + (low - 0xDC00);
                            } else {
                                throw new JsonError(ERROR_UTF16);
                            }
                        } else if (unit >= 0xDC00 && unit <= 0xDFFF) {
                            throw new JsonError(ERROR_UTF16);
                        }
                        Utf8.encode(out, unit);
                    }
                    default -> throw new JsonError(ERROR_SYNTAX);
                }
                runStart = pos;
            }
        }

        int hex4() {
            if (pos + 4 > s.length) {
                throw new JsonError(ERROR_SYNTAX);
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(s[pos++], 16);
                if (digit < 0) {
                    throw new JsonError(ERROR_SYNTAX);
                }
                value = value * 16 + digit;
            }
            return value;
        }

        static boolean isDigit(byte b) {
            return b >= '0' && b <= '9';
        }
    }

    // ---- encoding ----

    @TruffleBoundary
    private static Object encode(Object value, long flags, int depth) {
        lastError = 0;
        Encoder encoder = new Encoder(flags, depth);
        try {
            encoder.value(value);
        } catch (JsonError e) {
            if ((flags & PARTIAL_OUTPUT_ON_ERROR) == 0) {
                Object ignored = fail(e.code, flags);
                return Boolean.FALSE;
            }
            lastError = e.code;
        }
        if (encoder.error != 0) {
            if ((flags & PARTIAL_OUTPUT_ON_ERROR) == 0) {
                Object ignored = fail(encoder.error, flags);
                return Boolean.FALSE;
            }
            lastError = encoder.error;
        }
        return Str.of(encoder.out.toByteArray());
    }

    private static final class Encoder {
        final ByteBuilder out = new ByteBuilder();
        final long flags;
        final int maxDepth;
        int depth;
        int error;

        Encoder(long flags, int maxDepth) {
            this.flags = flags;
            this.maxDepth = maxDepth;
        }

        void ascii(String text) {
            for (int i = 0; i < text.length(); i++) {
                out.write(text.charAt(i));
            }
        }

        void newline() {
            if ((flags & PRETTY_PRINT) != 0) {
                out.write('\n');
                for (int i = 0; i < depth; i++) {
                    ascii("    ");
                }
            }
        }

        void value(Object value) {
            value = PhpRef.deref(value);
            if (value == PhpNull.NULL || value == null) {
                ascii("null");
            } else if (value instanceof Boolean b) {
                ascii(b ? "true" : "false");
            } else if (value instanceof Long l) {
                ascii(Long.toString(l));
            } else if (value instanceof Double d) {
                number(d);
            } else if (value instanceof TruffleString s) {
                string(Str.readBytes(s));
            } else if (value instanceof PhpArray array) {
                array(array);
            } else if (value instanceof PhpObject object) {
                object(object);
            } else {
                error = ERROR_UNSUPPORTED_TYPE;
                ascii("null");
            }
        }

        void number(double d) {
            if (!Double.isFinite(d)) {
                error = ERROR_INF_OR_NAN;
                out.write('0');
                return;
            }
            String text = d == 0 ? (1 / d < 0 ? "-0" : "0") : Convert.doubleToRepr(d).replace('E', 'e');
            if (text.endsWith(".0") && text.indexOf('e') < 0) {
                text = text.substring(0, text.length() - 2);
            }
            ascii(text);
            if ((flags & PRESERVE_ZERO_FRACTION) != 0 && text.indexOf('.') < 0 && text.indexOf('e') < 0) {
                ascii(".0");
            }
        }

        void array(PhpArray array) {
            boolean list = (flags & FORCE_OBJECT) == 0;
            if (list) {
                long expected = 0;
                for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                    if (!(array.keyAt(p) instanceof Long k) || k != expected++) {
                        list = false;
                        break;
                    }
                }
            }
            if (array.size() == 0) {
                ascii(list ? "[]" : "{}");
                return;
            }
            if (++depth > maxDepth) {
                throw new JsonError(ERROR_DEPTH);
            }
            out.write(list ? '[' : '{');
            boolean first = true;
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                if (!first) {
                    out.write(',');
                }
                first = false;
                newline();
                if (!list) {
                    key(array.keyAt(p));
                }
                value(array.valueAt(p));
            }
            depth--;
            newline();
            out.write(list ? ']' : '}');
        }

        void key(Object key) {
            string(key instanceof Long l ? Long.toString(l).getBytes(StandardCharsets.ISO_8859_1)
                    : Str.readBytes((TruffleString) key));
            out.write(':');
            if ((flags & PRETTY_PRINT) != 0) {
                out.write(' ');
            }
        }

        void object(PhpObject object) {
            if (object.getPhpClass().isSubclassOf("jsonserializable")) {
                value(object.callMethod("jsonserialize"));
                return;
            }
            Object[] names = object.propertyNames();
            int visible = 0;
            for (Object name : names) {
                if (object.getPhpClass().isPublicProperty((String) name)) {
                    visible++;
                }
            }
            if (visible == 0) {
                ascii("{}");
                return;
            }
            if (++depth > maxDepth) {
                throw new JsonError(ERROR_DEPTH);
            }
            out.write('{');
            boolean first = true;
            for (Object name : names) {
                if (!object.getPhpClass().isPublicProperty((String) name)) {
                    continue;
                }
                if (!first) {
                    out.write(',');
                }
                first = false;
                newline();
                key(Str.of((String) name));
                value(object.getProperty((String) name));
            }
            depth--;
            newline();
            out.write('}');
        }

        void string(byte[] s) {
            int start = out.size();
            out.write('"');
            int i = 0;
            while (i < s.length) {
                int c = s[i] & 0xFF;
                if (c >= 0x80) {
                    int length = Utf8.sequenceLength(s, i);
                    if (length <= 0) {
                        if ((flags & INVALID_UTF8_IGNORE) != 0) {
                            i++;
                            continue;
                        }
                        if ((flags & INVALID_UTF8_SUBSTITUTE) != 0) {
                            if ((flags & UNESCAPED_UNICODE) != 0) {
                                out.writeBytes(new byte[]{(byte) 0xEF, (byte) 0xBF, (byte) 0xBD});
                            } else {
                                ascii("\\ufffd");
                            }
                            i++;
                            continue;
                        }
                        error = ERROR_UTF8;
                        byte[] partial = out.toByteArray();
                        out.reset();
                        out.write(partial, 0, start);
                        ascii("null");
                        return;
                    }
                    int cp = Utf8.decode(s, i, length);
                    boolean lineTerminator = cp == 0x2028 || cp == 0x2029;
                    if ((flags & UNESCAPED_UNICODE) != 0
                            && !(lineTerminator && (flags & UNESCAPED_LINE_TERMINATORS) == 0)) {
                        out.write(s, i, length);
                    } else if (cp >= 0x10000) {
                        int v = cp - 0x10000;
                        unicodeEscape(0xD800 | (v >> 10));
                        unicodeEscape(0xDC00 | (v & 0x3FF));
                    } else {
                        unicodeEscape(cp);
                    }
                    i += length;
                    continue;
                }
                switch (c) {
                    case '"' -> ascii((flags & HEX_QUOT) != 0 ? "\\u0022" : "\\\"");
                    case '\\' -> ascii("\\\\");
                    case '/' -> ascii((flags & UNESCAPED_SLASHES) != 0 ? "/" : "\\/");
                    case '\b' -> ascii("\\b");
                    case '\f' -> ascii("\\f");
                    case '\n' -> ascii("\\n");
                    case '\r' -> ascii("\\r");
                    case '\t' -> ascii("\\t");
                    case '<' -> ascii((flags & HEX_TAG) != 0 ? "\\u003C" : "<");
                    case '>' -> ascii((flags & HEX_TAG) != 0 ? "\\u003E" : ">");
                    case '&' -> ascii((flags & HEX_AMP) != 0 ? "\\u0026" : "&");
                    case '\'' -> ascii((flags & HEX_APOS) != 0 ? "\\u0027" : "'");
                    default -> {
                        if (c < 0x20) {
                            unicodeEscape(c);
                        } else {
                            out.write(c);
                        }
                    }
                }
                i++;
            }
            out.write('"');
        }

        void unicodeEscape(int unit) {
            ascii("\\u");
            String hex = Integer.toHexString(unit);
            ascii("0".repeat(4 - hex.length()));
            ascii(hex);
        }
    }

    /** Strict UTF-8 (no overlongs, surrogates or code points above U+10FFFF). */
    static final class Utf8 {
        private Utf8() {
        }

        /** The length of the well-formed sequence at i, or 0 if it is ill-formed. */
        static int sequenceLength(byte[] s, int i) {
            int c = s[i] & 0xFF;
            if (c < 0x80) {
                return 1;
            }
            int length;
            int min;
            if (c >= 0xC2 && c <= 0xDF) {
                length = 2;
                min = 0x80;
            } else if (c >= 0xE0 && c <= 0xEF) {
                length = 3;
                min = 0x800;
            } else if (c >= 0xF0 && c <= 0xF4) {
                length = 4;
                min = 0x10000;
            } else {
                return 0;
            }
            if (i + length > s.length) {
                return 0;
            }
            for (int k = 1; k < length; k++) {
                if ((s[i + k] & 0xC0) != 0x80) {
                    return 0;
                }
            }
            int cp = decode(s, i, length);
            if (cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
                return 0;
            }
            return length;
        }

        static int decode(byte[] s, int i, int length) {
            int c = s[i] & 0xFF;
            int cp = length == 1 ? c : length == 2 ? c & 0x1F : length == 3 ? c & 0x0F : c & 0x07;
            for (int k = 1; k < length; k++) {
                cp = (cp << 6) | (s[i + k] & 0x3F);
            }
            return cp;
        }

        static void encode(ByteBuilder out, int cp) {
            if (cp < 0x80) {
                out.write(cp);
            } else if (cp < 0x800) {
                out.write(0xC0 | (cp >> 6));
                out.write(0x80 | (cp & 0x3F));
            } else if (cp < 0x10000) {
                out.write(0xE0 | (cp >> 12));
                out.write(0x80 | ((cp >> 6) & 0x3F));
                out.write(0x80 | (cp & 0x3F));
            } else {
                out.write(0xF0 | (cp >> 18));
                out.write(0x80 | ((cp >> 12) & 0x3F));
                out.write(0x80 | ((cp >> 6) & 0x3F));
                out.write(0x80 | (cp & 0x3F));
            }
        }
    }
}
