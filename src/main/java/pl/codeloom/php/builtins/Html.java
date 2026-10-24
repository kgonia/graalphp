package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * htmlspecialchars, htmlentities, their decoders and get_html_translation_table: a port of php-src's
 * ext/standard/html.c over its tables (HtmlTables), for every charset html.c knows.
 */
final class Html {
    static final int ENT_HTML_QUOTE_SINGLE = 1;
    static final int ENT_HTML_QUOTE_DOUBLE = 2;
    static final int ENT_HTML_IGNORE_ERRORS = 4;
    static final int ENT_HTML_SUBSTITUTE_ERRORS = 8;
    static final int ENT_HTML_DOC_TYPE_MASK = 16 | 32;
    static final int ENT_HTML_DOC_HTML401 = 0;
    static final int ENT_HTML_DOC_XML1 = 16;
    static final int ENT_HTML_DOC_XHTML = 32;
    static final int ENT_HTML_DOC_HTML5 = 16 | 32;
    static final int ENT_HTML_SUBSTITUTE_DISALLOWED_CHARS = 128;
    /** ENT_QUOTES | ENT_SUBSTITUTE | ENT_HTML401, the functions' default flags. */
    static final long DEFAULT_FLAGS = 3 | 8;

    private static final byte[] UTF8_REPLACEMENT = {(byte) 0xEF, (byte) 0xBF, (byte) 0xBD};
    private static final byte[] NUMERIC_REPLACEMENT = "&#xFFFD;".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] AMP = "&amp;".getBytes(StandardCharsets.ISO_8859_1);

    private Html() {
    }

    // ---- charsets ----

    private static boolean unicodeCompat(int cs) {
        return cs <= HtmlTables.CS_8859_1;
    }

    private static boolean singleByte(int cs) {
        return cs > HtmlTables.CS_UTF_8 && cs < HtmlTables.CS_BIG5;
    }

    private static boolean partialSupport(int cs) {
        return cs >= HtmlTables.CS_BIG5;
    }

    /**
     * determine_charset: the charset named by the hint (null or empty: default_charset, UTF-8), UTF-8 for a name
     * php-src doesn't know (it warns "Charset ... is not supported, assuming UTF-8").
     */
    private static int determineCharset(TruffleString hint) {
        if (hint == null || Str.length(hint) == 0) {
            return HtmlTables.CS_UTF_8;
        }
        byte[] bytes = Str.bytes(hint);
        if (bytes.length == 5 && (bytes[0] | 0x20) == 'u' && (bytes[1] | 0x20) == 't' && (bytes[2] | 0x20) == 'f'
                && bytes[3] == '-' && bytes[4] == '8') {
            return HtmlTables.CS_UTF_8;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] >= 'A' && bytes[i] <= 'Z') {
                bytes[i] += 'a' - 'A';
            }
        }
        int cs = HtmlTables.charset(new String(bytes, StandardCharsets.ISO_8859_1));
        return cs < 0 ? HtmlTables.CS_UTF_8 : cs;
    }

    private static boolean utf8Lead(int c) {
        return c < 0x80 || c >= 0xC2 && c <= 0xF4;
    }

    private static boolean utf8Trail(int c) {
        return c >= 0x80 && c <= 0xBF;
    }

    private static boolean gb2312Lead(int c) {
        return c != 0x8E && c != 0x8F && c != 0xA0 && c != 0xFF;
    }

    private static boolean gb2312Trail(int c) {
        return c >= 0xA1 && c <= 0xFE;
    }

    private static boolean sjisLead(int c) {
        return c != 0x80 && c != 0xA0 && c < 0xFD;
    }

    private static boolean sjisTrail(int c) {
        return c >= 0x40 && c != 0x7F && c < 0xFD;
    }

    /** A decoder for one string: get_next_char with its cursor and status. */
    private static final class Reader {
        final byte[] str;
        final int length;
        final int charset;
        int pos;
        boolean failure;

        Reader(byte[] str, int length, int charset) {
            this.str = str;
            this.length = length;
            this.charset = charset;
        }

        private int at(int i) {
            return str[i] & 0xFF;
        }

        private int fail(int at, int advance) {
            pos = at + advance;
            failure = true;
            return 0;
        }

        /** get_next_char: the character at pos, advancing pos; on an invalid sequence, failure and how far to skip. */
        int next() {
            failure = false;
            int p = pos;
            if (p >= length) {
                return fail(p, 1);
            }
            int c = at(p);
            int avail = length - p;
            switch (charset) {
                case HtmlTables.CS_UTF_8: {
                    if (c < 0x80) {
                        pos = p + 1;
                        return c;
                    } else if (c < 0xC2) {
                        return fail(p, 1);
                    } else if (c < 0xE0) {
                        if (avail < 2) {
                            return fail(p, 1);
                        }
                        if (!utf8Trail(at(p + 1))) {
                            return fail(p, utf8Lead(at(p + 1)) ? 1 : 2);
                        }
                        int ch = (c & 0x1F) << 6 | at(p + 1) & 0x3F;
                        if (ch < 0x80) {
                            return fail(p, 2);
                        }
                        pos = p + 2;
                        return ch;
                    } else if (c < 0xF0) {
                        if (avail < 3 || !utf8Trail(at(p + 1)) || !utf8Trail(at(p + 2))) {
                            if (avail < 2 || utf8Lead(at(p + 1))) {
                                return fail(p, 1);
                            } else if (avail < 3 || utf8Lead(at(p + 2))) {
                                return fail(p, 2);
                            }
                            return fail(p, 3);
                        }
                        int ch = (c & 0x0F) << 12 | (at(p + 1) & 0x3F) << 6 | at(p + 2) & 0x3F;
                        if (ch < 0x800 || ch >= 0xD800 && ch <= 0xDFFF) {
                            return fail(p, 3);
                        }
                        pos = p + 3;
                        return ch;
                    } else if (c < 0xF5) {
                        if (avail < 4 || !utf8Trail(at(p + 1)) || !utf8Trail(at(p + 2)) || !utf8Trail(at(p + 3))) {
                            if (avail < 2 || utf8Lead(at(p + 1))) {
                                return fail(p, 1);
                            } else if (avail < 3 || utf8Lead(at(p + 2))) {
                                return fail(p, 2);
                            } else if (avail < 4 || utf8Lead(at(p + 3))) {
                                return fail(p, 3);
                            }
                            return fail(p, 4);
                        }
                        int ch = (c & 0x07) << 18 | (at(p + 1) & 0x3F) << 12 | (at(p + 2) & 0x3F) << 6
                                | at(p + 3) & 0x3F;
                        if (ch < 0x10000 || ch > 0x10FFFF) {
                            return fail(p, 4);
                        }
                        pos = p + 4;
                        return ch;
                    }
                    return fail(p, 1);
                }
                case HtmlTables.CS_BIG5:
                    if (c >= 0x81 && c <= 0xFE) {
                        if (avail < 2) {
                            return fail(p, 1);
                        }
                        int next = at(p + 1);
                        if (next >= 0x40 && next <= 0x7E || next >= 0xA1 && next <= 0xFE) {
                            pos = p + 2;
                            return c << 8 | next;
                        }
                        return fail(p, 1);
                    }
                    pos = p + 1;
                    return c;
                case HtmlTables.CS_BIG5HKSCS:
                    if (c >= 0x81 && c <= 0xFE) {
                        if (avail < 2) {
                            return fail(p, 1);
                        }
                        int next = at(p + 1);
                        if (next >= 0x40 && next <= 0x7E || next >= 0xA1 && next <= 0xFE) {
                            pos = p + 2;
                            return c << 8 | next;
                        } else if (next != 0x80 && next != 0xFF) {
                            return fail(p, 1);
                        }
                        return fail(p, 2);
                    }
                    pos = p + 1;
                    return c;
                case HtmlTables.CS_GB2312:
                    if (c >= 0xA1 && c <= 0xFE) {
                        if (avail < 2) {
                            return fail(p, 1);
                        }
                        int next = at(p + 1);
                        if (gb2312Trail(next)) {
                            pos = p + 2;
                            return c << 8 | next;
                        } else if (gb2312Lead(next)) {
                            return fail(p, 1);
                        }
                        return fail(p, 2);
                    } else if (gb2312Lead(c)) {
                        pos = p + 1;
                        return c;
                    }
                    return fail(p, 1);
                case HtmlTables.CS_SJIS:
                    if (c >= 0x81 && c <= 0x9F || c >= 0xE0 && c <= 0xFC) {
                        if (avail < 2) {
                            return fail(p, 1);
                        }
                        int next = at(p + 1);
                        if (sjisTrail(next)) {
                            pos = p + 2;
                            return c << 8 | next;
                        } else if (sjisLead(next)) {
                            return fail(p, 1);
                        }
                        return fail(p, 2);
                    } else if (c < 0x80 || c >= 0xA1 && c <= 0xDF) {
                        pos = p + 1;
                        return c;
                    }
                    return fail(p, 1);
                case HtmlTables.CS_EUCJP:
                    if (c >= 0xA1 && c <= 0xFE) {
                        if (avail < 2) {
                            return fail(p, 1);
                        }
                        int next = at(p + 1);
                        if (next >= 0xA1 && next <= 0xFE) {
                            pos = p + 2;
                            return c << 8 | next;
                        }
                        return fail(p, next != 0xA0 && next != 0xFF ? 1 : 2);
                    } else if (c == 0x8E) {
                        if (avail < 2) {
                            return fail(p, 1);
                        }
                        int next = at(p + 1);
                        if (next >= 0xA1 && next <= 0xDF) {
                            pos = p + 2;
                            return c << 8 | next;
                        }
                        return fail(p, next != 0xA0 && next != 0xFF ? 1 : 2);
                    } else if (c == 0x8F) {
                        if (avail < 3 || !(at(p + 1) >= 0xA1 && at(p + 1) <= 0xFE)
                                || !(at(p + 2) >= 0xA1 && at(p + 2) <= 0xFE)) {
                            if (avail < 2 || at(p + 1) != 0xA0 && at(p + 1) != 0xFF) {
                                return fail(p, 1);
                            } else if (avail < 3 || at(p + 2) != 0xA0 && at(p + 2) != 0xFF) {
                                return fail(p, 2);
                            }
                            return fail(p, 3);
                        }
                        pos = p + 3;
                        return c << 16 | at(p + 1) << 8 | at(p + 2);
                    } else if (c != 0xA0 && c != 0xFF) {
                        pos = p + 1;
                        return c;
                    }
                    return fail(p, 1);
                default:
                    pos = p + 1;
                    return c;
            }
        }
    }

    /** map_from_unicode: the charset's code for a Unicode code point, or -1. */
    private static int mapFromUnicode(int code, int charset) {
        switch (charset) {
            case HtmlTables.CS_8859_1:
                return code > 0xFF ? -1 : code;
            case HtmlTables.CS_8859_5:
                if (code <= 0xA0 || code == 0xAD) {
                    return code;
                } else if (code == 0x2116) {
                    return 0xF0;
                } else if (code == 0xA7) {
                    return 0xFD;
                } else if (code >= 0x0401 && code <= 0x045F) {
                    if (code == 0x040D || code == 0x0450 || code == 0x045D) {
                        return -1;
                    }
                    return code - 0x360;
                }
                return -1;
            case HtmlTables.CS_8859_15:
                if (code < 0xA4 || code > 0xBE && code <= 0xFF) {
                    return code;
                }
                return found(HtmlTables.uniMap("iso885915").find(code));
            case HtmlTables.CS_CP1252:
                if (code <= 0x7F || code >= 0xA0 && code <= 0xFF) {
                    return code;
                }
                return found(HtmlTables.uniMap("win1252").find(code));
            case HtmlTables.CS_MACROMAN:
                if (code == 0x7F) {
                    return -1;
                }
                return code <= 0x7F ? code : found(HtmlTables.uniMap("macroman").find(code));
            case HtmlTables.CS_CP1251:
                return code <= 0x7F ? code : found(HtmlTables.uniMap("win1251").find(code));
            case HtmlTables.CS_KOI8R:
                return code <= 0x7F ? code : found(HtmlTables.uniMap("koi8r").find(code));
            case HtmlTables.CS_CP866:
                return code <= 0x7F ? code : found(HtmlTables.uniMap("cp866").find(code));
            case HtmlTables.CS_SJIS:
            case HtmlTables.CS_EUCJP:
                // 0x5C is the yen sign there
                return code >= 0x20 && code <= 0x7D && code != 0x5C ? code : -1;
            case HtmlTables.CS_BIG5:
            case HtmlTables.CS_BIG5HKSCS:
            case HtmlTables.CS_GB2312:
                return code >= 0x20 && code <= 0x7D ? code : -1;
            default:
                return -1;
        }
    }

    private static int found(int code) {
        return code == 0 ? -1 : code;
    }

    /** write_octet_sequence: UTF-8 for UTF-8, else the code as one byte. */
    private static void writeOctets(ByteBuilder out, int charset, int code) {
        if (charset != HtmlTables.CS_UTF_8) {
            out.write(code);
        } else if (code < 0x80) {
            out.write(code);
        } else if (code < 0x800) {
            out.write(0xC0 | code >> 6);
            out.write(0x80 | code & 0x3F);
        } else if (code < 0x10000) {
            out.write(0xE0 | code >> 12);
            out.write(0x80 | code >> 6 & 0x3F);
            out.write(0x80 | code & 0x3F);
        } else {
            out.write(0xF0 | code >> 18);
            out.write(0x80 | code >> 12 & 0x3F);
            out.write(0x80 | code >> 6 & 0x3F);
            out.write(0x80 | code & 0x3F);
        }
    }

    private static boolean unicodeCpIsAllowed(int cp, int doctype) {
        switch (doctype) {
            case ENT_HTML_DOC_HTML401:
                return cp >= 0x20 && cp <= 0x7E || cp == 0x0A || cp == 0x09 || cp == 0x0D
                        || cp >= 0xA0 && cp <= 0xD7FF || cp >= 0xE000 && cp <= 0x10FFFF;
            case ENT_HTML_DOC_HTML5:
                return cp >= 0x20 && cp <= 0x7E || cp >= 0x09 && cp <= 0x0D && cp != 0x0B
                        || cp >= 0xA0 && cp <= 0xD7FF
                        || cp >= 0xE000 && cp <= 0x10FFFF && (cp & 0xFFFF) < 0xFFFE && (cp < 0xFDD0 || cp > 0xFDEF);
            default:
                return cp >= 0x20 && cp <= 0xD7FF || cp == 0x0A || cp == 0x09 || cp == 0x0D
                        || cp >= 0xE000 && cp <= 0x10FFFF && cp != 0xFFFE && cp != 0xFFFF;
        }
    }

    private static boolean numericEntityIsAllowed(int cp, int doctype) {
        switch (doctype) {
            case ENT_HTML_DOC_HTML401:
                return cp <= 0x10FFFF;
            case ENT_HTML_DOC_HTML5:
                return cp >= 0x20 && cp <= 0x7E || cp >= 0x09 && cp <= 0x0C && cp != 0x0B
                        || cp >= 0xA0 && cp <= 0x10FFFF && (cp & 0xFFFF) < 0xFFFE && (cp < 0xFDD0 || cp > 0xFDEF);
            default:
                return unicodeCpIsAllowed(cp, doctype);
        }
    }

    private static int hexDigit(int c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        c |= 0x20;
        return c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
    }

    private static int byteAt(byte[] s, int length, int i) {
        return i < length ? s[i] & 0xFF : 0;
    }

    /**
     * process_numeric_entity on s from at (just after "&#"): -1, or the code point in the low 32 bits and the
     * position of the ';' in the high ones. Digits are read as strtol does, a "0x" after the 'x' included.
     */
    private static long processNumericEntity(byte[] s, int length, int at) {
        int p = at;
        boolean hexadecimal = byteAt(s, length, p) == 'x' || byteAt(s, length, p) == 'X';
        if (hexadecimal) {
            p++;
        }
        int first = byteAt(s, length, p);
        if (hexadecimal ? hexDigit(first) < 0 : first < '0' || first > '9') {
            return -1;
        }
        if (hexadecimal && first == '0' && (byteAt(s, length, p + 1) | 0x20) == 'x'
                && hexDigit(byteAt(s, length, p + 2)) >= 0) {
            // strtol(..., 16) takes a 0x prefix
            p += 2;
        }
        long code = 0;
        int base = hexadecimal ? 16 : 10;
        while (true) {
            int c = byteAt(s, length, p);
            int digit = hexadecimal ? hexDigit(c) : c >= '0' && c <= '9' ? c - '0' : -1;
            if (digit < 0) {
                break;
            }
            code = Math.min(code * base + digit, Long.MAX_VALUE / 16);
            p++;
        }
        if (byteAt(s, length, p) != ';' || code > 0x10FFFF) {
            return -1;
        }
        return (long) p << 32 | code;
    }

    // ---- htmlspecialchars, htmlentities ----

    /** main.c's escape_html for a displayed error: ENT_COMPAT, retried substituting invalid bytes if it fails. */
    static TruffleString escapeForError(TruffleString message) {
        TruffleString escaped = escape(message, false, ENT_HTML_QUOTE_DOUBLE, null, true);
        return Str.length(escaped) > 0 ? escaped
                : escape(message, false, ENT_HTML_QUOTE_DOUBLE | ENT_HTML_SUBSTITUTE_ERRORS, null, true);
    }

    /** htmlspecialchars (all false) and htmlentities (all true): php_escape_html_entities_ex. */
    @TruffleBoundary
    static TruffleString escape(TruffleString text, boolean all, long longFlags, TruffleString charsetHint,
                                boolean doubleEncode) {
        int oldlen = Str.length(text);
        if (oldlen == 0) {
            return Str.EMPTY;
        }
        byte[] old = Str.readBytes(text);
        int flags = (int) longFlags;
        int charset = determineCharset(charsetHint);
        int doctype = flags & ENT_HTML_DOC_TYPE_MASK;
        if (all) {
            // LIMIT_ALL (php-src notices that multi-byte charsets other than UTF-8 get only the basic entities)
            all = !partialSupport(charset) && doctype != ENT_HTML_DOC_XML1;
        }
        if (!all && charset == HtmlTables.CS_UTF_8 && (flags & ENT_HTML_SUBSTITUTE_DISALLOWED_CHARS) == 0) {
            return specialCharsUtf8(text, old, oldlen, flags, doctype, doubleEncode);
        }
        Map<Integer, HtmlTables.Entity> msTable = all ? HtmlTables.encodeTable(doctype == ENT_HTML_DOC_HTML5) : null;
        byte[][] basic = all ? null : HtmlTables.basicTable(doctype != ENT_HTML_DOC_HTML401);
        int[] toUni = all && !unicodeCompat(charset) ? HtmlTables.toUnicode(charset) : null;
        Map<String, int[]> invMap = doubleEncode ? null : HtmlTables.decodeTable(inverseMap(true, flags));
        byte[] replacement = null;
        if ((flags & (ENT_HTML_SUBSTITUTE_ERRORS | ENT_HTML_SUBSTITUTE_DISALLOWED_CHARS)) != 0) {
            replacement = charset == HtmlTables.CS_UTF_8 ? UTF8_REPLACEMENT : NUMERIC_REPLACEMENT;
        }
        ByteBuilder out = new ByteBuilder(oldlen + oldlen / 2 + 16);
        Reader reader = new Reader(old, oldlen, charset);
        while (reader.pos < oldlen) {
            int before = reader.pos;
            int thisChar = reader.next();
            if (reader.failure) {
                if ((flags & ENT_HTML_IGNORE_ERRORS) != 0) {
                    continue;
                } else if ((flags & ENT_HTML_SUBSTITUTE_ERRORS) != 0) {
                    out.write(replacement);
                    continue;
                }
                return Str.EMPTY;
            }
            byte[] mbsequence = old;
            int mbStart = before;
            int mbLength = reader.pos - before;
            if (thisChar != '&') {
                byte[] rep = null;
                boolean passThrough = thisChar == '\'' && (flags & ENT_HTML_QUOTE_SINGLE) == 0
                        || thisChar == '"' && (flags & ENT_HTML_QUOTE_DOUBLE) == 0;
                if (!passThrough) {
                    if (all) {
                        if (toUni != null) {
                            thisChar = toUni[thisChar];
                            if (thisChar == 0xFFFF) {
                                passThrough = true;
                            }
                        }
                        if (!passThrough) {
                            rep = findEntityForChar(thisChar, msTable, reader);
                        }
                    } else if (thisChar < 64) {
                        rep = basic[thisChar];
                    }
                }
                if (rep != null) {
                    out.write('&');
                    out.write(rep);
                    out.write(';');
                    continue;
                }
                if (!passThrough && (flags & ENT_HTML_SUBSTITUTE_DISALLOWED_CHARS) != 0) {
                    boolean disallowed;
                    if (unicodeCompat(charset) || toUni != null) {
                        // with toUni (htmlentities in a single-byte charset), thisChar is already Unicode
                        disallowed = !unicodeCpIsAllowed(thisChar, doctype);
                    } else {
                        // no Unicode mapping: assume 0x00-0x7D are the C0 controls and ASCII
                        disallowed = thisChar <= 0x7D && !unicodeCpIsAllowed(thisChar, doctype);
                    }
                    if (disallowed) {
                        mbsequence = replacement;
                        mbStart = 0;
                        mbLength = replacement.length;
                    }
                }
                out.write(mbsequence, mbStart, mbLength);
            } else if (doubleEncode) {
                out.write(AMP);
            } else {
                int entityLength = validEntityLength(old, oldlen, reader.pos, flags, doctype, invMap);
                if (entityLength < 0) {
                    out.write(AMP);
                } else {
                    out.write('&');
                    out.write(old, reader.pos, entityLength);
                    out.write(';');
                    reader.pos += entityLength + 1;
                }
            }
        }
        return Str.of(out.toByteArray());
    }

    /**
     * After an '&' at cursor - 1, without double encoding: the length of the entity body (between '&' and ';')
     * when it is a valid numeric or named entity, else -1.
     */
    private static int validEntityLength(byte[] old, int oldlen, int cursor, int flags, int doctype,
                                         Map<String, int[]> invMap) {
        if (byteAt(old, oldlen, cursor) == '#') {
            long numeric = processNumericEntity(old, oldlen, cursor + 1);
            if (numeric < 0) {
                return -1;
            }
            if ((flags & ENT_HTML_SUBSTITUTE_DISALLOWED_CHARS) != 0
                    && !numericEntityIsAllowed((int) numeric, doctype)) {
                return -1;
            }
            return (int) (numeric >>> 32) - cursor;
        }
        int end = cursor;
        while (true) {
            int c = byteAt(old, oldlen, end);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9')) {
                break;
            }
            end++;
        }
        if (byteAt(old, oldlen, end) != ';' || end == cursor) {
            return -1;
        }
        int length = end - cursor;
        String name = new String(old, cursor, length, StandardCharsets.ISO_8859_1);
        if (!invMap.containsKey(name) && !(doctype == ENT_HTML_DOC_XHTML && name.equals("apos"))) {
            // XHTML decodes with the HTML 4.01 map, which lacks &apos;
            return -1;
        }
        return length;
    }

    /** find_entity_for_char: the entity for k, looking at the next character for a two-code-point entity. */
    private static byte[] findEntityForChar(int k, Map<Integer, HtmlTables.Entity> table, Reader reader) {
        if (k >> 12 > 0x1D) {
            return null;
        }
        HtmlTables.Entity entity = table.get(k);
        if (entity == null) {
            return null;
        }
        if (!entity.ambiguous()) {
            return entity.name();
        }
        int before = reader.pos;
        if (reader.pos < reader.length) {
            int next = reader.next();
            if (!reader.failure) {
                for (int i = 0; i < entity.secondCps().length; i++) {
                    if (entity.secondCps()[i] == next) {
                        return entity.secondNames()[i];
                    }
                }
            }
        }
        reader.pos = before;
        reader.failure = false;
        return entity.name();
    }

    /**
     * htmlspecialchars over UTF-8 without ENT_DISALLOWED, the common case: the input is returned as is when
     * nothing needs escaping, and runs of plain bytes are copied at once.
     */
    private static TruffleString specialCharsUtf8(TruffleString text, byte[] old, int oldlen, int flags,
                                                  int doctype, boolean doubleEncode) {
        byte[][] basic = HtmlTables.basicTable(doctype != ENT_HTML_DOC_HTML401);
        ByteBuilder out = null;
        Reader reader = null;
        int copied = 0;
        int i = 0;
        while (i < oldlen) {
            int b = old[i] & 0xFF;
            byte[] rep;
            int consumed;
            if (b >= 0x80) {
                if (reader == null) {
                    reader = new Reader(old, oldlen, HtmlTables.CS_UTF_8);
                }
                reader.pos = i;
                reader.next();
                if (!reader.failure) {
                    i = reader.pos;
                    continue;
                }
                consumed = reader.pos - i;
                if ((flags & ENT_HTML_IGNORE_ERRORS) != 0) {
                    rep = null;
                } else if ((flags & ENT_HTML_SUBSTITUTE_ERRORS) != 0) {
                    rep = UTF8_REPLACEMENT;
                } else {
                    return Str.EMPTY;
                }
                if (out == null) {
                    out = new ByteBuilder(oldlen + 16);
                }
                out.write(old, copied, i - copied);
                if (rep != null) {
                    out.write(rep);
                }
                i += consumed;
                copied = i;
                continue;
            }
            if (b >= 64 || basic[b] == null
                    || b == '\'' && (flags & ENT_HTML_QUOTE_SINGLE) == 0
                    || b == '"' && (flags & ENT_HTML_QUOTE_DOUBLE) == 0) {
                i++;
                continue;
            }
            if (b == '&' && !doubleEncode) {
                int entityLength = validEntityLength(old, oldlen, i + 1, flags, doctype,
                        HtmlTables.decodeTable(inverseMap(true, flags)));
                if (entityLength >= 0) {
                    i += entityLength + 2;
                    continue;
                }
            }
            if (out == null) {
                out = new ByteBuilder(oldlen + 16);
            }
            out.write(old, copied, i - copied);
            out.write('&');
            out.write(basic[b]);
            out.write(';');
            i++;
            copied = i;
        }
        if (out == null) {
            return text;
        }
        out.write(old, copied, oldlen - copied);
        return Str.of(out.toByteArray());
    }

    // ---- decoding ----

    /** unescape_inverse_map. */
    private static int inverseMap(boolean all, int flags) {
        int doctype = flags & ENT_HTML_DOC_TYPE_MASK;
        if (all) {
            return switch (doctype) {
                case ENT_HTML_DOC_HTML401, ENT_HTML_DOC_XHTML -> HtmlTables.INV_HTML4;
                case ENT_HTML_DOC_HTML5 -> HtmlTables.INV_HTML5;
                default -> HtmlTables.INV_BE_APOS;
            };
        }
        return doctype == ENT_HTML_DOC_HTML401 ? HtmlTables.INV_BE_NOAPOS : HtmlTables.INV_BE_APOS;
    }

    /** html_entity_decode (all) and htmlspecialchars_decode: php_unescape_html_entities. */
    @TruffleBoundary
    static TruffleString unescape(TruffleString text, boolean all, long longFlags, TruffleString charsetHint) {
        byte[] s = Str.readBytes(text);
        int length = Str.length(text);
        int first = indexOf(s, length, 0, '&');
        if (first < 0) {
            return text;
        }
        int flags = (int) longFlags;
        int charset = all ? determineCharset(charsetHint) : HtmlTables.CS_8859_1;
        int doctype = flags & ENT_HTML_DOC_TYPE_MASK;
        Map<String, int[]> invMap = HtmlTables.decodeTable(inverseMap(all, flags));
        byte[][] basicApos = HtmlTables.basicTable(true);
        ByteBuilder out = new ByteBuilder(length);
        int current = 0;
        while (current < length) {
            int ampersand = indexOf(s, length, current, '&');
            if (ampersand < 0) {
                out.write(s, current, length - current);
                break;
            }
            out.write(s, current, ampersand - current);
            current = ampersand;
            if (length - current < 4) {
                out.write(s, current, length - current);
                break;
            }
            int code;
            int code2 = 0;
            int entityEnd;
            if (s[current + 1] == '#') {
                long numeric = processNumericEntity(s, length, current + 2);
                if (numeric < 0) {
                    out.write(s[current++]);
                    continue;
                }
                code = (int) numeric;
                entityEnd = (int) (numeric >>> 32);
                if (!all && (code > 63 || basicApos[code] == null)) {
                    // htmlspecialchars_decode decodes only the entities of &, <, >, " and '
                    out.write(s[current++]);
                    continue;
                }
                if (!unicodeCpIsAllowed(code, doctype) || doctype == ENT_HTML_DOC_HTML5 && code == 0x0D) {
                    out.write(s[current++]);
                    continue;
                }
            } else {
                int nameStart = current + 1;
                int maxSearch = Math.min(HtmlTables.LONGEST_ENTITY_LENGTH + 1, length - nameStart);
                int semicolon = indexOf(s, nameStart + maxSearch, nameStart, ';');
                if (semicolon < 0 || semicolon == nameStart) {
                    out.write(s[current++]);
                    continue;
                }
                int[] cps = invMap.get(new String(s, nameStart, semicolon - nameStart, StandardCharsets.ISO_8859_1));
                if (cps != null) {
                    code = cps[0];
                    code2 = cps[1];
                } else if (doctype == ENT_HTML_DOC_XHTML && semicolon - nameStart == 4 && s[nameStart] == 'a'
                        && s[nameStart + 1] == 'p' && s[nameStart + 2] == 'o' && s[nameStart + 3] == 's') {
                    // XHTML decodes with the HTML 4.01 map, which lacks &apos;
                    code = '\'';
                } else {
                    out.write(s[current++]);
                    continue;
                }
                entityEnd = semicolon;
            }
            boolean invalid = code == '\'' && (flags & ENT_HTML_QUOTE_SINGLE) == 0
                    || code == '"' && (flags & ENT_HTML_QUOTE_DOUBLE) == 0;
            if (!invalid && charset != HtmlTables.CS_UTF_8) {
                code = mapFromUnicode(code, charset);
                invalid = code < 0 || code2 != 0;
            }
            if (invalid) {
                // a complete entity that can't be decoded here stays as it is
                out.write(s, current, entityEnd - current);
                current = entityEnd;
                continue;
            }
            writeOctets(out, charset, code);
            if (code2 != 0) {
                writeOctets(out, charset, code2);
            }
            current = entityEnd + 1;
        }
        return Str.of(out.toByteArray());
    }

    private static int indexOf(byte[] s, int end, int from, int b) {
        for (int i = from; i < end; i++) {
            if (s[i] == b) {
                return i;
            }
        }
        return -1;
    }

    // ---- get_html_translation_table ----

    @TruffleBoundary
    static PhpArray translationTable(long table, long longFlags, TruffleString charsetHint) {
        int flags = (int) longFlags;
        int charset = determineCharset(charsetHint);
        int doctype = flags & ENT_HTML_DOC_TYPE_MASK;
        boolean all = table != 0 && !partialSupport(charset) && doctype != ENT_HTML_DOC_XML1;
        PhpArray result = new PhpArray();
        if (all) {
            Map<Integer, HtmlTables.Entity> msTable = HtmlTables.encodeTable(doctype == ENT_HTML_DOC_HTML5);
            if (unicodeCompat(charset)) {
                Integer[] codes = msTable.keySet().toArray(new Integer[0]);
                Arrays.sort(codes);
                int limit = singleByte(charset) ? 0x100 : 0x1E000;
                for (int code : codes) {
                    if (code >= limit || code == '\'' && (flags & ENT_HTML_QUOTE_SINGLE) == 0
                            || code == '"' && (flags & ENT_HTML_QUOTE_DOUBLE) == 0) {
                        continue;
                    }
                    writeRow(result, msTable.get(code), code, charset);
                }
            } else {
                int[] toUni = HtmlTables.toUnicode(charset);
                for (int i = 0; i <= 0xFF; i++) {
                    if (i == '\'' && (flags & ENT_HTML_QUOTE_SINGLE) == 0
                            || i == '"' && (flags & ENT_HTML_QUOTE_DOUBLE) == 0) {
                        continue;
                    }
                    HtmlTables.Entity entity = msTable.get(toUni[i]);
                    if (entity != null) {
                        writeRow(result, entity, i, charset);
                    }
                }
            }
        } else {
            byte[][] basic = HtmlTables.basicTable(doctype != ENT_HTML_DOC_HTML401);
            for (int j = 0; j < basic.length; j++) {
                if (basic[j] == null || j == '\'' && (flags & ENT_HTML_QUOTE_SINGLE) == 0
                        || j == '"' && (flags & ENT_HTML_QUOTE_DOUBLE) == 0) {
                    continue;
                }
                writeRow(result, new HtmlTables.Entity(basic[j], null, null), j, HtmlTables.CS_8859_1);
            }
        }
        return result;
    }

    /** write_s3row_data: the row's entities keyed by the character (two characters for a pair). */
    private static void writeRow(PhpArray result, HtmlTables.Entity entity, int code, int charset) {
        ByteBuilder key = new ByteBuilder(8);
        writeOctets(key, charset, code);
        byte[] first = key.toByteArray();
        if (entity.name() != null) {
            put(result, first, entity.name());
        }
        if (!entity.ambiguous()) {
            return;
        }
        for (int i = 0; i < entity.secondCps().length; i++) {
            int second = entity.secondCps()[i];
            if (!unicodeCompat(charset)) {
                second = mapFromUnicode(second, charset);
                if (second < 0) {
                    continue;
                }
            }
            ByteBuilder pair = new ByteBuilder(8);
            pair.write(first);
            writeOctets(pair, charset, second);
            put(result, pair.toByteArray(), entity.secondNames()[i]);
        }
    }

    private static void put(PhpArray result, byte[] key, byte[] name) {
        byte[] value = new byte[name.length + 2];
        value[0] = '&';
        System.arraycopy(name, 0, value, 1, name.length);
        value[value.length - 1] = ';';
        result.put(ArrayKey.of(Str.of(key)), Str.of(value));
    }
}
