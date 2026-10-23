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
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** ext/standard string functions, on bytes as php-src does (no locale: ASCII-only case mapping, as PHP 8.2+). */
public final class StringFunctions {
    private StringFunctions() {
    }

    static void register(Registry r) {
        r.fn("substr", a -> substr(Args.str(a, 0), Args.lng(a, 1, 0), lengthArg(a, 2)));
        r.fn("strpos", a -> strpos(Args.str(a, 0), Args.str(a, 1), Args.lng(a, 2, 0), false));
        r.fn("stripos", a -> strpos(Args.str(a, 0), Args.str(a, 1), Args.lng(a, 2, 0), true));
        r.fn("strrpos", a -> strrpos(Args.str(a, 0), Args.str(a, 1), Args.lng(a, 2, 0), false));
        r.fn("strripos", a -> strrpos(Args.str(a, 0), Args.str(a, 1), Args.lng(a, 2, 0), true));
        r.fn("str_contains", a -> indexOf(Str.readBytes(Args.str(a, 0)), Str.readBytes(Args.str(a, 1)), 0, false) >= 0);
        r.fn("str_starts_with", a -> startsWith(Str.readBytes(Args.str(a, 0)), Str.readBytes(Args.str(a, 1))));
        r.fn("str_ends_with", a -> endsWith(Str.readBytes(Args.str(a, 0)), Str.readBytes(Args.str(a, 1))));
        r.fn("str_replace", "0001", a -> replace(a, false));
        r.fn("str_ireplace", "0001", a -> replace(a, true));
        r.fn("trim", a -> trim(Args.str(a, 0), Args.get(a, 1), 3));
        r.fn("ltrim", a -> trim(Args.str(a, 0), Args.get(a, 1), 1));
        r.fn("rtrim", a -> trim(Args.str(a, 0), Args.get(a, 1), 2));
        r.fn("chop", a -> trim(Args.str(a, 0), Args.get(a, 1), 2));
        r.fn("strtolower", a -> lower(Args.str(a, 0)));
        r.fn("strtoupper", a -> upper(Args.str(a, 0)));
        r.fn("ucfirst", a -> caseFirst(Args.str(a, 0), true));
        r.fn("lcfirst", a -> caseFirst(Args.str(a, 0), false));
        r.fn("ucwords", a -> ucwords(Args.str(a, 0), Args.str(a, 1, Str.of(" \t\r\n\f\u000b"))));
        r.fn("explode", a -> explode(Args.str(a, 0), Args.str(a, 1), Args.lng(a, 2, Long.MAX_VALUE)));
        r.fn("implode", a -> implode(a));
        r.fn("join", a -> implode(a));
        r.fn("sprintf", a -> Sprintf.format(a, 0));
        r.fn("vsprintf", a -> Sprintf.formatArray(Args.str(a, 0), Args.array(a, 1, "vsprintf")));
        r.fn("printf", (context, a) -> {
            TruffleString text = Sprintf.format(a, 0);
            context.write(text);
            return (long) Str.length(text);
        });
        r.fn("str_pad", a -> strPad(Args.str(a, 0), Args.lng(a, 1, 0), Args.str(a, 2, Str.of(" ")),
                Args.lng(a, 3, 1)));
        r.fn("str_repeat", a -> repeat(Args.str(a, 0), Args.lng(a, 1, 0)));
        r.fn("strtr", a -> Args.count(a) == 2 ? strtrArray(Args.str(a, 0), Args.array(a, 1, "strtr"))
                : strtrChars(Args.str(a, 0), Args.str(a, 1), Args.str(a, 2)));
        r.fn("strspn", a -> span(Args.str(a, 0), Args.str(a, 1), Args.lng(a, 2, 0), lengthArg(a, 3), true));
        r.fn("strcspn", a -> span(Args.str(a, 0), Args.str(a, 1), Args.lng(a, 2, 0), lengthArg(a, 3), false));
        r.fn("substr_count", a -> substrCount(Args.str(a, 0), Args.str(a, 1), Args.lng(a, 2, 0), lengthArg(a, 3)));
        r.fn("substr_compare", a -> substrCompare(a));
        r.fn("substr_replace", a -> substrReplace(Args.str(a, 0), Args.str(a, 1), Args.lng(a, 2, 0),
                lengthArg(a, 3)));
        r.fn("strcmp", a -> (long) Integer.signum(Str.compare(Args.str(a, 0), Args.str(a, 1))));
        r.fn("strcasecmp", a -> (long) Integer.signum(compareIgnoreCase(Str.readBytes(Args.str(a, 0)),
                Str.readBytes(Args.str(a, 1)), Integer.MAX_VALUE)));
        r.fn("strncmp", a -> (long) Integer.signum(compareN(Args.str(a, 0), Args.str(a, 1), Args.lng(a, 2, 0))));
        r.fn("strncasecmp", a -> (long) Integer.signum(compareIgnoreCase(Str.readBytes(Args.str(a, 0)),
                Str.readBytes(Args.str(a, 1)), (int) Math.min(Integer.MAX_VALUE, Args.lng(a, 2, 0)))));
        r.fn("strnatcmp", a -> (long) Integer.signum(Str.compare(Args.str(a, 0), Args.str(a, 1))));
        r.fn("strrchr", a -> strrchr(Args.str(a, 0), Args.str(a, 1)));
        r.fn("strstr", a -> strstr(Args.str(a, 0), Args.str(a, 1), Args.bool(a, 2, false), false));
        r.fn("stristr", a -> strstr(Args.str(a, 0), Args.str(a, 1), Args.bool(a, 2, false), true));
        r.fn("strrev", a -> {
            byte[] bytes = Str.bytes(Args.str(a, 0));
            for (int i = 0, j = bytes.length - 1; i < j; i++, j--) {
                byte t = bytes[i];
                bytes[i] = bytes[j];
                bytes[j] = t;
            }
            return Str.of(bytes);
        });
        r.fn("str_split", a -> strSplit(Args.str(a, 0), Args.lng(a, 1, 1)));
        r.fn("addslashes", a -> addslashes(Args.str(a, 0)));
        r.fn("stripslashes", a -> stripslashes(Args.str(a, 0)));
        r.fn("addcslashes", a -> addcslashes(Args.str(a, 0), Args.str(a, 1)));
        r.fn("stripcslashes", a -> stripcslashes(Args.str(a, 0)));
        r.fn("htmlspecialchars", a -> Html.specialChars(Args.str(a, 0), Args.lng(a, 1, 3 | 8),
                Args.bool(a, 3, true)));
        r.fn("htmlspecialchars_decode", a -> Html.decode(Args.str(a, 0), Args.lng(a, 1, 3 | 8), true));
        r.fn("html_entity_decode", a -> Html.decode(Args.str(a, 0), Args.lng(a, 1, 3 | 8), false));
        r.fn("htmlentities", a -> Html.specialChars(Args.str(a, 0), Args.lng(a, 1, 3 | 8), Args.bool(a, 3, true)));
        r.fn("get_html_translation_table", a -> Html.translationTable(Args.lng(a, 0, 0), Args.lng(a, 1, 3 | 8)));
        r.fn("strip_tags", a -> StripTags.strip(Args.str(a, 0), Args.get(a, 1)));
        r.fn("nl2br", a -> nl2br(Args.str(a, 0)));
        r.fn("rawurlencode", a -> urlencode(Args.str(a, 0), true));
        r.fn("urlencode", a -> urlencode(Args.str(a, 0), false));
        r.fn("rawurldecode", a -> urldecode(Args.str(a, 0), false));
        r.fn("urldecode", a -> urldecode(Args.str(a, 0), true));
        r.fn("bin2hex", a -> Str.of(java.util.HexFormat.of().formatHex(Str.readBytes(Args.str(a, 0)))));
        r.fn("chr", a -> Str.singleByte((int) Math.floorMod(Args.lng(a, 0, 0), 256)));
        r.fn("ord", a -> {
            TruffleString s = Args.str(a, 0);
            return Str.length(s) == 0 ? 0L : (long) Str.byteAt(s, 0);
        });
        r.fn("number_format", a -> numberFormat(Convert.toDouble(Args.get(a, 0)), Args.lng(a, 1, 0),
                Args.str(a, 2, Str.of(".")), Args.str(a, 3, Str.of(","))));
        r.fn("wordwrap", a -> wordwrap(Args.str(a, 0), Args.lng(a, 1, 75), Args.str(a, 2, Str.of("\n")),
                Args.bool(a, 3, false)));
        r.fn("parse_str", "01", a -> {
            Args.ref(a, 1).set(UrlFunctions.parseQuery(Args.str(a, 0)));
            return PhpNull.NULL;
        });
        r.fn("parse_url", a -> UrlFunctions.parseUrl(Args.str(a, 0), Args.lng(a, 1, -1)));
        r.fn("pack", a -> Pack.pack(a));
        r.fn("unpack", a -> Pack.unpack(Args.str(a, 0), Args.str(a, 1), Args.lng(a, 2, 0)));
        r.fn("dirname", a -> dirname(Args.str(a, 0), Args.lng(a, 1, 1)));
        r.fn("basename", a -> basename(Args.str(a, 0), Args.str(a, 1, Str.EMPTY)));
        r.fn("pathinfo", a -> pathinfo(Args.str(a, 0), Args.lng(a, 1, 15)));
        r.fn("ctype_digit", a -> ctype(Args.get(a, 0), "digit"));
        r.fn("ctype_alpha", a -> ctype(Args.get(a, 0), "alpha"));
        r.fn("ctype_alnum", a -> ctype(Args.get(a, 0), "alnum"));
        r.fn("ctype_space", a -> ctype(Args.get(a, 0), "space"));
        r.fn("ctype_xdigit", a -> ctype(Args.get(a, 0), "xdigit"));
        r.fn("ctype_upper", a -> ctype(Args.get(a, 0), "upper"));
        r.fn("ctype_lower", a -> ctype(Args.get(a, 0), "lower"));
    }

