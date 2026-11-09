package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpGenerator;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** json_encode/json_decode after ext/json: the same error codes, number rules and escaping. */
final class JsonFunctions {
    private JsonFunctions() {
    }

    private static final long HEX_TAG = 1;
    private static final long HEX_AMP = 2;
    private static final long HEX_APOS = 4;
    private static final long HEX_QUOT = 8;
    private static final long FORCE_OBJECT = 16;
    private static final long NUMERIC_CHECK = 32;
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
    private static final int ERROR_STATE_MISMATCH = 2;
    private static final int ERROR_CTRL_CHAR = 3;
    private static final int ERROR_SYNTAX = 4;
    private static final int ERROR_UTF8 = 5;
    private static final int ERROR_RECURSION = 6;
    private static final int ERROR_INF_OR_NAN = 7;
    private static final int ERROR_UNSUPPORTED_TYPE = 8;
    private static final int ERROR_INVALID_PROPERTY_NAME = 9;
    private static final int ERROR_UTF16 = 10;
    private static final int ERROR_NON_BACKED_ENUM = 11;

    /** Per-context state: json_last_error(). */
    static final class State {
        int lastError;
        /**
         * The objects whose jsonSerialize() runs (their JSON recursion guard): a json_encode() that jsonSerialize()
         * calls sees them too. The arrays and objects being encoded carry the context's RecursionGuard marks.
         */
        final ArrayList<Object> serializing = new ArrayList<>();
    }

    private static State state(PhpContext context) {
        return context.builtinState().json;
    }

    static void register(Registry r) {
        r.fn("json_decode", (context, a) -> {
            return decode(context, state(context), Args.str(a, 0), Args.get(a, 1, PhpNull.NULL), Args.lng(a, 2, 512),
                    Args.lng(a, 3, 0));
        });
        r.fn("json_encode", (context, a) -> encode(state(context), context.builtinState().recursion, Args.get(a, 0), Args.lng(a, 1, 0), Args.lng(a, 2, 512)));
        r.fn("json_last_error", (context, a) -> {
            Args.arity(a, "json_last_error", 0, 0);
            return (long) state(context).lastError;
        });
        r.fn("json_last_error_msg", (context, a) -> {
            Args.arity(a, "json_last_error_msg", 0, 0);
            return Str.of(message(state(context).lastError));
        });
    }

    private static String message(int code) {
        return switch (code) {
            case 0 -> "No error";
            case ERROR_DEPTH -> "Maximum stack depth exceeded";
            case ERROR_STATE_MISMATCH -> "State mismatch (invalid or malformed JSON)";
            case ERROR_CTRL_CHAR -> "Control character error, possibly incorrectly encoded";
            case ERROR_SYNTAX -> "Syntax error";
            case ERROR_UTF8 -> "Malformed UTF-8 characters, possibly incorrectly encoded";
            case ERROR_RECURSION -> "Recursion detected";
            case ERROR_INF_OR_NAN -> "Inf and NaN cannot be JSON encoded";
            case ERROR_UNSUPPORTED_TYPE -> "Type is not supported";
            case ERROR_INVALID_PROPERTY_NAME -> "The decoded property name is invalid";
            case ERROR_UTF16 -> "Single unpaired UTF-16 surrogate in unicode escape";
            case ERROR_NON_BACKED_ENUM -> "Non-backed enums have no default serialization";
            default -> "Unknown error";
        };
    }

