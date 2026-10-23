package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * htmlspecialchars, html_entity_decode and get_html_translation_table for UTF-8, after php-src's
 * ext/standard/html.c. Named-entity tables are exported from php-src itself (tools/entity-tables.php).
 */
final class Html {
    private static final int ENT_HTML_QUOTE_SINGLE = 1;
    private static final int ENT_HTML_QUOTE_DOUBLE = 2;
    private static final int ENT_IGNORE = 4;
    private static final int ENT_SUBSTITUTE = 8;
    private static final int DOCTYPE_MASK = 16 | 32;
    private static final int HTML401 = 0;
    private static final int XML1 = 16;
    private static final int XHTML = 32;
    private static final int HTML5 = 48;

    private Html() {
    }

    /*
     * Per doctype, entity name to UTF-8 bytes in php-src's table order: one holder class each, so a table is
     * loaded on first use (the JVM's class initialization makes that once and thread-safe) and then read lock-free.
     */
    private static final class Html401 {
        static final Map<String, byte[]> TABLE = load("entities-html401.txt");
    }

    private static final class Xml1 {
        static final Map<String, byte[]> TABLE = load("entities-xml1.txt");
    }

    private static final class Xhtml {
        static final Map<String, byte[]> TABLE = load("entities-xhtml.txt");
    }

    private static final class Html5 {
        static final Map<String, byte[]> TABLE = load("entities-html5.txt");
    }

    private static Map<String, byte[]> table(int doctype) {
        return switch (doctype) {
            case XML1 -> Xml1.TABLE;
            case XHTML -> Xhtml.TABLE;
            case HTML5 -> Html5.TABLE;
            default -> Html401.TABLE;
        };
    }

