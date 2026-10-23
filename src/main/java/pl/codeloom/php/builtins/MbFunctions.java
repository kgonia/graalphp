package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.Str;

import java.util.Locale;

/**
 * The mbstring functions WordPress calls, for UTF-8 (plus ASCII for mb_check_encoding). Invalid input decodes as
 * php-src 8.x does: one substitute character per maximal ill-formed subpart.
 */
final class MbFunctions {
    private MbFunctions() {
    }

    /** mb_substitute_character(): a code point, or -1 for "none". */
    private static int substitute = '?';

    static void register(Registry r) {
        // per-process state starts over in each context (BatchMain runs many contexts in one JVM)
        substitute = '?';
        r.fn("mb_check_encoding", a -> checkEncoding(Args.get(a, 0), encoding(a, 1)));
        r.fn("mb_substr", a -> substr(Args.str(a, 0), Args.lng(a, 1, 0), StringFunctions.lengthArg(a, 2),
                encoding(a, 3)));
        r.fn("mb_strlen", a -> strlen(Args.str(a, 0), encoding(a, 1)));
        r.fn("mb_strtolower", a -> changeCase(Args.str(a, 0), encoding(a, 1), false));
        r.fn("mb_strtoupper", a -> changeCase(Args.str(a, 0), encoding(a, 1), true));
        r.fn("mb_scrub", a -> scrub(Args.str(a, 0), encoding(a, 1)));
        r.fn("mb_ord", a -> ord(Args.str(a, 0), encoding(a, 1)));
        r.fn("mb_chr", a -> chr(Args.lng(a, 0, 0), encoding(a, 1)));
        r.fn("mb_internal_encoding", a -> Args.has(a, 0) ? (Object) Boolean.TRUE : Str.of("UTF-8"));
        r.fn("mb_substitute_character", a -> {
            if (!Args.has(a, 0) || Args.get(a, 0) == PhpNull.NULL) {
                return substitute == -1 ? Str.of("none") : (Object) (long) substitute;
            }
            Object value = Args.get(a, 0);
            if (value instanceof TruffleString s) {
                String name = Str.java(s).toLowerCase(Locale.ROOT);
                if (name.equals("none")) {
                    substitute = -1;
                    return true;
                }
                throw new PhpError("mb_substitute_character(\"" + name + "\") is not supported");
            }
            long cp = Convert.toLong(value);
            if (cp < 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
                throw new PhpError("ValueError: mb_substitute_character(): Argument #1 ($substitute_character) "
                        + "is not a valid codepoint");
            }
            substitute = (int) cp;
            return true;
        });
    }

    /** The encoding argument, normalized: "UTF-8" (the default) or "ASCII". */
    private static String encoding(Object[] a, int index) {
        Object value = Args.get(a, index, PhpNull.NULL);
        if (value == PhpNull.NULL) {
            return "UTF-8";
        }
        String name = Str.java(Convert.toStr(value)).toUpperCase(Locale.ROOT);
        return switch (name) {
            case "UTF-8", "UTF8" -> "UTF-8";
            case "ASCII", "US-ASCII" -> "ASCII";
            default -> throw new PhpError("mbstring encoding " + name + " is not supported");
        };
    }