    private static Object fail(State state, int code, long flags) {
        if ((flags & THROW_ON_ERROR) != 0) {
            throw new PhpError("JsonException", message(code), code);
        }
        state.lastError = code;
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

    /** PHP_FUNCTION(json_decode) and php_json_decode_ex. */
    @TruffleBoundary
    private static Object decode(PhpContext context, State state, TruffleString text, Object assocArg, long depth,
                                 long flags) {
        if ((flags & THROW_ON_ERROR) == 0) {
            state.lastError = 0;
        }
        byte[] input = Str.readBytes(text);
        if (input.length == 0) {
            Object ignored = fail(state, ERROR_SYNTAX, flags);
            return PhpNull.NULL;
        }
        if (depth <= 0) {
            throw new PhpError("ValueError", "json_decode(): Argument #3 ($depth) must be greater than 0");
        }
        if (depth > Integer.MAX_VALUE) {
            throw new PhpError("ValueError", "json_decode(): Argument #3 ($depth) must be less than 2147483647");
        }
        if (assocArg != PhpNull.NULL) {
            flags = Convert.toBool(assocArg) ? flags | OBJECT_AS_ARRAY : flags & ~OBJECT_AS_ARRAY;
        }
        Parser parser = new Parser(context, input, (int) depth, flags);
        try {
            Object value = parser.value(parser.next());
            if (parser.next() != Parser.EOI) {
                throw new JsonError(ERROR_SYNTAX);
            }
            return value;
        } catch (JsonError e) {
            Object ignored = fail(state, e.code, flags);
            return PhpNull.NULL;
        }
    }

    /**
     * json_scanner.re and json_parser.y: the scanner's tokens, parsed by the grammar's rules. A scanner error
     * throws its code; the parser's own errors are a syntax error, or a state mismatch where a closing bracket
     * doesn't match.
     */
    private static final class Parser {
        static final int EOI = 0;
        static final int LBRACE = 1;
        static final int RBRACE = 2;
        static final int LBRACKET = 3;
        static final int RBRACKET = 4;
        static final int COLON = 5;
        static final int COMMA = 6;
        static final int SCALAR = 7;
        static final int STRING = 8;

        final PhpContext context;
        final byte[] s;
        final boolean assoc;
        final int maxDepth;
        final long flags;
        int pos;
        int depth;
        /** The value of the last SCALAR or STRING token. */
        Object token;

        Parser(PhpContext context, byte[] s, int maxDepth, long flags) {
            this.context = context;
            this.s = s;
            this.assoc = (flags & OBJECT_AS_ARRAY) != 0;
            this.maxDepth = maxDepth;
            this.flags = flags;
        }

        int next() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) {
                pos++;
            }
            if (pos >= s.length) {
                return EOI;
            }
            int c = s[pos] & 0xFF;
            switch (c) {
                case '{':
                    pos++;
                    return LBRACE;
                case '}':
                    pos++;
                    return RBRACE;
                case '[':
                    pos++;
                    return LBRACKET;
                case ']':
                    pos++;
                    return RBRACKET;
                case ':':
                    pos++;
                    return COLON;
                case ',':
                    pos++;
                    return COMMA;
                case '"':
                    token = string();
                    return STRING;
                case 't':
                    if (literal("true")) {
                        token = Boolean.TRUE;
                        return SCALAR;
                    }
                    break;
                case 'f':
                    if (literal("false")) {
                        token = Boolean.FALSE;
                        return SCALAR;
                    }
                    break;
                case 'n':
                    if (literal("null")) {
                        token = PhpNull.NULL;
                        return SCALAR;
                    }
                    break;
                case 0:
                    throw new JsonError(ERROR_CTRL_CHAR);
                default:
                    if (isDigit(c) || c == '-' && pos + 1 < s.length && isDigit(s[pos + 1])) {
                        return number();
                    }
            }
            // <JS>UTF8 is a syntax error, <JS>ANY (a byte that starts no UTF-8 character) a UTF-8 one
            throw new JsonError(Utf8.sequenceLength(s, pos) > 0 ? ERROR_SYNTAX : ERROR_UTF8);
        }

        boolean literal(String word) {
            if (pos + word.length() > s.length) {
                return false;
            }
            for (int i = 0; i < word.length(); i++) {
                if (s[pos + i] != word.charAt(i)) {
                    return false;
                }
            }
            pos += word.length();
            return true;
        }

        /** value: object | array | a scalar token. */
        Object value(int t) {
            return switch (t) {
                case LBRACE -> object();
                case LBRACKET -> array();
                case SCALAR, STRING -> token;
                default -> throw new JsonError(ERROR_SYNTAX);
            };
        }

        /** PHP_JSON_DEPTH_INC: the depth counts the scalar inside too, so nesting may reach depth - 1. */
        void enter() {
            if (++depth >= maxDepth) {
                throw new JsonError(ERROR_DEPTH);
            }
        }

        /** array_end and object_end: the other closing bracket is a state mismatch. */
        void end(int t, int expected) {
            if (t != expected) {
                throw new JsonError(t == RBRACE || t == RBRACKET ? ERROR_STATE_MISMATCH : ERROR_SYNTAX);
            }
            depth--;
        }

