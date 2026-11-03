package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
        r.fn("vsprintf", (context, a) -> Sprintf.formatArray(context, a, 0, "vsprintf"));
        r.fn("sscanf", "00*", (context, a) -> {
            Args.arity(a, "sscanf", 2, Integer.MAX_VALUE);
            TruffleString string = Args.string(context, a, 0, "sscanf", "string", false);
            TruffleString format = Args.string(context, a, 1, "sscanf", "format", false);
            return Scanf.scan(Str.readBytes(string), Str.readBytes(format), a, 2);
        });
        r.fn("str_getcsv", (context, a) -> {
            Args.arity(a, "str_getcsv", 1, 4);
            byte[] string = Str.readBytes(Args.string(context, a, 0, "str_getcsv", "string", false));
            int delimiter = csvChar(context, a, 1, "separator", ',');
            int enclosure = csvChar(context, a, 2, "enclosure", '"');
            int escape;
            if (Args.has(a, 3)) {
                byte[] e = Str.readBytes(Args.string(context, a, 3, "str_getcsv", "escape", false));
                if (e.length > 1) {
                    throw new PhpError("ValueError", "str_getcsv(): Argument #4 ($escape) must be empty or a single "
                            + "character");
                }
                escape = e.length == 0 ? -1 : e[0] & 0xFF;
            } else {
                ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, Str.of("str_getcsv(): the $escape "
                        + "parameter must be provided as its default value will change"), SourceLocation.current());
                escape = '\\';
            }
            PhpArray values = csv(string, (byte) delimiter, (byte) enclosure, escape,
                    !context.builtinState().locales[0].equals("C"));
            return values != null ? values : PhpArray.list(PhpNull.NULL);
        });
        r.fn("levenshtein", (context, a) -> {
            Args.arity(a, "levenshtein", 2, 5);
            byte[] s1 = Str.readBytes(Args.string(context, a, 0, "levenshtein", "string1", false));
            byte[] s2 = Str.readBytes(Args.string(context, a, 1, "levenshtein", "string2", false));
            long ins = Args.has(a, 2) ? Args.integer(context, a, 2, "levenshtein", "insertion_cost", "int") : 1;
            long rep = Args.has(a, 3) ? Args.integer(context, a, 3, "levenshtein", "replacement_cost", "int") : 1;
            long del = Args.has(a, 4) ? Args.integer(context, a, 4, "levenshtein", "deletion_cost", "int") : 1;
            return levenshtein(s1, s2, ins, rep, del);
        });
        r.fn("similar_text", "001", (context, a) -> {
            Args.arity(a, "similar_text", 2, 3);
            byte[] s1 = Str.readBytes(Args.string(context, a, 0, "similar_text", "string1", false));
            byte[] s2 = Str.readBytes(Args.string(context, a, 1, "similar_text", "string2", false));
            long sim = s1.length + s2.length == 0 ? 0 : similar(s1, 0, s1.length, s2, 0, s2.length);
            if (Args.has(a, 2)) {
                Args.ref(a, 2).set(s1.length + s2.length == 0 ? 0.0 : sim * 200.0 / (s1.length + s2.length));
            }
            return sim;
        });
        r.fn("metaphone", (context, a) -> {
            Args.arity(a, "metaphone", 1, 2);
            byte[] word = Str.readBytes(Args.string(context, a, 0, "metaphone", "string", false));
            long max = Args.has(a, 1) ? Args.integer(context, a, 1, "metaphone", "max_phonemes", "int") : 0;
            if (max < 0) {
                throw new PhpError("ValueError", "metaphone(): Argument #2 ($max_phonemes) must be greater than or "
                        + "equal to 0");
            }
            return metaphone(word, max);
        });
        string(r, "soundex", StringFunctions::soundex);
        string(r, "quoted_printable_encode", StringFunctions::quotedPrintableEncode);
        string(r, "quoted_printable_decode", StringFunctions::quotedPrintableDecode);
        string(r, "convert_uuencode", StringFunctions::uuencode);
        string(r, "convert_uudecode", StringFunctions::uudecode);
        string(r, "str_rot13", s -> {
            for (int i = 0; i < s.length; i++) {
                int c = s[i] | 0x20;
                if (c >= 'a' && c <= 'z') {
                    s[i] = (byte) (s[i] + (c <= 'm' ? 13 : -13));
                }
            }
            return Str.of(s);
        });
        string(r, "str_shuffle", s -> {
            java.util.Random random = java.util.concurrent.ThreadLocalRandom.current();
            for (int left = s.length - 1; left > 0; left--) {
                int j = random.nextInt(left + 1);
                byte t = s[left];
                s[left] = s[j];
                s[j] = t;
            }
            return Str.of(s);
        });
        r.fn("utf8_encode", (context, a) -> {
            deprecated(context, "utf8_encode", "8.2, visit the php.net documentation for various alternatives");
            Args.arity(a, "utf8_encode", 1, 1);
            ByteBuilder out = new ByteBuilder();
            for (byte b : Str.readBytes(Args.string(context, a, 0, "utf8_encode", "string", false))) {
                if (b >= 0) {
                    out.write(b);
                } else {
                    out.write(0xC0 | (b & 0xFF) >> 6);
                    out.write(0x80 | b & 0x3F);
                }
            }
            return Str.of(out.toByteArray());
        });
        r.fn("utf8_decode", (context, a) -> {
            deprecated(context, "utf8_decode", "8.2, visit the php.net documentation for various alternatives");
            Args.arity(a, "utf8_decode", 1, 1);
            return Html.utf8Decode(Str.readBytes(Args.string(context, a, 0, "utf8_decode", "string", false)));
        });
        r.fn("count_chars", (context, a) -> {
            Args.arity(a, "count_chars", 1, 2);
            byte[] s = Str.readBytes(Args.string(context, a, 0, "count_chars", "string", false));
            long mode = Args.has(a, 1) ? Args.integer(context, a, 1, "count_chars", "mode", "int") : 0;
            if (mode < 0 || mode > 4) {
                throw new PhpError("ValueError", "count_chars(): Argument #2 ($mode) must be between 0 and 4 "
                        + "(inclusive)");
            }
            long[] counts = new long[256];
            for (byte b : s) {
                counts[b & 0xFF]++;
            }
            PhpArray result = new PhpArray();
            ByteBuilder chars = new ByteBuilder();
            for (int c = 0; c < 256; c++) {
                if (mode == 0 || mode == 1 && counts[c] != 0 || mode == 2 && counts[c] == 0) {
                    result.put((long) c, counts[c]);
                } else if (mode == 3 && counts[c] != 0 || mode == 4 && counts[c] == 0) {
                    chars.write(c);
                }
            }
            return mode < 3 ? result : Str.of(chars.toByteArray());
        });
        r.fn("str_word_count", (context, a) -> {
            Args.arity(a, "str_word_count", 1, 3);
            byte[] s = Str.readBytes(Args.string(context, a, 0, "str_word_count", "string", false));
            long format = Args.has(a, 1) ? Args.integer(context, a, 1, "str_word_count", "format", "int") : 0;
            TruffleString list = Args.has(a, 2) ? Args.string(context, a, 2, "str_word_count", "characters", true)
                    : null;
            if (format < 0 || format > 2) {
                throw new PhpError("ValueError", "str_word_count(): Argument #2 ($format) must be a valid format "
                        + "value");
            }
            return wordCount(s, format, list == null ? null : charMask(Str.readBytes(list), "str_word_count"));
        });
        r.fn("vprintf", (context, a) -> {
            TruffleString text = Sprintf.formatArray(context, a, 0, "vprintf");
            context.write(text);
            return (long) Str.length(text);
        });
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
        r.fn("strnatcmp", a -> (long) naturalCompare(Str.bytes(Args.str(a, 0)), Str.bytes(Args.str(a, 1)), false));
        r.fn("strnatcasecmp", a -> (long) naturalCompare(Str.bytes(Args.str(a, 0)), Str.bytes(Args.str(a, 1)),
                true));
        r.fn("strtok", (context, a) -> strtok(context.builtinState(), a));
        r.fn("strpbrk", a -> {
            byte[] haystack = Str.bytes(Args.str(a, 0));
            byte[] chars = Str.bytes(Args.str(a, 1));
            for (int i = 0; i < haystack.length; i++) {
                for (byte c : chars) {
                    if (haystack[i] == c) {
                        return Str.of(haystack, i, haystack.length - i);
                    }
                }
            }
            return Boolean.FALSE;
        });
        r.fn("chunk_split", a -> {
            byte[] body = Str.bytes(Args.str(a, 0));
            int length = (int) Args.lng(a, 1, 76);
            byte[] end = Str.bytes(Args.str(a, 2, Str.of("\r\n")));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < body.length; i += length) {
                out.write(body, i, Math.min(length, body.length - i));
                out.writeBytes(end);
            }
            return Str.of(out.toByteArray());
        });
        r.fn("quotemeta", a -> {
            byte[] input = Str.bytes(Args.str(a, 0));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (byte b : input) {
                if (".\\+*?[^]$()".indexOf(b) >= 0) {
                    out.write('\\');
                }
                out.write(b);
            }
            return Str.of(out.toByteArray());
        });
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
        r.fn("htmlspecialchars", a -> Html.escape(Args.str(a, 0), false, Args.lng(a, 1, Html.DEFAULT_FLAGS),
                charsetArg(a, 2), Args.bool(a, 3, true)));
        r.fn("htmlspecialchars_decode", a -> Html.unescape(Args.str(a, 0), false, Args.lng(a, 1, Html.DEFAULT_FLAGS),
                null));
        r.fn("html_entity_decode", a -> Html.unescape(Args.str(a, 0), true, Args.lng(a, 1, Html.DEFAULT_FLAGS),
                charsetArg(a, 2)));
        r.fn("htmlentities", a -> Html.escape(Args.str(a, 0), true, Args.lng(a, 1, Html.DEFAULT_FLAGS),
                charsetArg(a, 2), Args.bool(a, 3, true)));
        r.fn("get_html_translation_table", a -> Html.translationTable(Args.lng(a, 0, 0),
                Args.lng(a, 1, Html.DEFAULT_FLAGS), charsetArg(a, 2)));
        r.fn("strip_tags", a -> StripTags.strip(Args.str(a, 0), Args.get(a, 1)));
        r.fn("nl2br", a -> nl2br(Args.str(a, 0)));
        r.fn("str_increment", a -> strStep(Args.str(a, 0), true));
        r.fn("str_decrement", a -> strStep(Args.str(a, 0), false));
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
        // the C locale's conventions: php-src sets only LC_CTYPE from the environment at startup, and setlocale()
        // isn't there to change LC_NUMERIC or LC_MONETARY (CHAR_MAX, 127, for the unspecified numbers)
        r.fn("setlocale", (context, a) -> {
            Args.arity(a, "setlocale", 2, Integer.MAX_VALUE);
            long category = Args.integer(context, a, 0, "setlocale", "category", "int");
            for (int i = 1; i < Args.count(a); i++) {
                Object locale = Args.get(a, i);
                if (!(locale instanceof PhpArray || locale == PhpNull.NULL || locale instanceof TruffleString
                        || locale instanceof Long || locale instanceof Double || locale instanceof Boolean)) {
                    throw new PhpError("TypeError", "setlocale(): Argument #" + (i + 1) + (i == 1 ? " ($locales)" : "")
                            + " must be of type array|string|null, " + Args.valueName(locale) + " given");
                }
            }
            String[] locales = context.builtinState().locales;
            for (int i = 1; i < Args.count(a); i++) {
                Object locale = Args.get(a, i);
                Object[] tries = locale instanceof PhpArray list ? list.entries() : new Object[]{null, locale};
                for (int j = 1; j < tries.length; j += 2) {
                    Object name = tries[j] == PhpNull.NULL ? Str.EMPTY : tries[j];
                    String result = setlocale(context, locales, category, Str.java(Convert.toStr(name)));
                    if (result != null) {
                        return Str.of(result);
                    }
                }
            }
            return Boolean.FALSE;
        });
        r.fn("nl_langinfo", (context, a) -> {
            Args.arity(a, "nl_langinfo", 1, 1);
            long item = Args.integer(context, a, 0, "nl_langinfo", "item", "int");
            String value = LANGINFO.get(item);
            if (value == null) {
                ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("nl_langinfo(): Item '" + item
                        + "' is not valid"), SourceLocation.current());
                return Boolean.FALSE;
            }
            String[] locales = context.builtinState().locales;
            boolean utf8 = !locales[(int) (item >> 16)].equals("C");
            if (item == 14) {
                value = utf8 ? "UTF-8" : value;
            } else if (utf8 && item >= 262151 && item <= 262158) {
                // glibc's C.UTF-8 reads these chars as strings, running on into the next fields
                value = "\u00ff".repeat((int) (262159 - item)) + "-";
            }
            return Str.of(value.getBytes(StandardCharsets.ISO_8859_1));
        });
        r.fn("strcoll", (context, a) -> {
            Args.arity(a, "strcoll", 2, 2);
            byte[] s1 = Str.readBytes(Args.string(context, a, 0, "strcoll", "string1", false));
            byte[] s2 = Str.readBytes(Args.string(context, a, 1, "strcoll", "string2", false));
            // the C and C.UTF-8 locales collate as strcmp: the bytes up to the first NUL
            for (int i = 0; ; i++) {
                int c1 = at(s1, i);
                int c2 = at(s2, i);
                if (c1 != c2 || c1 == 0) {
                    return (long) (c1 - c2);
                }
            }
        });
        r.fn("localeconv", a -> {
            PhpArray conventions = new PhpArray();
            for (String key : new String[] {"decimal_point", "thousands_sep", "int_curr_symbol", "currency_symbol",
                "mon_decimal_point", "mon_thousands_sep", "positive_sign", "negative_sign"}) {
                conventions.put(ArrayKey.of(Str.of(key)), Str.of(key.equals("decimal_point") ? "." : ""));
            }
            for (String key : new String[] {"int_frac_digits", "frac_digits", "p_cs_precedes", "p_sep_by_space",
                "n_cs_precedes", "n_sep_by_space", "p_sign_posn", "n_sign_posn"}) {
                conventions.put(ArrayKey.of(Str.of(key)), 127L);
            }
            conventions.put(ArrayKey.of(Str.of("grouping")), new PhpArray());
            conventions.put(ArrayKey.of(Str.of("mon_grouping")), new PhpArray());
            return conventions;
        });
        r.fn("number_format", a -> numberFormat(Convert.toDouble(Args.get(a, 0)), Args.lng(a, 1, 0),
                Args.str(a, 2, Str.of(".")), Args.str(a, 3, Str.of(","))));
        r.fn("wordwrap", a -> wordwrap(Args.str(a, 0), Args.lng(a, 1, 75), Args.str(a, 2, Str.of("\n")),
                Args.bool(a, 3, false)));
        r.fn("parse_str", "01", (context, a) -> {
            FilterFunctions.parsedString(context, UrlFunctions.parseQuery(Args.str(a, 0)), Args.ref(a, 1));
            return PhpNull.NULL;
        });
        r.fn("parse_url", a -> UrlFunctions.parseUrl(Args.str(a, 0), Args.lng(a, 1, -1)));
        r.fn("http_build_query", UrlFunctions::httpBuildQuery);
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
            throw new PhpError("ValueError", "strpos(): Argument #3 ($offset) must be contained in argument #1 "
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
                throw new PhpError("TypeError", "str_replace(): Argument #2 ($replace) must be of type string when "
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

    /**
     * A character set as php_charmask: "a..z" ranges allowed; a malformed range warns as from the function and
     * adds none of its dots.
     */
    static boolean[] charMask(byte[] chars, String function) {
        boolean[] mask = new boolean[256];
        int length = chars.length;
        for (int i = 0; i < length; i++) {
            int c = chars[i] & 0xFF;
            if (i + 3 < length && chars[i + 1] == '.' && chars[i + 2] == '.' && (chars[i + 3] & 0xFF) >= c) {
                for (int x = c; x <= (chars[i + 3] & 0xFF); x++) {
                    mask[x] = true;
                }
                i += 3;
            } else if (i + 1 < length && chars[i] == '.' && chars[i + 1] == '.') {
                String problem;
                if (i == 0) {
                    problem = ", no character to the left of '..'";
                } else if (i + 2 >= length) {
                    problem = ", no character to the right of '..'";
                } else if ((chars[i - 1] & 0xFF) > (chars[i + 2] & 0xFF)) {
                    problem = ", '..'-range needs to be incrementing";
                } else {
                    problem = "";
                }
                PhpContext context = PhpContext.get(null);
                ErrorFunctions.raise(context, ErrorFunctions.E_WARNING,
                        Str.of(function + "(): Invalid '..'-range" + problem), SourceLocation.current());
            } else {
                mask[c] = true;
            }
        }
        return mask;
    }

    private static final boolean[] DEFAULT_TRIM = charMask(new byte[]{' ', '\t', '\n', '\r', 0, 0x0B}, "trim");

    /** Character lists up to this long without a '.' (so without a range) are searched instead of masked. */
    private static final int SHORT_TRIM_LIST = 8;

    /**
     * mode bit 1 trims the left, bit 2 the right. A short list without ranges ("/", "/\\", ", ") is searched
     * per byte, as php_charmask's mask would answer it, without making a mask per call.
     */
    @TruffleBoundary
    private static TruffleString trim(TruffleString s, Object chars, int mode) {
        byte[] list = chars == null ? null : Str.readBytes(Convert.toStr(chars));
        boolean[] mask = list == null ? DEFAULT_TRIM : isShortList(list) ? null
                : charMask(list, mode == 3 ? "trim" : mode == 1 ? "ltrim" : "rtrim");
        int n = Str.length(s);
        int start = 0;
        int end = n;
        if ((mode & 1) != 0) {
            while (start < end && trims(mask, list, Str.byteAt(s, start))) {
                start++;
            }
        }
        if ((mode & 2) != 0) {
            while (end > start && trims(mask, list, Str.byteAt(s, end - 1))) {
                end--;
            }
        }
        if (start == 0 && end == n) {
            return s;
        }
        return s.substringByteIndexUncached(start, end - start, Str.ENC, true);
    }

    private static boolean isShortList(byte[] list) {
        if (list.length > SHORT_TRIM_LIST) {
            return false;
        }
        for (byte b : list) {
            if (b == '.') {
                return false;
            }
        }
        return true;
    }

    /** Whether trim removes the byte c: by the mask, or else by searching the short list. */
    private static boolean trims(boolean[] mask, byte[] list, int c) {
        if (mask != null) {
            return mask[c];
        }
        for (byte b : list) {
            if ((b & 0xFF) == c) {
                return true;
            }
        }
        return false;
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
        if (Str.length(s) == 0) {
            return s;
        }
        boolean[] mask = charMask(Str.readBytes(delimiters), "ucwords");
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
            throw new PhpError("ValueError", "explode(): Argument #1 ($separator) cannot be empty");
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
            throw new PhpError("TypeError", "implode(): Argument #2 ($array) must be of type ?array");
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
            throw new PhpError("ValueError", "str_split(): Argument #2 ($length) must be greater than 0");
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
            throw new PhpError("ValueError", "str_repeat(): Argument #2 ($times) must be greater than or equal to 0");
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
            throw new PhpError("ValueError", "substr_count(): Argument #2 ($needle) cannot be empty");
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
            throw new PhpError("ValueError", "substr_compare(): Argument #3 ($offset) must be contained in argument "
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
    static TruffleString addslashes(TruffleString s) {
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
        if (Str.length(s) == 0 || Str.length(chars) == 0) {
            return s;
        }
        boolean[] mask = charMask(Str.readBytes(chars), "addcslashes");
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
                out.write(escape.getBytes(StandardCharsets.ISO_8859_1));
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
                out.write("<br />".getBytes(StandardCharsets.ISO_8859_1));
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
    static TruffleString urlencode(TruffleString s, boolean raw) {
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

    /**
     * str_increment and str_decrement (string.c): an ASCII alphanumeric string stepped as a number in mixed bases,
     * carrying through z, Z and 9 (increment) or a, A and 0 (decrement).
     */
    @TruffleBoundary
    private static TruffleString strStep(TruffleString string, boolean increment) {
        String function = increment ? "str_increment" : "str_decrement";
        byte[] s = Str.readBytes(string).clone();
        if (s.length == 0) {
            throw new PhpError("ValueError", function + "(): Argument #1 ($string) must not be empty");
        }
        for (byte c : s) {
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9')) {
                throw new PhpError("ValueError",
                        function + "(): Argument #1 ($string) must be composed only of alphanumeric ASCII characters");
            }
        }
        if (!increment && s[0] == '0') {
            throw outOfDecrementRange(string);
        }
        int position = s.length - 1;
        boolean carry;
        do {
            byte c = s[position];
            if (increment) {
                carry = c == 'z' || c == 'Z' || c == '9';
                s[position] = !carry ? (byte) (c + 1) : c == '9' ? (byte) '0' : (byte) (c - 25);
            } else {
                carry = c == 'a' || c == 'A' || c == '0';
                s[position] = !carry ? (byte) (c - 1) : c == '0' ? (byte) '9' : (byte) (c + 25);
            }
        } while (carry && position-- > 0);
        if (increment) {
            if (!carry) {
                return Str.of(s);
            }
            byte[] longer = new byte[s.length + 1];
            longer[0] = s[0] == '0' ? (byte) '1' : s[0];
            System.arraycopy(s, 0, longer, 1, s.length);
            return Str.of(longer);
        }
        if (carry || s[0] == '0' && s.length > 1) {
            if (s.length == 1) {
                throw outOfDecrementRange(string);
            }
            return Str.of(Arrays.copyOfRange(s, 1, s.length));
        }
        return Str.of(s);
    }

    private static PhpError outOfDecrementRange(TruffleString string) {
        return new PhpError("ValueError", "str_decrement(): Argument #1 ($string) \"" + Str.java(string)
                + "\" is out of decrement range");
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
            throw new PhpError("ValueError", "wordwrap(): Argument #3 ($break) must not be empty");
        }
        if (width == 0 && cut) {
            throw new PhpError("ValueError", "wordwrap(): Argument #4 ($cut_long_words) cannot be true when "
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
        if (levels < 1) {
            throw new PhpError("ValueError", "dirname(): Argument #2 ($levels) must be greater than or equal to 1");
        }
        byte[] p = Str.bytes(path);
        int length = p.length;
        int before;
        do {
            before = length;
            length = dirname(p, length);
        } while (length < before && --levels > 0);
        return Str.of(p, 0, length);
    }

    /** zend_dirname on the first length bytes, in place: the new length. */
    private static int dirname(byte[] p, int length) {
        if (length == 0) {
            return 0;
        }
        int end = length - 1;
        while (end >= 0 && p[end] == '/') {
            end--;
        }
        if (end >= 0) {
            while (end >= 0 && p[end] != '/') {
                end--;
            }
            if (end < 0) {
                p[0] = '.';
                return 1;
            }
            while (end >= 0 && p[end] == '/') {
                end--;
            }
        }
        if (end < 0) {
            p[0] = '/';
            return 1;
        }
        return end + 1;
    }

    @TruffleBoundary
    private static TruffleString basename(TruffleString path, TruffleString suffix) {
        // php_basename in an ASCII-compatible locale (C, C.UTF-8): bytes
        byte[] p = Str.readBytes(path);
        int end = p.length - 1;
        while (end >= 0 && p[end] == '/') {
            end--;
        }
        int start = end++;
        while (start > 0 && p[start - 1] != '/') {
            start--;
        }
        if (end <= 0) {
            return Str.EMPTY;
        }
        byte[] x = Str.readBytes(suffix);
        if (x.length < end - start && Arrays.equals(p, end - x.length, end, x, 0, x.length)) {
            end -= x.length;
        }
        return Str.of(p, start, end - start);
    }

    @TruffleBoundary
    private static Object pathinfo(TruffleString path, long options) {
        PhpArray info = new PhpArray();
        TruffleString dirname = (options & 1) != 0 ? dirname(path, 1) : Str.EMPTY;
        if (Str.length(dirname) > 0) {
            info.put(Str.of("dirname"), dirname);
        }
        TruffleString base = basename(path, Str.EMPTY);
        if ((options & 2) != 0) {
            info.put(Str.of("basename"), base);
        }
        byte[] b = Str.readBytes(base);
        int dot = b.length - 1;
        while (dot >= 0 && b[dot] != '.') {
            dot--;
        }
        if ((options & 4) != 0 && dot >= 0) {
            info.put(Str.of("extension"), Str.of(b, dot + 1, b.length - dot - 1));
        }
        if ((options & 8) != 0) {
            info.put(Str.of("filename"), dot >= 0 ? Str.of(b, 0, dot) : base);
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
    /** An optional ?string charset argument of the html functions: null when missing or null. */
    private static TruffleString charsetArg(Object[] a, int index) {
        Object value = Args.get(a, index);
        return value == null || value == PhpNull.NULL ? null : Args.str(a, index);
    }

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

    /**
     * php-src's strnatcmp_ex: leading zeros are skipped at the start only, whitespace is skipped, digit runs
     * compare as numbers (right-aligned) or, when one starts with 0, as fractions (left-aligned).
     */
    @TruffleBoundary
    static int naturalCompare(byte[] a, byte[] b, boolean foldCase) {
        if (a.length == 0 || b.length == 0) {
            return a.length == b.length ? 0 : a.length > b.length ? 1 : -1;
        }
        int[] ap = {0};
        int[] bp = {0};
        boolean leading = true;
        while (true) {
            int ca = at(a, ap[0]);
            int cb = at(b, bp[0]);
            while (leading && ca == '0' && ap[0] + 1 < a.length && isDigit(a[ap[0] + 1])) {
                ca = at(a, ++ap[0]);
            }
            while (leading && cb == '0' && bp[0] + 1 < b.length && isDigit(b[bp[0] + 1])) {
                cb = at(b, ++bp[0]);
            }
            leading = false;
            while (isSpace(ca)) {
                ca = at(a, ++ap[0]);
            }
            while (isSpace(cb)) {
                cb = at(b, ++bp[0]);
            }
            if (isDigit((byte) ca) && isDigit((byte) cb)) {
                int result = ca == '0' || cb == '0' ? compareLeft(a, ap, b, bp) : compareRight(a, ap, b, bp);
                if (result != 0) {
                    return result;
                } else if (ap[0] >= a.length && bp[0] >= b.length) {
                    return 0;
                } else if (ap[0] >= a.length) {
                    return -1;
                } else if (bp[0] >= b.length) {
                    return 1;
                }
                ca = at(a, ap[0]);
                cb = at(b, bp[0]);
            }
            if (foldCase) {
                // zend_toupper_ascii: ASCII letters only
                ca = ca >= 'a' && ca <= 'z' ? ca - ('a' - 'A') : ca;
                cb = cb >= 'a' && cb <= 'z' ? cb - ('a' - 'A') : cb;
            }
            if (ca != cb) {
                return ca < cb ? -1 : 1;
            }
            ap[0]++;
            bp[0]++;
            if (ap[0] >= a.length && bp[0] >= b.length) {
                return 0;
            } else if (ap[0] >= a.length) {
                return -1;
            } else if (bp[0] >= b.length) {
                return 1;
            }
        }
    }

    /** Right-aligned digit runs: the longer run wins, else the first differing digit (kept as the bias). */
    private static int compareRight(byte[] a, int[] ap, byte[] b, int[] bp) {
        int bias = 0;
        for (;; ap[0]++, bp[0]++) {
            boolean aEnd = ap[0] >= a.length || !isDigit(a[ap[0]]);
            boolean bEnd = bp[0] >= b.length || !isDigit(b[bp[0]]);
            if (aEnd && bEnd) {
                return bias;
            } else if (aEnd) {
                return -1;
            } else if (bEnd) {
                return 1;
            } else if (bias == 0 && a[ap[0]] != b[bp[0]]) {
                bias = a[ap[0]] < b[bp[0]] ? -1 : 1;
            }
        }
    }

    /** Left-aligned (fractional) digit runs: the first differing digit wins. */
    private static int compareLeft(byte[] a, int[] ap, byte[] b, int[] bp) {
        for (;; ap[0]++, bp[0]++) {
            boolean aEnd = ap[0] >= a.length || !isDigit(a[ap[0]]);
            boolean bEnd = bp[0] >= b.length || !isDigit(b[bp[0]]);
            if (aEnd && bEnd) {
                return 0;
            } else if (aEnd) {
                return -1;
            } else if (bEnd) {
                return 1;
            } else if (a[ap[0]] != b[bp[0]]) {
                return a[ap[0]] < b[bp[0]] ? -1 : 1;
            }
        }
    }

    private static int at(byte[] bytes, int index) {
        return index < bytes.length ? bytes[index] & 0xff : 0;
    }

    private static boolean isSpace(int c) {
        return c == ' ' || c >= 9 && c <= 13;
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    /** strtok($string, $token) starts over; strtok($token) continues the context's last string. */
    @TruffleBoundary
    private static Object strtok(BuiltinState state, Object[] a) {
        byte[] token;
        if (Args.has(a, 1)) {
            state.strtok = Str.bytes(Args.str(a, 0));
            state.strtokPosition = 0;
            token = Str.bytes(Args.str(a, 1));
        } else {
            token = Str.bytes(Args.str(a, 0));
        }
        byte[] input = state.strtok;
        if (input == null) {
            return Boolean.FALSE;
        }
        int start = state.strtokPosition;
        while (start < input.length && contains(token, input[start])) {
            start++;
        }
        if (start >= input.length) {
            state.strtok = null;
            return Boolean.FALSE;
        }
        int end = start;
        while (end < input.length && !contains(token, input[end])) {
            end++;
        }
        state.strtokPosition = end + 1;
        return Str.of(input, start, end - start);
    }

    private static boolean contains(byte[] set, byte b) {
        for (byte c : set) {
            if (c == b) {
                return true;
            }
        }
        return false;
    }

    /** A function of one string, $string (Z_PARAM_STR), given its bytes (a copy the function may change). */
    private static void string(Registry r, String name, java.util.function.Function<byte[], Object> body) {
        r.fn(name, (context, a) -> {
            Args.arity(a, name, 1, 1);
            return body.apply(Str.bytes(Args.string(context, a, 0, name, "string", false)));
        });
    }

    /** zend_deprecated_function for a function declared #[\Deprecated(since, message)]. */
    private static void deprecated(PhpContext context, String function, String sinceAndMessage) {
        ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, Str.of("Function " + function
                + "() is deprecated since " + sinceAndMessage), SourceLocation.current());
    }

    /** levenshtein.c's reference_levdist. */
    private static long levenshtein(byte[] s1, byte[] s2, long ins, long rep, long del) {
        if (s1.length == 0) {
            return s2.length * ins;
        }
        if (s2.length == 0) {
            return s1.length * del;
        }
        if (s1.length < s2.length && ins == rep && rep == del) {
            byte[] t = s1;
            s1 = s2;
            s2 = t;
        }
        long[] p1 = new long[s2.length + 1];
        long[] p2 = new long[s2.length + 1];
        for (int i2 = 0; i2 <= s2.length; i2++) {
            p1[i2] = i2 * ins;
        }
        for (byte c : s1) {
            p2[0] = p1[0] + del;
            for (int i2 = 0; i2 < s2.length; i2++) {
                long c0 = p1[i2] + (c == s2[i2] ? 0 : rep);
                c0 = Math.min(c0, p1[i2 + 1] + del);
                p2[i2 + 1] = Math.min(c0, p2[i2] + ins);
            }
            long[] t = p1;
            p1 = p2;
            p2 = t;
        }
        return p1[s2.length];
    }

    /** php_similar_char: the longest common substring, then the same left and right of it. */
    private static long similar(byte[] t1, int from1, int len1, byte[] t2, int from2, int len2) {
        int max = 0;
        int count = 0;
        int pos1 = 0;
        int pos2 = 0;
        for (int p = 0; p < len1; p++) {
            for (int q = 0; q < len2; q++) {
                int l = 0;
                while (p + l < len1 && q + l < len2 && t1[from1 + p + l] == t2[from2 + q + l]) {
                    l++;
                }
                if (l > max) {
                    max = l;
                    count++;
                    pos1 = p;
                    pos2 = q;
                }
            }
        }
        long sum = max;
        if (sum > 0) {
            if (pos1 > 0 && pos2 > 0 && count > 1) {
                sum += similar(t1, from1, pos1, t2, from2, pos2);
            }
            if (pos1 + max < len1 && pos2 + max < len2) {
                sum += similar(t1, from1 + pos1 + max, len1 - pos1 - max, t2, from2 + pos2 + max, len2 - pos2 - max);
            }
        }
        return sum;
    }

    /** soundex.c: Knuth's soundex, of the ASCII letters. */
    private static Object soundex(byte[] s) {
        String table = "01230120022455012623010202";
        byte[] out = {'0', '0', '0', '0'};
        int small = 0;
        int last = -1;
        for (int i = 0; i < s.length && small < 4; i++) {
            int code = s[i] >= 'a' && s[i] <= 'z' ? s[i] - 32 : s[i];
            if (code >= 'A' && code <= 'Z') {
                int digit = table.charAt(code - 'A') == '0' ? 0 : table.charAt(code - 'A');
                if (small == 0) {
                    out[small++] = (byte) code;
                    last = digit;
                } else if (digit != last) {
                    if (digit != 0) {
                        out[small++] = (byte) digit;
                    }
                    last = digit;
                }
            }
        }
        return Str.of(out);
    }

    /** php_quot_print_encode: lines of at most 75, CRLF kept, a multibyte sequence's encoding on one line. */
    private static Object quotedPrintableEncode(byte[] s) {
        ByteBuilder d = new ByteBuilder(s.length * 3);
        long lp = 0;
        for (int i = 0; i < s.length; i++) {
            int c = s[i] & 0xFF;
            if (c == '\r' && at(s, i + 1) == '\n' && i + 1 < s.length) {
                d.write('\r');
                d.write('\n');
                i++;
                lp = 0;
            } else if (c < 0x20 || c == 0x7f || c >= 0x80 || c == '=' || c == ' ' && at(s, i + 1) == '\r') {
                if ((lp += 3) > 75 && c <= 0x7f || c > 0x7f && c <= 0xdf && lp + 3 > 75
                        || c > 0xdf && c <= 0xef && lp + 6 > 75 || c > 0xef && c <= 0xf4 && lp + 9 > 75) {
                    d.writeAscii("=\r\n");
                    lp = 3;
                }
                d.write('=');
                d.write("0123456789ABCDEF".charAt(c >> 4));
                d.write("0123456789ABCDEF".charAt(c & 0xf));
            } else {
                if (++lp > 75) {
                    d.writeAscii("=\r\n");
                    lp = 1;
                }
                d.write(c);
            }
        }
        return Str.of(d.toByteArray());
    }

    /** quoted_printable_decode: =XX, and soft line breaks (= then spaces or tabs then a line end); to a NUL. */
    private static Object quotedPrintableDecode(byte[] s) {
        ByteBuilder out = new ByteBuilder(s.length);
        int i = 0;
        while (at(s, i) != 0) {
            if (at(s, i) == '=' && Character.digit(at(s, i + 1), 16) >= 0 && Character.digit(at(s, i + 2), 16) >= 0
                    && at(s, i + 1) < 128 && at(s, i + 2) < 128) {
                out.write(Character.digit(at(s, i + 1), 16) << 4 | Character.digit(at(s, i + 2), 16));
                i += 3;
            } else if (at(s, i) == '=') {
                int k = 1;
                while (at(s, i + k) == ' ' || at(s, i + k) == '\t') {
                    k++;
                }
                if (at(s, i + k) == 0) {
                    i += k;
                } else if (at(s, i + k) == '\r' && at(s, i + k + 1) == '\n') {
                    i += k + 2;
                } else if (at(s, i + k) == '\r' || at(s, i + k) == '\n') {
                    i += k + 1;
                } else {
                    out.write(s[i++]);
                }
            } else {
                out.write(s[i++]);
            }
        }
        return Str.of(out.toByteArray());
    }

    /** PHP_UU_ENC. */
    private static int uu(int c) {
        return c != 0 ? (c & 077) + ' ' : '`';
    }

    /** php_uuencode: lines of 45 bytes. */
    private static Object uuencode(byte[] src) {
        ByteBuilder p = new ByteBuilder(src.length * 3 / 2 + 46);
        int len = 45;
        int s = 0;
        int e = src.length;
        while (s + 3 < e) {
            int ee = s + len;
            if (ee > e) {
                ee = e;
                len = ee - s;
                if (len % 3 != 0) {
                    ee = s + len / 3 * 3;
                }
            }
            p.write(uu(len));
            while (s < ee) {
                p.write(uu(at(src, s) >> 2));
                p.write(uu(at(src, s) << 4 & 060 | at(src, s + 1) >> 4 & 017));
                p.write(uu(at(src, s + 1) << 2 & 074 | at(src, s + 2) >> 6 & 03));
                p.write(uu(at(src, s + 2) & 077));
                s += 3;
            }
            if (len == 45) {
                p.write('\n');
            }
        }
        if (s < e) {
            if (len == 45) {
                p.write(uu(e - s));
                len = 0;
            }
            p.write(uu(at(src, s) >> 2));
            p.write(uu(at(src, s) << 4 & 060 | at(src, s + 1) >> 4 & 017));
            p.write(e - s > 1 ? uu(at(src, s + 1) << 2 & 074 | at(src, s + 2) >> 6 & 03) : uu(0));
            p.write(e - s > 2 ? uu(at(src, s + 2) & 077) : uu(0));
        }
        if (len < 45) {
            p.write('\n');
        }
        p.write(uu(0));
        p.write('\n');
        return Str.of(p.toByteArray());
    }

    /** PHP_UU_DEC. */
    private static int unuu(byte[] s, int i) {
        return at(s, i) - ' ' & 077;
    }

    /** php_uudecode; false, with a warning, for a string that isn't uuencoded. */
    private static Object uudecode(byte[] src) {
        ByteBuilder p = new ByteBuilder(src.length);
        int totalLen = 0;
        int s = 0;
        int e = src.length;
        boolean valid = src.length > 0;
        while (valid && s < e) {
            int len = unuu(src, s++);
            if (len == 0) {
                break;
            }
            totalLen += len;
            int ee = s + (len == 45 ? 60 : (int) Math.floor(len * 1.33));
            if (len > src.length || ee > e) {
                valid = false;
                break;
            }
            while (s < ee) {
                if (s + 4 > e) {
                    valid = false;
                    break;
                }
                p.write(unuu(src, s) << 2 | unuu(src, s + 1) >> 4);
                p.write(unuu(src, s + 1) << 4 | unuu(src, s + 2) >> 2);
                p.write(unuu(src, s + 2) << 6 | unuu(src, s + 3));
                s += 4;
            }
            if (len < 45) {
                break;
            }
            s++;
        }
        if (!valid) {
            ErrorFunctions.raise(PhpContext.get(null), ErrorFunctions.E_WARNING,
                    Str.of("convert_uudecode(): Argument #1 ($data) is not a valid uuencoded string"),
                    SourceLocation.current());
            return Boolean.FALSE;
        }
        if (totalLen > p.size()) {
            p.write(unuu(src, s) << 2 | unuu(src, s + 1) >> 4);
            if (totalLen > 1) {
                p.write(unuu(src, s + 1) << 4 | unuu(src, s + 2) >> 2);
                if (totalLen > 2) {
                    p.write(unuu(src, s + 2) << 6 | unuu(src, s + 3));
                }
            }
        }
        byte[] out = p.toByteArray();
        return Str.of(out.length > totalLen ? Arrays.copyOf(out, totalLen) : out);
    }

    /** str_word_count: words of letters (C locale), ' and - (neither first, nor - last), and the given bytes. */
    private static Object wordCount(byte[] s, long format, boolean[] mask) {
        PhpArray words = new PhpArray();
        if (s.length == 0) {
            return format == 0 ? (Object) 0L : words;
        }
        int p = 0;
        int e = s.length;
        if (s[0] == '\'' && (mask == null || !mask['\'']) || s[0] == '-' && (mask == null || !mask['-'])) {
            p++;
        }
        if (s[e - 1] == '-' && (mask == null || !mask['-'])) {
            e--;
        }
        long count = 0;
        while (p < e) {
            int start = p;
            while (p < e && ((s[p] | 0x20) >= 'a' && (s[p] | 0x20) <= 'z' || mask != null && mask[s[p] & 0xFF]
                    || s[p] == '\'' || s[p] == '-')) {
                p++;
            }
            if (p > start) {
                if (format == 1) {
                    words.append(Str.of(s, start, p - start));
                } else if (format == 2) {
                    words.put((long) start, Str.of(s, start, p - start));
                } else {
                    count++;
                }
            }
            p++;
        }
        return format == 0 ? (Object) count : words;
    }

    private static final String[] LOCALE_CATEGORIES = {"LC_CTYPE", "LC_NUMERIC", "LC_TIME", "LC_COLLATE",
        "LC_MONETARY", "LC_MESSAGES", null, "LC_PAPER", "LC_NAME", "LC_ADDRESS", "LC_TELEPHONE", "LC_MEASUREMENT",
        "LC_IDENTIFICATION"};

    /**
     * try_setlocale_str on glibc with the locales the reference image has (C, POSIX, C.UTF-8): the locale's name,
     * "0" querying it; null if the category or the locale doesn't exist.
     */
    private static String setlocale(PhpContext context, String[] locales, long category, String name) {
        if (!name.equals("0") && name.length() >= 255) {
            ErrorFunctions.raise(context, ErrorFunctions.E_WARNING,
                    Str.of("setlocale(): Specified locale name is too long"), SourceLocation.current());
            return null;
        }
        if (category < 0 || category >= locales.length) {
            return null;
        }
        int cat = (int) category;
        if (!name.equals("0")) {
            String[] chosen = new String[locales.length];
            for (int c = 0; c < locales.length; c++) {
                if (c == 6 || cat != 6 && c != cat) {
                    continue;
                }
                String wanted = name;
                if (wanted.isEmpty()) {
                    // the environment: LC_ALL, then the category's variable, then LANG
                    Map<String, String> environment = RuntimeFunctions.environment(context);
                    for (String variable : new String[]{"LC_ALL", LOCALE_CATEGORIES[c], "LANG"}) {
                        String value = environment.get(variable);
                        if (value != null && !value.isEmpty()) {
                            wanted = value;
                            break;
                        }
                    }
                    wanted = wanted.isEmpty() ? "C" : wanted;
                }
                if (wanted.equals("POSIX") || wanted.equals("C")) {
                    chosen[c] = "C";
                } else if (wanted.matches("C\\.(?i:utf-?8)")) {
                    chosen[c] = wanted;
                } else {
                    return null;
                }
            }
            for (int c = 0; c < locales.length; c++) {
                if (chosen[c] != null) {
                    locales[c] = chosen[c];
                }
            }
        }
        if (cat != 6) {
            return locales[cat];
        }
        boolean same = true;
        StringBuilder composite = new StringBuilder();
        for (int c = 0; c < locales.length; c++) {
            if (c != 6) {
                same &= locales[c].equals(locales[0]);
                composite.append(c == 0 ? "" : ";").append(LOCALE_CATEGORIES[c]).append('=').append(locales[c]);
            }
        }
        return same ? locales[0] : composite.toString();
    }

    /** nl_langinfo()'s items (glibc's: the category in the high 16 bits) and their values in the C locale. */
    private static final Map<Long, String> LANGINFO = new java.util.HashMap<>();
    private static final Map<String, Long> LANGINFO_NAMES = new java.util.LinkedHashMap<>();

    static {
        String[] days = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
        String[] months = {"January", "February", "March", "April", "May", "June", "July", "August", "September",
            "October", "November", "December"};
        for (int i = 0; i < 7; i++) {
            langinfo("ABDAY_" + (i + 1), 131072 + i, days[i].substring(0, 3));
            langinfo("DAY_" + (i + 1), 131079 + i, days[i]);
        }
        for (int i = 0; i < 12; i++) {
            langinfo("ABMON_" + (i + 1), 131086 + i, months[i].substring(0, 3));
            langinfo("MON_" + (i + 1), 131098 + i, months[i]);
        }
        Object[] rest = {"AM_STR", 131110, "AM", "PM_STR", 131111, "PM", "D_T_FMT", 131112, "%a %b %e %H:%M:%S %Y",
            "D_FMT", 131113, "%m/%d/%y", "T_FMT", 131114, "%H:%M:%S", "T_FMT_AMPM", 131115, "%I:%M:%S %p",
            "ERA", 131116, "", "ERA_YEAR", 131117, "", "ERA_D_T_FMT", 131120, "", "ERA_D_FMT", 131118, "",
            "ERA_T_FMT", 131121, "", "ALT_DIGITS", 131119, "", "INT_CURR_SYMBOL", 262144, "",
            "CURRENCY_SYMBOL", 262145, "", "CRNCYSTR", 262159, "-", "MON_DECIMAL_POINT", 262146, "",
            "MON_THOUSANDS_SEP", 262147, "", "MON_GROUPING", 262148, "", "POSITIVE_SIGN", 262149, "",
            "NEGATIVE_SIGN", 262150, "", "INT_FRAC_DIGITS", 262151, "\u00ff", "FRAC_DIGITS", 262152, "\u00ff",
            "P_CS_PRECEDES", 262153, "\u00ff", "P_SEP_BY_SPACE", 262154, "\u00ff", "N_CS_PRECEDES", 262155, "\u00ff",
            "N_SEP_BY_SPACE", 262156, "\u00ff", "P_SIGN_POSN", 262157, "\u00ff", "N_SIGN_POSN", 262158, "\u00ff",
            "DECIMAL_POINT", 65536, ".", "RADIXCHAR", 65536, ".", "THOUSANDS_SEP", 65537, "", "THOUSEP", 65537, "",
            "GROUPING", 65538, "", "YESEXPR", 327680, "^[yY]", "NOEXPR", 327681, "^[nN]", "YESSTR", 327682, "",
            "NOSTR", 327683, "", "CODESET", 14, "ANSI_X3.4-1968"};
        for (int i = 0; i < rest.length; i += 3) {
            langinfo((String) rest[i], (Integer) rest[i + 1], (String) rest[i + 2]);
        }
    }

    private static void langinfo(String name, long item, String value) {
        LANGINFO.put(item, value);
        LANGINFO_NAMES.put(name, item);
    }

    static void langinfoConstants(Map<String, Object> constants) {
        constants.putAll(LANGINFO_NAMES);
    }

    /** metaphone.c's letter classes: 1 vowel (AEIOU), 2 FJMNR, 4 CGPST (affect H), 8 EIY (soften), 16 BDH. */
    private static final byte[] METAPHONE_CODES = {1, 16, 4, 16, 9, 2, 4, 16, 9, 2, 0, 2, 2, 2, 1, 4, 0, 2, 4, 4, 1, 0,
        0, 0, 8, 0};

    private static int encode(int c) {
        return c >= 'A' && c <= 'Z' ? METAPHONE_CODES[c - 'A'] : 0;
    }

    private static boolean isAlpha(int c) {
        return (c | 0x20) >= 'a' && (c | 0x20) <= 'z';
    }

    private static int upper(int c) {
        return c >= 'a' && c <= 'z' ? c - 32 : c;
    }

    /** metaphone.c (Michael G Schwern's), traditional: the word as a C string, to its first NUL. */
    private static TruffleString metaphone(byte[] word, long max) {
        int length = 0;
        while (length < word.length && word[length] != 0) {
            length++;
        }
        byte[] w = Arrays.copyOf(word, length + 3); // NULs after the end, as C reads them
        ByteBuilder out = new ByteBuilder(length);
        int i = 0;
        while (!isAlpha(w[i])) {
            if (w[i] == 0) {
                return Str.EMPTY;
            }
            i++;
        }
        int next = upper(w[i + 1]);
        switch (upper(w[i])) {
            case 'A' -> {
                out.write(next == 'E' ? 'E' : 'A');
                i += next == 'E' ? 2 : 1;
            }
            case 'G', 'K', 'P' -> {
                if (next == 'N') {
                    out.write('N');
                    i += 2;
                }
            }
            case 'W' -> {
                if (next == 'R') {
                    out.write('R');
                    i += 2;
                } else if (next == 'H' || (encode(next) & 1) != 0) {
                    out.write('W');
                    i += 2;
                }
            }
            case 'X' -> {
                out.write('S');
                i++;
            }
            case 'E', 'I', 'O', 'U' -> {
                out.write(upper(w[i]));
                i++;
            }
            default -> {
            }
        }
        for (; w[i] != 0 && (max == 0 || out.size() < max); i++) {
            int c = w[i];
            if (!isAlpha(c)) {
                continue;
            }
            c = upper(c);
            int prev = i >= 1 ? upper(w[i - 1]) : 0;
            if (c == prev && c != 'C') {
                continue;
            }
            next = upper(w[i + 1]);
            int afterNext = w[i + 1] != 0 ? upper(w[i + 2]) : 0;
            int skip = 0;
            switch (c) {
                case 'B' -> {
                    if (prev != 'M') {
                        out.write('B');
                    }
                }
                case 'C' -> {
                    if ((encode(next) & 8) != 0) {
                        if (next == 'I' && afterNext == 'A') {
                            out.write('X');
                        } else if (prev != 'S') {
                            out.write('S');
                        }
                    } else if (next == 'H') {
                        out.write('X');
                        skip++;
                    } else {
                        out.write('K');
                    }
                }
                case 'D' -> {
                    if (next == 'G' && (encode(afterNext) & 8) != 0) {
                        out.write('J');
                        skip++;
                    } else {
                        out.write('T');
                    }
                }
                case 'G' -> {
                    if (next == 'H') {
                        if (!((encode(i >= 3 ? upper(w[i - 3]) : 0) & 16) != 0 || (i >= 4 ? upper(w[i - 4]) : 0) == 'H')) {
                            out.write('F');
                            skip++;
                        }
                    } else if (next == 'N') {
                        if (!(!isAlpha(afterNext) || afterNext == 'E' && lookahead(w, i, 3) == 'D')) {
                            out.write('K');
                        }
                    } else if ((encode(next) & 8) != 0 && prev != 'G') {
                        out.write('J');
                    } else {
                        out.write('K');
                    }
                }
                case 'H' -> {
                    if ((encode(next) & 1) != 0 && (encode(prev) & 4) == 0) {
                        out.write('H');
                    }
                }
                case 'K' -> {
                    if (prev != 'C') {
                        out.write('K');
                    }
                }
                case 'P' -> out.write(next == 'H' ? 'F' : 'P');
                case 'Q' -> out.write('K');
                case 'S' -> {
                    if (next == 'I' && (afterNext == 'O' || afterNext == 'A')) {
                        out.write('X');
                    } else if (next == 'H') {
                        out.write('X');
                        skip++;
                    } else {
                        out.write('S');
                    }
                }
                case 'T' -> {
                    if (next == 'I' && (afterNext == 'O' || afterNext == 'A')) {
                        out.write('X');
                    } else if (next == 'H') {
                        out.write('0');
                        skip++;
                    } else if (!(next == 'C' && afterNext == 'H')) {
                        out.write('T');
                    }
                }
                case 'V' -> out.write('F');
                case 'W', 'Y' -> {
                    if ((encode(next) & 1) != 0) {
                        out.write(c);
                    }
                }
                case 'X' -> {
                    out.write('K');
                    out.write('S');
                }
                case 'Z' -> out.write('S');
                case 'F', 'J', 'L', 'M', 'N', 'R' -> out.write(c);
                default -> {
                }
            }
            i += skip;
        }
        return Str.of(out.toByteArray());
    }

    /** Lookahead: the letter how_far on, or the NUL before it. */
    private static int lookahead(byte[] w, int from, int howFar) {
        int i = 0;
        while (w[from + i] != 0 && i < howFar) {
            i++;
        }
        return upper(w[from + i]);
    }

    /** str_getcsv's separator and enclosure: one byte. */
    private static int csvChar(PhpContext context, Object[] a, int index, String name, int missing) {
        if (!Args.has(a, index)) {
            return missing;
        }
        byte[] c = Str.readBytes(Args.string(context, a, index, "str_getcsv", name, false));
        if (c.length != 1) {
            throw new PhpError("ValueError", "str_getcsv(): Argument #" + (index + 1) + " ($" + name
                    + ") must be a single character");
        }
        return c[0] & 0xFF;
    }

    /** php_mblen as php_fgetcsv calls it: 1 for a NUL, the character's length in a UTF-8 LC_CTYPE. */
    private static int csvLength(byte[] s, int i, int limit, boolean utf8) {
        return i >= limit ? 0 : s[i] == 0 || !utf8 ? 1 : ExecFunctions.mblen(s, i, limit);
    }

    /** php_fgetcsv_lookup_trailing_spaces: where a final \r\n, \r or \n of s[from, from + length) begins. */
    private static int csvTrailing(byte[] s, int from, int length, boolean utf8) {
        int last0 = 0;
        int last1 = 0;
        int p = from;
        int end = from + length;
        while (p < end) {
            int n = csvLength(s, p, end, utf8);
            if (n == 0) {
                break;
            }
            if (n < 0) {
                n = 1;
            } else {
                last0 = last1;
                last1 = s[p] & 0xFF;
            }
            p += n;
        }
        if (last1 == '\n' && last0 == '\r') {
            return p - 2;
        }
        return last1 == '\n' || last1 == '\r' ? p - 1 : p;
    }

    /** php_fgetcsv on a string (str_getcsv: no stream to read more lines from); null for a blank line. */
    private static PhpArray csv(byte[] string, byte delimiter, byte enclosure, int escape, boolean utf8) {
        byte[] buf = Arrays.copyOf(string, string.length + 1); // the NUL after the string, which C reads
        int limit = csvTrailing(buf, 0, string.length, utf8);
        int lineEnd = limit;
        int lineEndLength = string.length - limit;
        PhpArray values = new PhpArray();
        boolean firstField = true;
        int bptr = 0;
        int inc;
        do {
            ByteBuilder temp = new ByteBuilder();
            inc = csvLength(buf, bptr, limit, utf8);
            if (inc == 1) {
                int tmp = bptr;
                while (at(buf, tmp) != (delimiter & 0xFF) && isSpace(at(buf, tmp))) {
                    tmp++;
                }
                if (at(buf, tmp) == (enclosure & 0xFF) && tmp < limit) {
                    bptr = tmp;
                }
            }
            if (firstField && bptr == lineEnd) {
                return null;
            }
            firstField = false;
            int hunk;
            int compEnd;
            if (inc != 0 && at(buf, bptr) == (enclosure & 0xFF)) {
                int state = 0;
                bptr++;
                hunk = bptr;
                enclosed:
                while (true) {
                    if (inc == 0) {
                        if (state == 2) {
                            temp.write(buf, hunk, bptr - hunk - 1);
                            hunk = bptr;
                            break;
                        }
                        if (state == 1) {
                            temp.write(buf, hunk, bptr - hunk);
                            hunk = bptr;
                        }
                        if (hunk != lineEnd) {
                            temp.write(buf, hunk, bptr - hunk);
                            hunk = bptr;
                        }
                        temp.write(buf, lineEnd, lineEndLength);
                        if (bptr > limit) {
                            if (hunk == bptr) {
                                hunk--;
                            }
                            bptr--;
                        }
                        break;
                    } else if (inc < 0 || inc == 1) {
                        if (state == 1) {
                            bptr++;
                            state = 0;
                        } else if (state == 2) {
                            if (at(buf, bptr) != (enclosure & 0xFF)) {
                                temp.write(buf, hunk, bptr - hunk - 1);
                                hunk = bptr;
                                break enclosed;
                            }
                            temp.write(buf, hunk, bptr - hunk);
                            bptr++;
                            hunk = bptr;
                            state = 0;
                        } else {
                            if (at(buf, bptr) == (enclosure & 0xFF)) {
                                state = 2;
                            } else if (escape >= 0 && at(buf, bptr) == escape) {
                                state = 1;
                            }
                            bptr++;
                        }
                    } else {
                        if (state == 2) {
                            temp.write(buf, hunk, bptr - hunk - 1);
                            hunk = bptr;
                            break;
                        } else if (state == 1) {
                            bptr += inc;
                            temp.write(buf, hunk, bptr - hunk);
                            hunk = bptr;
                            state = 0;
                        } else {
                            bptr += inc;
                        }
                    }
                    inc = csvLength(buf, bptr, limit, utf8);
                }
                // the delimiter
                while (inc != 0) {
                    if (inc < 0) {
                        inc = 1;
                    }
                    if (inc == 1 && at(buf, bptr) == (delimiter & 0xFF)) {
                        break;
                    }
                    bptr += inc;
                    inc = csvLength(buf, bptr, limit, utf8);
                }
                temp.write(buf, hunk, bptr - hunk);
                bptr += inc;
                compEnd = temp.size();
            } else {
                hunk = bptr;
                while (inc != 0) {
                    if (inc < 0) {
                        inc = 1;
                    }
                    if (inc == 1 && at(buf, bptr) == (delimiter & 0xFF)) {
                        break;
                    }
                    bptr += inc;
                    inc = csvLength(buf, bptr, limit, utf8);
                }
                temp.write(buf, hunk, bptr - hunk);
                byte[] field = temp.toByteArray();
                compEnd = csvTrailing(field, 0, field.length, utf8);
                if (at(buf, bptr) == (delimiter & 0xFF)) {
                    bptr++;
                }
            }
            values.append(Str.of(Arrays.copyOf(temp.toByteArray(), compEnd)));
        } while (inc > 0);
        return values;
    }
}