    @TruffleBoundary
    private static Object checkEncoding(Object value, String encoding) {
        if (value instanceof PhpArray array) {
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                if (array.keyAt(p) instanceof TruffleString key && !(Boolean) checkEncoding(key, encoding)) {
                    return false;
                }
                if (!(Boolean) checkEncoding(array.valueAt(p), encoding)) {
                    return false;
                }
            }
            return true;
        }
        byte[] s = Str.readBytes(Convert.toStr(value));
        if (encoding.equals("ASCII")) {
            for (byte b : s) {
                if (b < 0) {
                    return false;
                }
            }
            return true;
        }
        int i = 0;
        while (i < s.length) {
            int length = JsonFunctions.Utf8.sequenceLength(s, i);
            if (length == 0) {
                return false;
            }
            i += length;
        }
        return true;
    }

    /** mb_get_strlen for UTF-8: the bytes that aren't continuation bytes. */
    @TruffleBoundary
    private static long strlen(TruffleString text, String encoding) {
        byte[] s = Str.readBytes(text);
        if (encoding.equals("ASCII")) {
            return s.length;
        }
        long count = 0;
        for (byte b : s) {
            if ((b & 0xC0) != 0x80) {
                count++;
            }
        }
        return count;
    }

    /** The UTF-8 mblen table php-src steps through for mb_substr. */
    private static int charLength(byte b) {
        int c = b & 0xFF;
        if (c < 0xC0) {
            return 1;
        }
        if (c < 0xE0) {
            return 2;
        }
        if (c < 0xF0) {
            return 3;
        }
        if (c < 0xF8) {
            return 4;
        }
        if (c < 0xFC) {
            return 5;
        }
        return c < 0xFE ? 6 : 1;
    }

    @TruffleBoundary
    private static TruffleString substr(TruffleString text, long start, Long length, String encoding) {
        byte[] s = Str.readBytes(text);
        if (encoding.equals("ASCII")) {
            return StringFunctions.substr(text, start, length);
        }
        if (start < 0 || (length != null && length < 0)) {
            long total = strlen(text, encoding);
            if (start < 0) {
                start = Math.max(0, total + start);
            }
            if (length != null && length < 0) {
                length = Math.max(0, total - start + length);
            }
        }
        int from = skip(s, 0, start);
        int to = length == null ? s.length : skip(s, from, length);
        return Str.of(s, from, to - from);
    }

    private static int skip(byte[] s, int position, long chars) {
        for (long n = 0; n < chars && position < s.length; n++) {
            position = Math.min(s.length, position + charLength(s[position]));
        }
        return position;
    }

    /**
     * The length of the maximal ill-formed subpart at i (php-src 8.x / WHATWG: the lead byte plus the continuation
     * bytes that could still have formed a valid sequence), for input where sequenceLength returned 0.
     */
    private static int badLength(byte[] s, int i) {
        int c = s[i] & 0xFF;
        int needed;
        int lowest = 0x80;
        int highest = 0xBF;
        if (c >= 0xC2 && c <= 0xDF) {
            needed = 1;
        } else if (c >= 0xE0 && c <= 0xEF) {
            needed = 2;
            if (c == 0xE0) {
                lowest = 0xA0;
            } else if (c == 0xED) {
                highest = 0x9F;
            }
        } else if (c >= 0xF0 && c <= 0xF4) {
            needed = 3;
            if (c == 0xF0) {
                lowest = 0x90;
            } else if (c == 0xF4) {
                highest = 0x8F;
            }
        } else {
            return 1;
        }
        int length = 1;
        for (int k = 0; k < needed && i + length < s.length; k++) {
            int b = s[i + length] & 0xFF;
            if (b < lowest || b > highest) {
                break;
            }
            length++;
            lowest = 0x80;
            highest = 0xBF;
        }
        return length;
    }

    /** Decodes UTF-8 to code points; -1 marks each ill-formed subpart. */
    private static int[] decode(byte[] s) {
        int[] out = new int[s.length];
        int n = 0;
        int i = 0;
        while (i < s.length) {
            int length = JsonFunctions.Utf8.sequenceLength(s, i);
            if (length == 0) {
                out[n++] = -1;
                i += badLength(s, i);
            } else {
                out[n++] = JsonFunctions.Utf8.decode(s, i, length);
                i += length;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static void encode(ByteBuilder out, int cp) {
        if (cp == -1) {
            if (substitute != -1) {
                JsonFunctions.Utf8.encode(out, substitute);
            }
        } else {
            JsonFunctions.Utf8.encode(out, cp);
        }
    }

    @TruffleBoundary
    private static TruffleString scrub(TruffleString text, String encoding) {
        byte[] s = Str.readBytes(text);
        ByteBuilder out = new ByteBuilder(s.length);
        if (encoding.equals("ASCII")) {
            for (byte b : s) {
                encode(out, b < 0 ? -1 : b);
            }
        } else {
            for (int cp : decode(s)) {
                encode(out, cp);
            }
        }
        return Str.of(out.toByteArray());
    }

    @TruffleBoundary
    private static TruffleString changeCase(TruffleString text, String encoding, boolean upper) {
        byte[] s = Str.readBytes(text);
        StringBuilder chars = new StringBuilder(s.length);
        for (int cp : decode(s)) {
            if (cp == -1) {
                // a placeholder that case mapping leaves alone, swapped for the substitute below
                chars.append('￿');
            } else {
                chars.appendCodePoint(cp);
            }
        }
        String mapped = upper ? chars.toString().toUpperCase(Locale.ROOT) : chars.toString().toLowerCase(Locale.ROOT);
        ByteBuilder out = new ByteBuilder(s.length);
        for (int i = 0; i < mapped.length(); ) {
            int cp = mapped.codePointAt(i);
            encode(out, cp == 0xFFFF ? -1 : cp);
            i += Character.charCount(cp);
        }
        return Str.of(out.toByteArray());
    }

    @TruffleBoundary
    private static Object ord(TruffleString text, String encoding) {
        byte[] s = Str.readBytes(text);
        if (s.length == 0) {
            throw new PhpError("ValueError: mb_ord(): Argument #1 ($string) must not be empty");
        }
        int length = JsonFunctions.Utf8.sequenceLength(s, 0);
        return length == 0 ? (Object) Boolean.FALSE : (long) JsonFunctions.Utf8.decode(s, 0, length);
    }

    @TruffleBoundary
    private static Object chr(long cp, String encoding) {
        if (cp < 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            return Boolean.FALSE;
        }
        ByteBuilder out = new ByteBuilder(4);
        JsonFunctions.Utf8.encode(out, (int) cp);
        return Str.of(out.toByteArray());
    }
}