        Object array() {
            enter();
            PhpArray result = new PhpArray();
            int t = next();
            if (t != RBRACKET && t != RBRACE) {
                while (true) {
                    result.append(value(t));
                    t = next();
                    if (t != COMMA) {
                        break;
                    }
                    t = next();
                }
            }
            end(t, RBRACKET);
            return result;
        }

        /** The object is made when its first member is (member: key ':' value), or at its end when it has none. */
        Object object() {
            enter();
            Object result = null;
            int t = next();
            if (t != RBRACE && t != RBRACKET) {
                while (true) {
                    if (t != STRING) {
                        throw new JsonError(ERROR_SYNTAX);
                    }
                    TruffleString key = (TruffleString) token;
                    if (next() != COLON) {
                        throw new JsonError(ERROR_SYNTAX);
                    }
                    Object value = value(next());
                    if (result == null) {
                        result = create();
                    }
                    if (assoc) {
                        ((PhpArray) result).put(ArrayKey.of(key), value);
                    } else {
                        if (Str.length(key) > 0 && Str.readBytes(key)[0] == 0) {
                            throw new JsonError(ERROR_INVALID_PROPERTY_NAME);
                        }
                        if (value instanceof PhpArray nested) {
                            nested.addHolder();
                        }
                        ((PhpObject) result).setProperty(Str.key(key), value);
                    }
                    t = next();
                    if (t != COMMA) {
                        break;
                    }
                    t = next();
                }
            }
            if (result == null) {
                result = create();
            }
            end(t, RBRACE);
            return result;
        }

        Object create() {
            return assoc ? new PhpArray() : context.getClass("stdClass").instantiate();
        }

        /** "-"? INT ("." DIGIT+)? ([eE] [+-]? DIGIT+)?, where INT is 0 or has no leading zero. */
        int number() {
            int start = pos;
            boolean negative = s[pos] == '-';
            if (negative) {
                pos++;
            }
            if (s[pos++] != '0') {
                while (pos < s.length && isDigit(s[pos])) {
                    pos++;
                }
            }
            int intEnd = pos;
            if (pos + 1 < s.length && s[pos] == '.' && isDigit(s[pos + 1])) {
                pos += 2;
                while (pos < s.length && isDigit(s[pos])) {
                    pos++;
                }
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                int e = pos + 1;
                if (e < s.length && (s[e] == '+' || s[e] == '-')) {
                    e++;
                }
                if (e < s.length && isDigit(s[e])) {
                    pos = e;
                    while (pos < s.length && isDigit(s[pos])) {
                        pos++;
                    }
                }
            }
            String text = new String(s, start, pos - start, StandardCharsets.ISO_8859_1);
            if (pos == intEnd) {
                // PHP_JSON_INT_MAX_LENGTH digits are compared with LONG_MIN_DIGITS
                int digits = pos - start - (negative ? 1 : 0);
                int cmp = digits == 19 ? text.substring(negative ? 1 : 0).compareTo("9223372036854775808") : 0;
                if (digits < 19 || digits == 19 && (cmp < 0 || cmp == 0 && negative)) {
                    token = Long.parseLong(text);
                    return SCALAR;
                }
                if ((flags & BIGINT_AS_STRING) != 0) {
                    token = Str.of(text);
                    return STRING;
                }
            }
            token = Double.parseDouble(text);
            return SCALAR;
        }