    private static Map<String, byte[]> load(String file) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (String line : new String(StringFunctions.resource(file), StandardCharsets.UTF_8).split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                map.put(line.substring(0, tab), HexFormat.of().parseHex(line.substring(tab + 1)));
            }
        }
        return Collections.unmodifiableMap(map);
    }

    // ---- htmlspecialchars ----

    @TruffleBoundary
    static TruffleString specialChars(TruffleString text, long flags, boolean doubleEncode) {
        byte[] s = Str.readBytes(text);
        int doctype = (int) flags & DOCTYPE_MASK;
        ByteBuilder out = null;
        int i = 0;
        int copied = 0;
        while (i < s.length) {
            int b = s[i] & 0xFF;
            String replacement = null;
            int consumed = 1;
            if (b >= 0x80) {
                int length = JsonFunctions.Utf8.sequenceLength(s, i);
                if (length > 0) {
                    i += length;
                    continue;
                }
                if ((flags & ENT_IGNORE) != 0) {
                    replacement = "";
                } else if ((flags & ENT_SUBSTITUTE) != 0) {
                    replacement = "�";
                } else {
                    return Str.EMPTY;
                }
                consumed = invalidLength(s, i);
            } else if (b == '&') {
                if (!doubleEncode) {
                    int entity = entityLength(s, i, doctype);
                    if (entity > 0) {
                        i += entity;
                        continue;
                    }
                }
                replacement = "&amp;";
            } else if (b == '"' && (flags & ENT_HTML_QUOTE_DOUBLE) != 0) {
                replacement = "&quot;";
            } else if (b == '\'' && (flags & ENT_HTML_QUOTE_SINGLE) != 0) {
                replacement = doctype == HTML401 ? "&#039;" : "&apos;";
            } else if (b == '<') {
                replacement = "&lt;";
            } else if (b == '>') {
                replacement = "&gt;";
            }
            if (replacement == null) {
                i++;
                continue;
            }
            if (out == null) {
                out = new ByteBuilder(s.length + 16);
            }
            out.write(s, copied, i - copied);
            out.writeBytes(replacement.getBytes(StandardCharsets.UTF_8));
            i += consumed;
            copied = i;
        }
        if (out == null) {
            return text;
        }
        out.write(s, copied, s.length - copied);
        return Str.of(out.toByteArray());
    }

    /** How many bytes php-src's get_next_char skips for an invalid sequence: the lead plus valid continuations. */
    private static int invalidLength(byte[] s, int i) {
        int b0 = s[i] & 0xFF;
        int expected = b0 >= 0xF0 ? 4 : b0 >= 0xE0 ? 3 : b0 >= 0xC2 ? 2 : 1;
        int n = 1;
        while (n < expected && i + n < s.length && (s[i + n] & 0xC0) == 0x80) {
            n++;
        }
        return n == expected ? 1 : n;
    }

    /** Length of a valid entity reference "&...;" at i (for double_encode=false), else 0. */
    private static int entityLength(byte[] s, int i, int doctype) {
        int j = i + 1;
        if (j < s.length && s[j] == '#') {
            j++;
            boolean hex = j < s.length && (s[j] == 'x' || s[j] == 'X');
            if (hex) {
                j++;
            }
            int start = j;
            long code = 0;
            while (j < s.length && Character.digit(s[j], hex ? 16 : 10) >= 0) {
                code = code * (hex ? 16 : 10) + Character.digit(s[j], hex ? 16 : 10);
                if (code > 0x10FFFF) {
                    return 0;
                }
                j++;
            }
            if (j == start || j >= s.length || s[j] != ';' || !numericAllowed((int) code, doctype)) {
                return 0;
            }
            return j + 1 - i;
        }
        int start = j;
        while (j < s.length && (Character.isLetterOrDigit(s[j]) && s[j] >= 0)) {
            j++;
        }
        if (j == start || j >= s.length || s[j] != ';') {
            return 0;
        }
        String name = new String(s, start, j - start, StandardCharsets.ISO_8859_1);
        return table(doctype).containsKey(name) || isQuoteEntity(name, doctype) ? j + 1 - i : 0;
    }

    private static boolean isQuoteEntity(String name, int doctype) {
        return name.equals("amp") || name.equals("lt") || name.equals("gt") || name.equals("quot")
                || name.equals("apos") && doctype != HTML401;
    }

    private static boolean numericAllowed(int cp, int doctype) {
        switch (doctype) {
            case HTML401:
                return cp <= 0x10FFFF;
            case HTML5:
                return cp >= 0x20 && cp <= 0x7E || cp >= 0x09 && cp <= 0x0C && cp != 0x0B
                        || cp >= 0xA0 && cp <= 0xD7FF
                        || cp >= 0xE000 && cp <= 0x10FFFF && (cp & 0xFFFF) < 0xFFFE && (cp < 0xFDD0 || cp > 0xFDEF);
            default:
                return cp == 0x09 || cp == 0x0A || cp == 0x0D || cp >= 0x20 && cp <= 0xD7FF
                        || cp >= 0xE000 && cp <= 0x10FFFF && cp != 0xFFFE && cp != 0xFFFF;
        }
    }

    // ---- decoding ----

    /** html_entity_decode, or htmlspecialchars_decode when specialOnly. */
    @TruffleBoundary
    static TruffleString decode(TruffleString text, long flags, boolean specialOnly) {
        byte[] s = Str.readBytes(text);
        int doctype = (int) flags & DOCTYPE_MASK;
        if (indexOfAmp(s) < 0) {
            return text;
        }
        ByteBuilder out = new ByteBuilder(s.length);
        int i = 0;
        while (i < s.length) {
            if (s[i] != '&') {
                out.write(s[i++]);
                continue;
            }
            int semicolon = -1;
            for (int j = i + 1; j < s.length && j < i + 40; j++) {
                if (s[j] == ';') {
                    semicolon = j;
                    break;
                }
                if (s[j] == '&') {
                    break;
                }
            }
            if (semicolon < 0) {
                out.write(s[i++]);
                continue;
            }
            byte[] decoded = decodeEntity(new String(s, i + 1, semicolon - i - 1, StandardCharsets.ISO_8859_1),
                    flags, doctype, specialOnly);
            if (decoded == null) {
                out.write(s[i++]);
                continue;
            }
            out.writeBytes(decoded);
            i = semicolon + 1;
        }
        return Str.of(out.toByteArray());
    }

    private static int indexOfAmp(byte[] s) {
        for (int i = 0; i < s.length; i++) {
            if (s[i] == '&') {
                return i;
            }
        }
        return -1;
    }

    private static byte[] decodeEntity(String body, long flags, int doctype, boolean specialOnly) {
        if (body.startsWith("#")) {
            boolean hex = body.length() > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X');
            String digits = body.substring(hex ? 2 : 1);
            if (digits.isEmpty() || digits.length() > 8) {
                return null;
            }
            int cp;
            try {
                cp = Integer.parseInt(digits, hex ? 16 : 10);
            } catch (NumberFormatException e) {
                return null;
            }
            if (cp == '\'' && (flags & ENT_HTML_QUOTE_SINGLE) == 0 || cp == '"' && (flags & ENT_HTML_QUOTE_DOUBLE) == 0) {
                return null;
            }
            if (specialOnly && cp != '&' && cp != '<' && cp != '>' && cp != '"' && cp != '\'') {
                return null;
            }
            if (!numericAllowed(cp, doctype) || cp > 0x10FFFF) {
                return null;
            }
            return new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
        }
        switch (body) {
            case "amp":
                return new byte[]{'&'};
            case "lt":
                return new byte[]{'<'};
            case "gt":
                return new byte[]{'>'};
            case "quot":
                return (flags & ENT_HTML_QUOTE_DOUBLE) != 0 ? new byte[]{'"'} : null;
            case "apos":
                return (flags & ENT_HTML_QUOTE_SINGLE) != 0 && doctype != HTML401 ? new byte[]{'\''} : null;
            default:
                break;
        }
        if (specialOnly) {
            return null;
        }
        return table(doctype).get(body);
    }

    @TruffleBoundary
    static PhpArray translationTable(long table, long flags) {
        int doctype = (int) flags & DOCTYPE_MASK;
        PhpArray result = new PhpArray();
        if (table == 1) {
            for (Map.Entry<String, byte[]> entry : table(doctype).entrySet()) {
                String name = entry.getKey();
                if (name.equals("quot") && (flags & ENT_HTML_QUOTE_DOUBLE) == 0
                        || name.equals("apos") && (flags & ENT_HTML_QUOTE_SINGLE) == 0) {
                    continue;
                }
                result.put(pl.codeloom.php.runtime.ArrayKey.of(Str.of(entry.getValue())), Str.of("&" + name + ";"));
            }
            if ((flags & ENT_HTML_QUOTE_SINGLE) != 0 && doctype == HTML401) {
                result.put(Str.of("'"), Str.of("&#039;"));
            }
            return result;
        }
        if ((flags & ENT_HTML_QUOTE_DOUBLE) != 0) {
            result.put(Str.of("\""), Str.of("&quot;"));
        }
        result.put(Str.of("&"), Str.of("&amp;"));
        if ((flags & ENT_HTML_QUOTE_SINGLE) != 0) {
            result.put(Str.of("'"), Str.of(doctype == HTML401 ? "&#039;" : "&apos;"));
        }
        result.put(Str.of("<"), Str.of("&lt;"));
        result.put(Str.of(">"), Str.of("&gt;"));
        return result;
    }
}