    /** An optional length argument: null when missing or null. */
    static Long lengthArg(Object[] a, int index) {
        Object value = Args.get(a, index);
        return value == null || value == PhpNull.NULL ? null : Convert.toLong(value);
    }

    // ---- substrings and search ----

    @TruffleBoundary
    static TruffleString substr(TruffleString s, long offset, Long length) {
        int n = Str.length(s);
        long start = offset < 0 ? Math.max(0, n + offset) : offset;
        if (start > n) {
            return Str.EMPTY;
        }
        long end;
        if (length == null) {
            end = n;
        } else if (length < 0) {
            end = n + length;
            if (end < start) {
                return Str.EMPTY;
            }
        } else {
            end = Math.min(n, start + length);
        }
        if (start == 0 && end == n) {
            return s;
        }
        return s.substringByteIndexUncached((int) start, (int) (end - start), Str.ENC, true);
    }

    @TruffleBoundary
    static int indexOf(byte[] haystack, byte[] needle, int from, boolean ignoreCase) {
        if (needle.length == 0) {
            return from <= haystack.length ? from : -1;
        }
        if (!ignoreCase) {
            return indexOfExact(haystack, needle, from);
        }
        int last = haystack.length - needle.length;
        byte first = ignoreCase ? lowerByte(needle[0]) : needle[0];
        outer:
        for (int i = from; i <= last; i++) {
            byte b = ignoreCase ? lowerByte(haystack[i]) : haystack[i];
            if (b != first) {
                continue;
            }
            for (int j = 1; j < needle.length; j++) {
                byte x = haystack[i + j];
                byte y = needle[j];
                if (x != y && !(ignoreCase && lowerByte(x) == lowerByte(y))) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Case-sensitive search: a tight scan for the first byte, then a (vectorized) range compare. */
    private static int indexOfExact(byte[] haystack, byte[] needle, int from) {
        byte first = needle[0];
        int rest = needle.length - 1;
        int last = haystack.length - needle.length;
        for (int i = Math.max(from, 0); i <= last; i++) {
            if (haystack[i] == first && (rest == 0
                    || Arrays.equals(haystack, i + 1, i + 1 + rest, needle, 1, needle.length))) {
                return i;
            }
        }
        return -1;
    }

    @TruffleBoundary
    private static Object strpos(TruffleString haystack, TruffleString needle, long offset, boolean ignoreCase) {
        byte[] h = Str.readBytes(haystack);
        if (offset < 0) {
            offset += h.length;
        }
        if (offset < 0 || offset > h.length) {
            throw new PhpError("ValueError: strpos(): Argument #3 ($offset) must be contained in argument #1 "
                    + "($haystack)");
        }
        int index = indexOf(h, Str.readBytes(needle), (int) offset, ignoreCase);
        return index < 0 ? (Object) Boolean.FALSE : (Object) (long) index;
    }

    @TruffleBoundary
    private static Object strrpos(TruffleString haystack, TruffleString needle, long offset, boolean ignoreCase) {
        byte[] h = Str.readBytes(haystack);
        byte[] n = Str.readBytes(needle);
        int start;
        int limit;
        if (offset >= 0) {
            start = (int) offset;
            limit = h.length - n.length;
        } else {
            start = 0;
            limit = (int) Math.min(h.length - n.length, h.length + offset);
        }
        for (int i = limit; i >= start; i--) {
            if (regionMatches(h, i, n, ignoreCase)) {
                return (long) i;
            }
        }
        return Boolean.FALSE;
    }

    private static boolean regionMatches(byte[] h, int at, byte[] n, boolean ignoreCase) {
        if (at < 0 || at + n.length > h.length) {
            return false;
        }
        for (int j = 0; j < n.length; j++) {
            byte x = h[at + j];
            byte y = n[j];
            if (x != y && !(ignoreCase && lowerByte(x) == lowerByte(y))) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    private static boolean startsWith(byte[] s, byte[] prefix) {
        return regionMatches(s, 0, prefix, false);
    }

    @TruffleBoundary
    private static boolean endsWith(byte[] s, byte[] suffix) {
        return regionMatches(s, s.length - suffix.length, suffix, false);
    }

    @TruffleBoundary
    private static Object strrchr(TruffleString haystack, TruffleString needle) {
        byte[] h = Str.readBytes(haystack);
        byte[] n = Str.readBytes(needle);
        if (n.length == 0) {
            return Boolean.FALSE;
        }
        for (int i = h.length - 1; i >= 0; i--) {
            if (h[i] == n[0]) {
                return Str.of(h, i, h.length - i);
            }
        }
        return Boolean.FALSE;
    }

    @TruffleBoundary
    private static Object strstr(TruffleString haystack, TruffleString needle, boolean before, boolean ignoreCase) {
        byte[] h = Str.readBytes(haystack);
        int index = indexOf(h, Str.readBytes(needle), 0, ignoreCase);
        if (index < 0) {
            return Boolean.FALSE;
        }
        return before ? Str.of(h, 0, index) : Str.of(h, index, h.length - index);
    }

    // ---- replacement ----

    /** str_replace/str_ireplace(search, replace, subject, &count). */
    @TruffleBoundary
    private static Object replace(Object[] a, boolean ignoreCase) {
        Object search = Args.get(a, 0);
        Object replacement = Args.get(a, 1);
        Object subject = Args.get(a, 2);
        long[] count = {0};
        Object result;
        if (subject instanceof PhpArray subjects) {
            PhpArray out = new PhpArray();
            for (int p = subjects.firstPosition(); p >= 0; p = subjects.nextPosition(p)) {
                Object item = subjects.valueAt(p);
                out.put(subjects.keyAt(p), item instanceof PhpArray ? item
                        : replaceOne(Convert.toStr(item), search, replacement, ignoreCase, count));
            }
            result = out;
        } else {
            result = replaceOne(Convert.toStr(subject), search, replacement, ignoreCase, count);
        }
        if (Args.has(a, 3)) {
            Args.ref(a, 3).set(count[0]);
        }
        return result;
    }

    private static TruffleString replaceOne(TruffleString subject, Object search, Object replacement,
                                            boolean ignoreCase, long[] count) {
        if (!(search instanceof PhpArray searches)) {
            if (replacement instanceof PhpArray) {
                throw new PhpError("TypeError: str_replace(): Argument #2 ($replace) must be of type string when "
                        + "argument #1 ($search) is a string");
            }
            return replaceAll(subject, Convert.toStr(search), Convert.toStr(replacement), ignoreCase, count);
        }
        TruffleString result = subject;
        PhpArray replacements = replacement instanceof PhpArray array ? array : null;
        int r = replacements == null ? -1 : replacements.firstPosition();
        for (int p = searches.firstPosition(); p >= 0; p = searches.nextPosition(p)) {
            TruffleString with;
            if (replacements == null) {
                with = Convert.toStr(replacement);
            } else if (r >= 0) {
                with = Convert.toStr(replacements.valueAt(r));
                r = replacements.nextPosition(r);
            } else {
                with = Str.EMPTY;
            }
            result = replaceAll(result, Convert.toStr(searches.valueAt(p)), with, ignoreCase, count);
        }
        return result;
    }

    static TruffleString replaceAll(TruffleString subject, TruffleString search, TruffleString with,
                                    boolean ignoreCase, long[] count) {
        int searchLength = Str.length(search);
        if (searchLength == 0 || Str.length(subject) < searchLength) {
            return subject;
        }
        int index = ignoreCase ? indexOf(Str.readBytes(subject), Str.readBytes(search), 0, true)
                : Str.indexOf(subject, search, 0);
        if (index < 0) {
            return subject;
        }
        byte[] s = Str.readBytes(subject);
        byte[] f = Str.readBytes(search);
        byte[] w = Str.readBytes(with);
        ByteBuilder out = ByteBuilder.borrow(s.length);
        try {
            int from = 0;
            while (index >= 0) {
                out.write(s, from, index - from);
                out.write(w, 0, w.length);
                count[0]++;
                from = index + f.length;
                index = ignoreCase ? indexOf(s, f, from, true) : Str.indexOf(subject, search, from);
            }
            out.write(s, from, s.length - from);
            return Str.of(out.toByteArray());
        } finally {
            out.release();
        }
    }

    /** strtr($s, $pairs): longest keys first at each position; replaced text is not rescanned. */
    @TruffleBoundary
    private static TruffleString strtrArray(TruffleString subject, PhpArray pairs) {
        int n = pairs.size();
        if (n == 0) {
            return subject;
        }
        byte[][] keys = new byte[n][];
        byte[][] values = new byte[n][];
        boolean singleBytes = true;
        int k = 0;
        for (int p = pairs.firstPosition(); p >= 0; p = pairs.nextPosition(p)) {
            byte[] key = Str.readBytes(Convert.toStr(pairs.keyAt(p)));
            if (key.length == 0) {
                return subject;
            }
            keys[k] = key;
            values[k] = Str.readBytes(Convert.toStr(pairs.valueAt(p)));
            singleBytes &= key.length == 1;
            k++;
        }
        byte[] s = Str.readBytes(subject);
        return singleBytes ? strtrBytes(subject, s, keys, values) : strtrKeys(subject, s, keys, values);
    }

    /** strtr with one-byte keys only (escaping tables like kses's): a byte-indexed table, one pass. */
    private static TruffleString strtrBytes(TruffleString subject, byte[] s, byte[][] keys, byte[][] values) {
        byte[][] table = new byte[256][];
        for (int k = 0; k < keys.length; k++) {
            table[keys[k][0] & 0xff] = values[k];
        }
        int i = 0;
        while (i < s.length && table[s[i] & 0xff] == null) {
            i++;
        }
        if (i == s.length) {
            return subject;
        }
        ByteBuilder out = new ByteBuilder(s.length + 16);
        out.write(s, 0, i);
        for (; i < s.length; i++) {
            byte[] value = table[s[i] & 0xff];
            if (value == null) {
                out.write(s[i]);
            } else {
                out.write(value, 0, value.length);
            }
        }
        return Str.of(out.toByteArray());
    }

    /**
     * strtr with keys of any length: at each position the longest key that matches wins, and replaced text is not
     * rescanned. Keys are chained per first byte, longest first.
     */
    private static TruffleString strtrKeys(TruffleString subject, byte[] s, byte[][] keys, byte[][] values) {
        Integer[] byLength = new Integer[keys.length];
        for (int k = 0; k < keys.length; k++) {
            byLength[k] = k;
        }
        Arrays.sort(byLength, (a, b) -> Integer.compare(keys[a].length, keys[b].length));
        int[] head = new int[256];
        Arrays.fill(head, -1);
        int[] next = new int[keys.length];
        for (int k : byLength) {
            int first = keys[k][0] & 0xff;
            next[k] = head[first];
            head[first] = k;
        }
        ByteBuilder out = null;
        int copied = 0;
        int i = 0;
        while (i < s.length) {
            int found = -1;
            for (int k = head[s[i] & 0xff]; k >= 0; k = next[k]) {
                byte[] key = keys[k];
                if (i + key.length <= s.length && Arrays.equals(s, i, i + key.length, key, 0, key.length)) {
                    found = k;
                    break;
                }
            }
            if (found < 0) {
                i++;
                continue;
            }
            if (out == null) {
                out = new ByteBuilder(s.length + 16);
            }
            out.write(s, copied, i - copied);
            out.write(values[found], 0, values[found].length);
            i += keys[found].length;
            copied = i;
        }
        if (out == null) {
            return subject;
        }
        out.write(s, copied, s.length - copied);
        return Str.of(out.toByteArray());
    }

    @TruffleBoundary
    private static TruffleString strtrChars(TruffleString subject, TruffleString from, TruffleString to) {
        byte[] f = Str.readBytes(from);
        byte[] t = Str.readBytes(to);
        int n = Math.min(f.length, t.length);
        if (n == 0) {
            return subject;
        }
        byte[] map = new byte[256];
        boolean[] mapped = new boolean[256];
        for (int i = 0; i < n; i++) {
            map[f[i] & 0xFF] = t[i];
            mapped[f[i] & 0xFF] = true;
        }
        byte[] s = Str.bytes(subject);
        for (int i = 0; i < s.length; i++) {
            if (mapped[s[i] & 0xFF]) {
                s[i] = map[s[i] & 0xFF];
            }
        }
        return Str.of(s);
    }

    @TruffleBoundary
    private static TruffleString substrReplace(TruffleString s, TruffleString replacement, long offset,
                                               Long length) {
        byte[] b = Str.readBytes(s);
        int n = b.length;
        long start = offset < 0 ? Math.max(0, n + offset) : Math.min(offset, n);
        long end;
        if (length == null) {
            end = n;
        } else if (length < 0) {
            end = Math.max(start, n + length);
        } else {
            end = Math.min(n, start + length);
        }
        byte[] r = Str.readBytes(replacement);
        byte[] out = new byte[(int) (start + r.length + (n - end))];
        System.arraycopy(b, 0, out, 0, (int) start);
        System.arraycopy(r, 0, out, (int) start, r.length);
        System.arraycopy(b, (int) end, out, (int) start + r.length, (int) (n - end));
        return Str.of(out);
    }

    // ---- trimming and case ----

    /** A trim character set: "a..z" ranges allowed, as php_charmask. */
    static boolean[] charMask(byte[] chars) {
        boolean[] mask = new boolean[256];
        for (int i = 0; i < chars.length; i++) {
            int c = chars[i] & 0xFF;
            if (i + 3 < chars.length && chars[i + 1] == '.' && chars[i + 2] == '.' && (chars[i + 3] & 0xFF) >= c) {
                for (int x = c; x <= (chars[i + 3] & 0xFF); x++) {
                    mask[x] = true;
                }
                i += 3;
            } else {
                mask[c] = true;
            }
        }
        return mask;
    }

    private static final boolean[] DEFAULT_TRIM = charMask(new byte[]{' ', '\t', '\n', '\r', 0, 0x0B});

    /** mode bit 1 trims the left, bit 2 the right. */
    @TruffleBoundary
    private static TruffleString trim(TruffleString s, Object chars, int mode) {
        boolean[] mask = chars == null ? DEFAULT_TRIM : charMask(Str.readBytes(Convert.toStr(chars)));
        int n = Str.length(s);
        int start = 0;
        int end = n;
        if ((mode & 1) != 0) {
            while (start < end && mask[Str.byteAt(s, start)]) {
                start++;
            }
        }
        if ((mode & 2) != 0) {
            while (end > start && mask[Str.byteAt(s, end - 1)]) {
                end--;
            }
        }
        if (start == 0 && end == n) {
            return s;
        }
        return s.substringByteIndexUncached(start, end - start, Str.ENC, true);
    }

    static byte lowerByte(byte b) {
        return b >= 'A' && b <= 'Z' ? (byte) (b + 32) : b;
    }

    static byte upperByte(byte b) {
        return b >= 'a' && b <= 'z' ? (byte) (b - 32) : b;
    }

    @TruffleBoundary
    public static TruffleString lower(TruffleString s) {
        byte[] view = Str.readBytes(s);
        int first = 0;
        while (first < view.length && !(view[first] >= 'A' && view[first] <= 'Z')) {
            first++;
        }
        if (first == view.length) {
            return s;
        }
        byte[] b = view.clone();
        for (int i = first; i < b.length; i++) {
            if (b[i] >= 'A' && b[i] <= 'Z') {
                b[i] += 32;
            }
        }
        return Str.of(b);
    }

    @TruffleBoundary
    public static TruffleString upper(TruffleString s) {
        byte[] view = Str.readBytes(s);
        int first = 0;
        while (first < view.length && !(view[first] >= 'a' && view[first] <= 'z')) {
            first++;
        }
        if (first == view.length) {
            return s;
        }
        byte[] b = view.clone();
        for (int i = first; i < b.length; i++) {
            if (b[i] >= 'a' && b[i] <= 'z') {
                b[i] -= 32;
            }
        }
        return Str.of(b);
    }

    @TruffleBoundary
    public static TruffleString caseFirst(TruffleString s, boolean upper) {
        byte[] b = Str.bytes(s);
        if (b.length == 0) {
            return s;
        }
        byte first = upper ? upperByte(b[0]) : lowerByte(b[0]);
        if (first == b[0]) {
            return s;
        }
        b[0] = first;
        return Str.of(b);
    }

    @TruffleBoundary
    private static TruffleString ucwords(TruffleString s, TruffleString delimiters) {
        boolean[] mask = charMask(Str.readBytes(delimiters));
        byte[] b = Str.bytes(s);
        boolean start = true;
        for (int i = 0; i < b.length; i++) {
            if (start) {
                b[i] = upperByte(b[i]);
            }
            start = mask[b[i] & 0xFF];
        }
        return Str.of(b);
    }

    static int compareIgnoreCase(byte[] a, byte[] b, int limit) {
        int n = Math.min(Math.min(a.length, b.length), limit);
        for (int i = 0; i < n; i++) {
            int x = lowerByte(a[i]) & 0xFF;
            int y = lowerByte(b[i]) & 0xFF;
            if (x != y) {
                return x - y;
            }
        }
        return Math.min(a.length, limit) - Math.min(b.length, limit);
    }

    @TruffleBoundary
    private static int compareN(TruffleString a, TruffleString b, long n) {
        byte[] x = Str.readBytes(a);
        byte[] y = Str.readBytes(b);
        int limit = (int) Math.min(n, Integer.MAX_VALUE);
        int m = Math.min(Math.min(x.length, y.length), limit);
        for (int i = 0; i < m; i++) {
            if (x[i] != y[i]) {
                return (x[i] & 0xFF) - (y[i] & 0xFF);
            }
        }
        return Math.min(x.length, limit) - Math.min(y.length, limit);
    }

    // ---- splitting and joining ----

    @TruffleBoundary
    private static PhpArray explode(TruffleString separator, TruffleString s, long limit) {
        byte[] sep = Str.readBytes(separator);
        if (sep.length == 0) {
            throw new PhpError("ValueError: explode(): Argument #1 ($separator) cannot be empty");
        }
        byte[] b = Str.readBytes(s);
        List<TruffleString> parts = new ArrayList<>();
        int from = 0;
        int index = indexOf(b, sep, 0, false);
        while (index >= 0 && (limit <= 0 || parts.size() < limit - 1)) {
            parts.add(Str.of(b, from, index - from));
            from = index + sep.length;
            index = indexOf(b, sep, from, false);
        }
        parts.add(Str.of(b, from, b.length - from));
        if (limit < 0) {
            int keep = (int) Math.max(0, parts.size() + limit);
            parts = parts.subList(0, keep);
        }
        PhpArray result = PhpArray.ofCapacity(parts.size());
        for (TruffleString part : parts) {
            result.append(part);
        }
        return result;
    }

    @TruffleBoundary
    private static TruffleString implode(Object[] a) {
        Object first = Args.get(a, 0);
        Object second = Args.get(a, 1);
        PhpArray pieces;
        TruffleString glue;
        if (first instanceof PhpArray array) {
            pieces = array;
            glue = second == null ? Str.EMPTY : Convert.toStr(second);
        } else if (second instanceof PhpArray array) {
            pieces = array;
            glue = Convert.toStr(first);
        } else {
            throw new PhpError("TypeError: implode(): Argument #2 ($array) must be of type ?array");
        }
        ByteBuilder out = new ByteBuilder();
        boolean firstPiece = true;
        for (int p = pieces.firstPosition(); p >= 0; p = pieces.nextPosition(p)) {
            if (!firstPiece) {
                out.write(glue);
            }
            Object piece = pieces.valueAt(p);
            if (piece instanceof Long l) {
                out.writeLong(l);
            } else {
                out.write(piece instanceof TruffleString s ? s : Convert.toStr(piece));
            }
            firstPiece = false;
        }
        return Str.of(out.toByteArray());
    }

    @TruffleBoundary
    private static PhpArray strSplit(TruffleString s, long length) {
        if (length < 1) {
            throw new PhpError("ValueError: str_split(): Argument #2 ($length) must be greater than 0");
        }
        byte[] b = Str.readBytes(s);
        PhpArray result = new PhpArray();
        if (b.length == 0) {
            result.append(Str.EMPTY);
            return result;
        }
        for (int i = 0; i < b.length; i += (int) length) {
            result.append(Str.of(b, i, (int) Math.min(length, b.length - i)));
        }
        return result;
    }

    @TruffleBoundary
    private static TruffleString strPad(TruffleString s, long length, TruffleString pad, long type) {
        byte[] b = Str.readBytes(s);
        byte[] p = Str.readBytes(pad);
        if (length <= b.length || p.length == 0) {
            return s;
        }
        int total = (int) length - b.length;
        int left = type == 0 ? total : type == 2 ? total / 2 : 0;
        int right = total - left;
        byte[] out = new byte[(int) length];
        for (int i = 0; i < left; i++) {
            out[i] = p[i % p.length];
        }
        System.arraycopy(b, 0, out, left, b.length);
        for (int i = 0; i < right; i++) {
            out[left + b.length + i] = p[i % p.length];
        }
        return Str.of(out);
    }

    @TruffleBoundary
    private static TruffleString repeat(TruffleString s, long times) {
        if (times < 0) {
            throw new PhpError("ValueError: str_repeat(): Argument #2 ($times) must be greater than or equal to 0");
        }
        byte[] b = Str.readBytes(s);
        byte[] out = new byte[(int) (b.length * times)];
        for (int i = 0; i < times; i++) {
            System.arraycopy(b, 0, out, i * b.length, b.length);
        }
        return Str.of(out);
    }

    @TruffleBoundary
    private static long span(TruffleString s, TruffleString mask, long offset, Long length, boolean accept) {
        byte[] b = Str.readBytes(s);
        boolean[] set = new boolean[256];
        for (byte m : Str.readBytes(mask)) {
            set[m & 0xFF] = true;
        }
        int n = b.length;
        long start = offset < 0 ? Math.max(0, n + offset) : Math.min(offset, n);
        long end = length == null ? n : length < 0 ? Math.max(start, n + length) : Math.min(n, start + length);
        int i = (int) start;
        while (i < end && set[b[i] & 0xFF] == accept) {
            i++;
        }
        return i - start;
    }

    @TruffleBoundary
    private static long substrCount(TruffleString haystack, TruffleString needle, long offset, Long length) {
        byte[] h = Str.readBytes(haystack);
        byte[] n = Str.readBytes(needle);
        if (n.length == 0) {
            throw new PhpError("ValueError: substr_count(): Argument #2 ($needle) cannot be empty");
        }
        int start = (int) (offset < 0 ? h.length + offset : offset);
        int end = length == null ? h.length : (int) (length < 0 ? h.length + length : start + length);
        long count = 0;
        int i = indexOf(h, n, start, false);
        while (i >= 0 && i + n.length <= end) {
            count++;
            i = indexOf(h, n, i + n.length, false);
        }
        return count;
    }

    @TruffleBoundary
    private static Object substrCompare(Object[] a) {
        byte[] main = Str.readBytes(Args.str(a, 0));
        byte[] other = Str.readBytes(Args.str(a, 1));
        long offset = Args.lng(a, 2, 0);
        Long length = lengthArg(a, 3);
        boolean ignoreCase = Args.bool(a, 4, false);
        if (offset < 0) {
            offset = Math.max(0, main.length + offset);
        }
        if (offset > main.length) {
            throw new PhpError("ValueError: substr_compare(): Argument #3 ($offset) must be contained in argument "
                    + "#1 ($haystack)");
        }
        int limit = length == null ? Math.max(main.length - (int) offset, other.length) : (int) (long) length;
        byte[] part = Arrays.copyOfRange(main, (int) offset, main.length);
        if (ignoreCase) {
            return (long) Integer.signum(compareIgnoreCase(part, other, limit));
        }
        int m = Math.min(Math.min(part.length, other.length), limit);
        for (int i = 0; i < m; i++) {
            if (part[i] != other[i]) {
                return (long) Integer.signum((part[i] & 0xFF) - (other[i] & 0xFF));
            }
        }
        return (long) Integer.signum(Math.min(part.length, limit) - Math.min(other.length, limit));
    }

    // ---- escaping ----

    @TruffleBoundary
    private static TruffleString addslashes(TruffleString s) {
        byte[] b = Str.readBytes(s);
        ByteBuilder out = new ByteBuilder(b.length + 8);
        for (byte c : b) {
            if (c == '\'' || c == '"' || c == '\\') {
                out.write('\\');
                out.write(c);
            } else if (c == 0) {
                out.write('\\');
                out.write('0');
            } else {
                out.write(c);
            }
        }
        return Str.of(out.toByteArray());
    }

    @TruffleBoundary
    private static TruffleString stripslashes(TruffleString s) {
        byte[] b = Str.readBytes(s);
        ByteBuilder out = new ByteBuilder(b.length);
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '\\') {
                if (++i < b.length) {
                    out.write(b[i] == '0' ? 0 : b[i]);
                }
            } else {
                out.write(b[i]);
            }
        }
        return Str.of(out.toByteArray());
    }

    @TruffleBoundary
    private static TruffleString addcslashes(TruffleString s, TruffleString chars) {
        boolean[] mask = charMask(Str.readBytes(chars));
        byte[] b = Str.readBytes(s);
        ByteBuilder out = new ByteBuilder(b.length + 8);
        for (byte c : b) {
            int u = c & 0xFF;
            if (!mask[u]) {
                out.write(c);
                continue;
            }
            out.write('\\');
            if (u < 32 || u > 126) {
                String escape = switch (u) {
                    case '\n' -> "n";
                    case '\t' -> "t";
                    case '\r' -> "r";
                    case 7 -> "a";
                    case 0x0B -> "v";
                    case '\b' -> "b";
                    case '\f' -> "f";
                    default -> String.format("%03o", u);
                };
                out.writeBytes(escape.getBytes(StandardCharsets.ISO_8859_1));
            } else {
                out.write(c);
            }
        }
        return Str.of(out.toByteArray());
    }

    @TruffleBoundary
    private static TruffleString stripcslashes(TruffleString s) {
        byte[] b = Str.readBytes(s);
        ByteBuilder out = new ByteBuilder(b.length);
        for (int i = 0; i < b.length; i++) {
            if (b[i] != '\\' || i + 1 >= b.length) {
                out.write(b[i]);
                continue;
            }
            byte c = b[++i];
            switch (c) {
                case 'n' -> out.write('\n');
                case 't' -> out.write('\t');
                case 'r' -> out.write('\r');
                case 'a' -> out.write(7);
                case 'v' -> out.write(0x0B);
                case 'b' -> out.write('\b');
                case 'f' -> out.write('\f');
                case 'x' -> {
                    int value = 0;
                    int digits = 0;
                    while (digits < 2 && i + 1 < b.length && Character.digit(b[i + 1], 16) >= 0) {
                        value = value * 16 + Character.digit(b[++i], 16);
                        digits++;
                    }
                    if (digits == 0) {
                        out.write('x');
                    } else {
                        out.write(value);
                    }
                }
                default -> {
                    if (c >= '0' && c <= '7') {
                        int value = c - '0';
                        int digits = 1;
                        while (digits < 3 && i + 1 < b.length && b[i + 1] >= '0' && b[i + 1] <= '7') {
                            value = value * 8 + (b[++i] - '0');
                            digits++;
                        }
                        out.write(value & 0xFF);
                    } else {
                        out.write(c);
                    }
                }
            }
        }
        return Str.of(out.toByteArray());
    }

    @TruffleBoundary
    private static TruffleString nl2br(TruffleString s) {
        byte[] b = Str.readBytes(s);
        ByteBuilder out = new ByteBuilder(b.length + 16);
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '\r' || b[i] == '\n') {
                out.writeBytes("<br />".getBytes(StandardCharsets.ISO_8859_1));
                out.write(b[i]);
                if (i + 1 < b.length && (b[i + 1] == '\r' || b[i + 1] == '\n') && b[i + 1] != b[i]) {
                    out.write(b[++i]);
                }
            } else {
                out.write(b[i]);
            }
        }
        return Str.of(out.toByteArray());
    }

    @TruffleBoundary
    private static TruffleString urlencode(TruffleString s, boolean raw) {
        byte[] b = Str.readBytes(s);
        ByteBuilder out = new ByteBuilder(b.length * 3 / 2);
        for (byte c : b) {
            int u = c & 0xFF;
            boolean safe = u >= 'a' && u <= 'z' || u >= 'A' && u <= 'Z' || u >= '0' && u <= '9' || u == '-' || u == '_'
                    || u == '.' || raw && u == '~';
            if (safe) {
                out.write(u);
            } else if (!raw && u == ' ') {
                out.write('+');
            } else {
                out.write('%');
                out.write("0123456789ABCDEF".charAt(u >> 4));
                out.write("0123456789ABCDEF".charAt(u & 15));
            }
        }
        return Str.of(out.toByteArray());
    }

    @TruffleBoundary
    static TruffleString urldecode(TruffleString s, boolean plusAsSpace) {
        byte[] b = Str.readBytes(s);
        ByteBuilder out = new ByteBuilder(b.length);
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '+' && plusAsSpace) {
                out.write(' ');
            } else if (b[i] == '%' && i + 2 < b.length && Character.digit(b[i + 1], 16) >= 0
                    && Character.digit(b[i + 2], 16) >= 0) {
                out.write(Character.digit(b[i + 1], 16) * 16 + Character.digit(b[i + 2], 16));
                i += 2;
            } else {
                out.write(b[i]);
            }
        }
        return Str.of(out.toByteArray());
    }

    @TruffleBoundary
    private static TruffleString numberFormat(double value, long decimals, TruffleString point, TruffleString sep) {
        java.math.BigDecimal rounded = new java.math.BigDecimal(Double.toString(Math.abs(value)))
                .setScale((int) Math.max(0, decimals), java.math.RoundingMode.HALF_UP);
        String plain = rounded.toPlainString();
        String integer = plain.contains(".") ? plain.substring(0, plain.indexOf('.')) : plain;
        String fraction = plain.contains(".") ? plain.substring(plain.indexOf('.') + 1) : "";
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < integer.length(); i++) {
            if (i > 0 && (integer.length() - i) % 3 == 0) {
                grouped.append(Str.java(sep));
            }
            grouped.append(integer.charAt(i));
        }
        String sign = value < 0 && rounded.signum() != 0 ? "-" : "";
        return Str.of(sign + grouped + (decimals > 0 ? Str.java(point) + fraction : ""));
    }

    @TruffleBoundary
    private static TruffleString wordwrap(TruffleString s, long width, TruffleString brk, boolean cut) {
        String text = Str.java(s);
        String breakText = Str.java(brk);
        if (breakText.isEmpty()) {
            throw new PhpError("ValueError: wordwrap(): Argument #3 ($break) must not be empty");
        }
        if (width == 0 && cut) {
            throw new PhpError("ValueError: wordwrap(): Argument #4 ($cut_long_words) cannot be true when "
                    + "argument #2 ($width) is 0");
        }
        StringBuilder out = new StringBuilder();
        int lineStart = 0;
        int lastSpace = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (text.startsWith(breakText, i)) {
                out.append(text, lineStart, i + breakText.length());
                i += breakText.length() - 1;
                lineStart = i + 1;
                lastSpace = -1;
                continue;
            }
            if (c == ' ') {
                if (i - lineStart >= width) {
                    out.append(text, lineStart, i).append(breakText);
                    lineStart = i + 1;
                    lastSpace = -1;
                    continue;
                }
                lastSpace = i;
            }
            if (i - lineStart >= width && lastSpace >= lineStart) {
                out.append(text, lineStart, lastSpace).append(breakText);
                lineStart = lastSpace + 1;
                lastSpace = -1;
            } else if (cut && i - lineStart >= width) {
                out.append(text, lineStart, i).append(breakText);
                lineStart = i;
            }
        }
        out.append(text.substring(lineStart));
        return Str.of(out.toString());
    }

    // ---- paths ----

    @TruffleBoundary
    private static TruffleString dirname(TruffleString path, long levels) {
        String p = Str.java(path);
        for (long i = 0; i < levels; i++) {
            int end = p.length();
            while (end > 1 && p.charAt(end - 1) == '/') {
                end--;
            }
            int slash = p.lastIndexOf('/', end - 1);
            if (slash < 0) {
                return Str.of(".");
            }
            while (slash > 0 && p.charAt(slash - 1) == '/') {
                slash--;
            }
            p = slash == 0 ? "/" : p.substring(0, slash);
        }
        return Str.of(p);
    }

    @TruffleBoundary
    private static TruffleString basename(TruffleString path, TruffleString suffix) {
        String p = Str.java(path);
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        String base = p.substring(p.lastIndexOf('/') + 1);
        String s = Str.java(suffix);
        if (!s.isEmpty() && base.endsWith(s) && !base.equals(s)) {
            base = base.substring(0, base.length() - s.length());
        }
        return Str.of(base);
    }

    @TruffleBoundary
    private static Object pathinfo(TruffleString path, long options) {
        String p = Str.java(path);
        PhpArray info = new PhpArray();
        int slash = p.lastIndexOf('/');
        if ((options & 1) != 0 && slash >= 0) {
            info.put(Str.of("dirname"), dirname(path, 1));
        }
        String base = Str.java(basename(path, Str.EMPTY));
        if ((options & 2) != 0) {
            info.put(Str.of("basename"), Str.of(base));
        }
        int dot = base.lastIndexOf('.');
        if ((options & 4) != 0 && dot >= 0) {
            info.put(Str.of("extension"), Str.of(base.substring(dot + 1)));
        }
        if ((options & 8) != 0) {
            info.put(Str.of("filename"), Str.of(dot >= 0 ? base.substring(0, dot) : base));
        }
        if (options != 15) {
            Object only = info.firstPosition() >= 0 ? info.valueAt(info.firstPosition()) : Str.EMPTY;
            return only;
        }
        return info;
    }

    @TruffleBoundary
    private static boolean ctype(Object value, String kind) {
        if (!(value instanceof TruffleString s) || Str.length(s) == 0) {
            return false;
        }
        for (byte b : Str.readBytes(s)) {
            int c = b & 0xFF;
            boolean ok = switch (kind) {
                case "digit" -> c >= '0' && c <= '9';
                case "alpha" -> c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
                case "alnum" -> c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
                case "space" -> c == ' ' || c >= 9 && c <= 13;
                case "xdigit" -> Character.digit(c, 16) >= 0;
                case "upper" -> c >= 'A' && c <= 'Z';
                case "lower" -> c >= 'a' && c <= 'z';
                default -> false;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Reads a bundled resource next to this class. */
    static byte[] resource(String name) {
        try (InputStream in = StringFunctions.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new PhpError("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new PhpError("cannot read resource " + name);
        }
    }

    static Object keyOf(String text) {
        return ArrayKey.of(Str.of(text));
    }

    static Object deref(Object value) {
        return PhpRef.deref(value);
    }
}