        TruffleString string() {
            pos++;
            ByteBuilder out = null;
            int runStart = pos;
            while (true) {
                if (pos >= s.length) {
                    // the NUL after the input is a control character
                    throw new JsonError(ERROR_CTRL_CHAR);
                }
                int c = s[pos] & 0xFF;
                if (c == '"') {
                    TruffleString result;
                    if (out == null) {
                        result = Str.of(s, runStart, pos - runStart);
                    } else {
                        out.write(s, runStart, pos - runStart);
                        result = out.toStr();
                    }
                    pos++;
                    return result;
                }
                if (c < 0x20) {
                    throw new JsonError(ERROR_CTRL_CHAR);
                }
                if (c >= 0x80) {
                    int length = Utf8.sequenceLength(s, pos);
                    if (length > 0) {
                        pos += length;
                        continue;
                    }
                    if ((flags & (INVALID_UTF8_IGNORE | INVALID_UTF8_SUBSTITUTE)) == 0) {
                        throw new JsonError(ERROR_UTF8);
                    }
                    // one byte at a time, as the scanner's ANY
                    if (out == null) {
                        out = new ByteBuilder();
                    }
                    out.write(s, runStart, pos - runStart);
                    if ((flags & INVALID_UTF8_SUBSTITUTE) != 0) {
                        out.write(new byte[]{(byte) 0xEF, (byte) 0xBF, (byte) 0xBD});
                    }
                    runStart = ++pos;
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
                int e = pos + 1 < s.length ? s[pos + 1] : 0;
                switch (e) {
                    case '"', '\\', '/' -> out.write(e);
                    case 'b' -> out.write('\b');
                    case 'f' -> out.write('\f');
                    case 'n' -> out.write('\n');
                    case 'r' -> out.write('\r');
                    case 't' -> out.write('\t');
                    case 'u' -> {
                        int unit = hex4(pos + 2);
                        if (unit < 0) {
                            throw new JsonError(ERROR_SYNTAX);
                        }
                        if (unit >= 0xD800 && unit <= 0xDBFF) {
                            // UTF16_4, else a lone surrogate (UCS2)
                            int low = pos + 7 < s.length && s[pos + 6] == '\\' && s[pos + 7] == 'u'
                                    ? hex4(pos + 8) : -1;
                            if (low < 0xDC00 || low > 0xDFFF) {
                                throw new JsonError(ERROR_UTF16);
                            }
                            unit = 0x10000 + ((unit - 0xD800) << 10) + (low - 0xDC00);
                            pos += 6;
                        } else if (unit >= 0xDC00 && unit <= 0xDFFF) {
                            throw new JsonError(ERROR_UTF16);
                        }
                        Utf8.encode(out, unit);
                        pos += 4;
                    }
                    default -> throw new JsonError(ERROR_SYNTAX);
                }
                pos += 2;
                runStart = pos;
            }
        }

        /** The four hex digits at i, or -1. */
        int hex4(int i) {
            if (i + 4 > s.length) {
                return -1;
            }
            int value = 0;
            for (int k = i; k < i + 4; k++) {
                int digit = Character.digit(s[k], 16);
                if (digit < 0) {
                    return -1;
                }
                value = value * 16 + digit;
            }
            return value;
        }

        static boolean isDigit(int b) {
            return b >= '0' && b <= '9';
        }
    }

    // ---- encoding ----

    /** PHP_FUNCTION(json_encode). */
    @TruffleBoundary
    private static Object encode(State state, RecursionGuard guard, Object value, long flags, long depth) {
        Encoder encoder = new Encoder(state, guard, flags, (int) depth);
        boolean partial = (flags & PARTIAL_OUTPUT_ON_ERROR) != 0;
        boolean record = (flags & THROW_ON_ERROR) == 0 || partial;
        int encoding = guard.depth();
        int serializing = state.serializing.size();
        try {
            encoder.value(value);
        } finally {
            if (record) {
                state.lastError = encoder.error;
            }
            // what an exception left protected
            guard.unwind(encoding);
            state.serializing.subList(serializing, state.serializing.size()).clear();
        }
        if (encoder.error != 0 && !partial) {
            if (!record) {
                throw new PhpError("JsonException", message(encoder.error), encoder.error);
            }
            return Boolean.FALSE;
        }
        return encoder.out.toStr();
    }

    /** json_encoder.c: each encode function's false is its FAILURE, which stops the encoding unless partial. */
    private static final class Encoder {
        final ByteBuilder out = new ByteBuilder();
        final long flags;
        final boolean partial;
        final int maxDepth;
        int depth;
        int error;
        /** The arrays (one mark every walk shares) and objects (JSON's mark) being encoded. */
        final RecursionGuard guard;
        final ArrayList<Object> serializing;

        Encoder(State state, RecursionGuard guard, long flags, int maxDepth) {
            this.guard = guard;
            this.serializing = state.serializing;
            this.flags = flags;
            this.partial = (flags & PARTIAL_OUTPUT_ON_ERROR) != 0;
            this.maxDepth = maxDepth;
        }

        void ascii(String text) {
            out.writeAscii(text);
        }

        void newline() {
            if ((flags & PRETTY_PRINT) != 0) {
                out.write('\n');
                for (int i = 0; i < depth; i++) {
                    ascii("    ");
                }
            }
        }

        boolean value(Object value) {
            value = PhpRef.deref(value);
            if (value == PhpNull.NULL || value == null) {
                ascii("null");
            } else if (value instanceof Boolean b) {
                ascii(b ? "true" : "false");
            } else if (value instanceof Long l) {
                out.writeLong(l);
            } else if (value instanceof Double d) {
                if (Double.isFinite(d)) {
                    number(d);
                } else {
                    error = ERROR_INF_OR_NAN;
                    out.write('0');
                }
            } else if (value instanceof TruffleString s) {
                return string(Str.readBytes(s), flags);
            } else if (value instanceof PhpArray array) {
                return container(array, array, null);
            } else if (value instanceof PhpObject object) {
                if (object.getPhpClass().isSubclassOf("jsonserializable")) {
                    return serializable(object);
                }
                if (object.getPhpClass().isEnum()) {
                    // php_json_encode_serializable_enum
                    if (object.getPhpClass().backingType() == null) {
                        error = ERROR_NON_BACKED_ENUM;
                        out.write('0');
                        return false;
                    }
                    return value(object.getProperty("value"));
                }
                return container(object, null, object);
            } else if (value instanceof PhpClosure || value instanceof PhpGenerator) {
                return container(value, null, null); // an object without properties
            } else {
                error = ERROR_UNSUPPORTED_TYPE;
                if (partial) {
                    ascii("null");
                }
                return false;
            }
            return true;
        }

        void number(double d) {
            String text = d == 0 ? (1 / d < 0 ? "-0" : "0") : Convert.doubleToRepr(d).replace('E', 'e');
            if (text.endsWith(".0") && text.indexOf('e') < 0) {
                text = text.substring(0, text.length() - 2);
            }
            ascii(text);
            if ((flags & PRESERVE_ZERO_FRACTION) != 0 && text.indexOf('.') < 0 && text.indexOf('e') < 0) {
                ascii(".0");
            }
        }

        /** php_json_encode_serializable_object. */
        boolean serializable(PhpObject object) {
            if (contains(serializing, object)) {
                error = ERROR_RECURSION;
                if (partial) {
                    ascii("null");
                }
                return false;
            }
            serializing.add(object);
            Object result = PhpRef.deref(object.callMethod("jsonserialize"));
            if (result == object) {
                // return $this: straight to the properties
                serializing.remove(serializing.size() - 1);
                return container(object, null, object);
            }
            boolean ok = value(result);
            serializing.remove(serializing.size() - 1);
            return ok;
        }

        static boolean contains(ArrayList<Object> list, Object value) {
            for (Object o : list) {
                if (o == value) {
                    return true;
                }
            }
            return false;
        }

        /** php_json_encode_array: an array, or an object's public properties (both null: no properties). */
        boolean container(Object holder, PhpArray array, PhpObject object) {
            boolean list = array != null && (flags & FORCE_OBJECT) == 0 && isList(array);
            if (!guard.enter(holder, holder instanceof PhpArray ? RecursionGuard.ARRAY : RecursionGuard.JSON)) {
                error = ERROR_RECURSION;
                ascii("null");
                return false;
            }
            out.write(list ? '[' : '{');
            ++depth;
            boolean needComma = false;
            if (array != null) {
                for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                    needComma = comma(needComma);
                    if (!list) {
                        Object key = array.keyAt(p);
                        if (key instanceof Long l) {
                            out.write('"');
                            out.writeLong(l);
                            out.write('"');
                            colon();
                        } else {
                            key(Str.readBytes((TruffleString) key));
                        }
                    }
                    if (!value(array.valueAt(p)) && !partial) {
                        guard.leave();
                        return false;
                    }
                }
            } else if (object != null) {
                // a lazy object initialized; a class with hooks has its get hooks called (readableProperties)
                PhpObject real = object.initialized();
                Object[] table = real.getPhpClass().hasHooks() ? real.readableProperties(true).toArray()
                        : real.propertyTable();
                for (int i = 0; i < table.length; i += 2) {
                    String name = (String) table[i];
                    if (name.startsWith("\0")) {
                        continue; // protected and private
                    }
                    needComma = comma(needComma);
                    key(name.getBytes(StandardCharsets.ISO_8859_1));
                    if (!value(table[i + 1]) && !partial) {
                        guard.leave();
                        return false;
                    }
                }
            }
            guard.leave();
            if (depth > maxDepth) {
                error = ERROR_DEPTH;
                if (!partial) {
                    return false;
                }
            }
            --depth;
            if (needComma) {
                newline();
            }
            out.write(list ? ']' : '}');
            return true;
        }

        static boolean isList(PhpArray array) {
            long expected = 0;
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                if (!(array.keyAt(p) instanceof Long k) || k != expected++) {
                    return false;
                }
            }
            return true;
        }

        boolean comma(boolean needComma) {
            if (needComma) {
                out.write(',');
            }
            newline();
            return true;
        }

        /** A string key: a failure with partial output leaves "" for it, one without goes on. */
        void key(byte[] name) {
            if (!string(name, flags & ~NUMERIC_CHECK) && partial) {
                out.truncate(out.size() - 4);
                ascii("\"\"");
            }
            colon();
        }

        void colon() {
            out.write(':');
            if ((flags & PRETTY_PRINT) != 0) {
                out.write(' ');
            }
        }

        /** php_json_escape_string. */
        boolean string(byte[] s, long options) {
            if (s.length == 0) {
                ascii("\"\"");
                return true;
            }
            if ((options & NUMERIC_CHECK) != 0) {
                Object number = Convert.numericValue(Str.of(s));
                if (number instanceof Long l) {
                    out.writeLong(l);
                    return true;
                }
                if (number instanceof Double d && Double.isFinite(d)) {
                    number(d);
                    return true;
                }
            }
            int checkpoint = out.size();
            out.write('"');
            Html.Reader reader = new Html.Reader(s, s.length, HtmlTables.CS_UTF_8);
            while (reader.pos < s.length) {
                int c = s[reader.pos] & 0xFF;
                if (c >= 0x80) {
                    int start = reader.pos;
                    int cp = reader.next();
                    if (reader.failure) {
                        if ((options & INVALID_UTF8_IGNORE) != 0) {
                            continue;
                        }
                        if ((options & INVALID_UTF8_SUBSTITUTE) != 0) {
                            if ((options & UNESCAPED_UNICODE) != 0) {
                                out.write(new byte[]{(byte) 0xEF, (byte) 0xBF, (byte) 0xBD});
                            } else {
                                ascii("\\ufffd");
                            }
                            continue;
                        }
                        out.truncate(checkpoint);
                        error = ERROR_UTF8;
                        if (partial) {
                            ascii("null");
                        }
                        return false;
                    }
                    if ((options & UNESCAPED_UNICODE) != 0
                            && ((options & UNESCAPED_LINE_TERMINATORS) != 0 || cp < 0x2028 || cp > 0x2029)) {
                        out.write(s, start, reader.pos - start);
                    } else if (cp >= 0x10000) {
                        int v = cp - 0x10000;
                        unicodeEscape(0xD800 | (v >> 10));
                        unicodeEscape(0xDC00 | (v & 0x3FF));
                    } else {
                        unicodeEscape(cp);
                    }
                    continue;
                }
                switch (c) {
                    case '"' -> ascii((options & HEX_QUOT) != 0 ? "\\u0022" : "\\\"");
                    case '\\' -> ascii("\\\\");
                    case '/' -> ascii((options & UNESCAPED_SLASHES) != 0 ? "/" : "\\/");
                    case '\b' -> ascii("\\b");
                    case '\f' -> ascii("\\f");
                    case '\n' -> ascii("\\n");
                    case '\r' -> ascii("\\r");
                    case '\t' -> ascii("\\t");
                    case '<' -> ascii((options & HEX_TAG) != 0 ? "\\u003C" : "<");
                    case '>' -> ascii((options & HEX_TAG) != 0 ? "\\u003E" : ">");
                    case '&' -> ascii((options & HEX_AMP) != 0 ? "\\u0026" : "&");
                    case '\'' -> ascii((options & HEX_APOS) != 0 ? "\\u0027" : "'");
                    default -> {
                        if (c < 0x20) {
                            unicodeEscape(c);
                        } else {
                            out.write(c);
                        }
                    }
                }
                reader.pos++;
            }
            out.write('"');
            return true;
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
