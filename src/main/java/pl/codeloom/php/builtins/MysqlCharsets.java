package pl.codeloom.php.builtins;

import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.Map;

/**
 * mysqlnd's client character sets as mysqli_real_escape_string() uses them (ext/mysqlnd/mysqlnd_charset.c): a
 * multibyte character valid in the connection's charset is copied unescaped, so its trail byte is never taken for
 * a quote or a backslash (`0xBF 0x27` in gbk), and a lead byte that starts no valid character is backslash-escaped.
 * The functions are ported as php-src has them, including that mysqlnd_mbcharlen gets the byte as a sign-extended
 * `char` (x86): the UTF-8 and GB18030 variants then never report a lead byte, the others mask it to a byte.
 */
final class MysqlCharsets {
    private MysqlCharsets() {
    }

    /** How a charset recognises multibyte characters: mysqlnd's mb_valid and mb_charlen pairs. */
    enum Encoding {
        SINGLE_BYTE(0x100), BIG5(0xA1), CP932(0x80), EUCKR(0x80), EUCJPMS(0x80), GB2312(0xA1), GBK(0x80), SJIS(0x80),
        UCS2(0x00), UJIS(0x80), UTF16(0x00), UTF32(0x00), GB18030(0x00), UTF8MB3(0x80), UTF8MB4(0x80);

        /** cset->lowest_mb_byte: bytes below it are never part of a multibyte character. */
        final int lowestMultibyte;

        Encoding(int lowestMultibyte) {
            this.lowestMultibyte = lowestMultibyte;
        }
    }

    /** mysqlnd_charsets by name (mysqlnd_find_charset_name; "utf8" is utf8mb3, as the first "utf8" entry). */
    private static final Map<String, Encoding> CHARSETS = Map.ofEntries(
            Map.entry("big5", Encoding.BIG5), Map.entry("cp932", Encoding.CP932), Map.entry("euckr", Encoding.EUCKR),
            Map.entry("eucjpms", Encoding.EUCJPMS), Map.entry("gb2312", Encoding.GB2312), Map.entry("gbk", Encoding.GBK),
            Map.entry("sjis", Encoding.SJIS), Map.entry("ucs2", Encoding.UCS2), Map.entry("ujis", Encoding.UJIS),
            Map.entry("utf16", Encoding.UTF16), Map.entry("utf16le", Encoding.UTF16),
            Map.entry("utf32", Encoding.UTF32), Map.entry("gb18030", Encoding.GB18030),
            Map.entry("utf8", Encoding.UTF8MB3), Map.entry("utf8mb4", Encoding.UTF8MB4),
            Map.entry("armscii8", Encoding.SINGLE_BYTE), Map.entry("ascii", Encoding.SINGLE_BYTE),
            Map.entry("binary", Encoding.SINGLE_BYTE), Map.entry("cp1250", Encoding.SINGLE_BYTE),
            Map.entry("cp1251", Encoding.SINGLE_BYTE), Map.entry("cp1256", Encoding.SINGLE_BYTE),
            Map.entry("cp1257", Encoding.SINGLE_BYTE), Map.entry("cp850", Encoding.SINGLE_BYTE),
            Map.entry("cp852", Encoding.SINGLE_BYTE), Map.entry("cp866", Encoding.SINGLE_BYTE),
            Map.entry("dec8", Encoding.SINGLE_BYTE), Map.entry("filename", Encoding.SINGLE_BYTE),
            Map.entry("geostd8", Encoding.SINGLE_BYTE), Map.entry("greek", Encoding.SINGLE_BYTE),
            Map.entry("hebrew", Encoding.SINGLE_BYTE), Map.entry("hp8", Encoding.SINGLE_BYTE),
            Map.entry("keybcs2", Encoding.SINGLE_BYTE), Map.entry("koi8r", Encoding.SINGLE_BYTE),
            Map.entry("koi8u", Encoding.SINGLE_BYTE), Map.entry("latin1", Encoding.SINGLE_BYTE),
            Map.entry("latin2", Encoding.SINGLE_BYTE), Map.entry("latin5", Encoding.SINGLE_BYTE),
            Map.entry("latin7", Encoding.SINGLE_BYTE), Map.entry("macce", Encoding.SINGLE_BYTE),
            Map.entry("macroman", Encoding.SINGLE_BYTE), Map.entry("swe7", Encoding.SINGLE_BYTE),
            Map.entry("tis620", Encoding.SINGLE_BYTE));

    /** The charset mysqlnd knows by this name (case-insensitive), or null. */
    static Encoding find(String name) {
        return CHARSETS.get(name.toLowerCase(Locale.ROOT));
    }

    /** mysqlnd_cset_escape_slashes: backslash before NUL, \n, \r, \, ', ", Ctrl-Z and invalid lead bytes. */
    static byte[] escapeSlashes(Encoding charset, byte[] s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length + 16);
        int i = 0;
        while (i < s.length) {
            byte b = s[i];
            int esc = 0;
            if ((b & 0xFF) >= charset.lowestMultibyte) {
                int length = valid(charset, s, i);
                if (length > 0) {
                    out.write(s, i, length);
                    i += length;
                    continue;
                }
                if (charLength(charset, b) > 1) {
                    esc = b;
                }
            }
            if (esc == 0) {
                esc = switch (b) {
                    case 0 -> '0';
                    case '\n' -> 'n';
                    case '\r' -> 'r';
                    case '\\', '\'', '"' -> b;
                    case 0x1A -> 'Z';
                    default -> 0;
                };
            }
            if (esc != 0) {
                out.write('\\');
                out.write(esc);
            } else {
                out.write(b);
            }
            i++;
        }
        return out.toByteArray();
    }

    /** mysqlnd_cset_escape_quotes, for NO_BACKSLASH_ESCAPES: only ' is escaped, by doubling it. */
    static byte[] escapeQuotes(Encoding charset, byte[] s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length + 16);
        int i = 0;
        while (i < s.length) {
            byte b = s[i];
            int length = (b & 0xFF) >= charset.lowestMultibyte ? valid(charset, s, i) : 0;
            if (length > 0) {
                out.write(s, i, length);
                i += length;
                continue;
            }
            out.write(b);
            if (b == '\'') {
                out.write('\'');
            }
            i++;
        }
        return out.toByteArray();
    }

    /** The byte at an index, or the NUL a zend_string ends with past its end (mysqlnd's ujis reads one past). */
    private static int at(byte[] s, int i) {
        return i < s.length ? s[i] & 0xFF : 0;
    }

    /** mb_valid: the length of the valid multibyte character starting at i, or 0. */
    private static int valid(Encoding charset, byte[] s, int i) {
        int left = s.length - i;
        int c = s[i] & 0xFF;
        int d = at(s, i + 1);
        return switch (charset) {
            case SINGLE_BYTE -> 0;
            case BIG5 -> c >= 0xA1 && c <= 0xF9 && left > 1 && (d >= 0x40 && d <= 0x7E || d >= 0xA1 && d <= 0xFE)
                    ? 2 : 0;
            case CP932, SJIS -> (c >= 0x81 && c <= 0x9F || c >= 0xE0 && c <= 0xFC) && left > 1
                    && (d >= 0x40 && d <= 0x7E || d >= 0x80 && d <= 0xFC) ? 2 : 0;
            case EUCKR -> left > 1 && c >= 0x80 && d >= 0xA1 && d <= 0xFE ? 2 : 0;
            case EUCJPMS -> {
                if (c < 0x80) {
                    yield 0;
                }
                if (c >= 0xA1 && c <= 0xFE && left > 1 && d >= 0xA1 && d <= 0xFE) {
                    yield 2;
                }
                if (c == 0x8E && left > 1 && d >= 0xA1 && d <= 0xDF) {
                    yield 2;
                }
                int e = at(s, i + 2);
                yield c == 0x8F && left > 2 && d >= 0xA1 && d <= 0xFE && e >= 0xA1 && e <= 0xFE ? 3 : 0;
            }
            case GB2312 -> c >= 0xA1 && c <= 0xF7 && left > 1 && d >= 0xA1 && d <= 0xFE ? 2 : 0;
            case GBK -> c >= 0x81 && c <= 0xFE && left > 1 && (d >= 0x40 && d <= 0x7E || d >= 0x80 && d <= 0xFE)
                    ? 2 : 0;
            case UCS2 -> 2;
            case UJIS -> {
                if (c < 0x80) {
                    yield 0;
                }
                if (c >= 0xA1 && c <= 0xFE && d >= 0xA1 && d <= 0xFE) {
                    yield 2;
                }
                if (c == 0x8E && d >= 0xA1 && d <= 0xDF) {
                    yield 2;
                }
                int e = at(s, i + 2);
                yield c == 0x8F && left > 2 && d >= 0xA1 && d <= 0xFE && e >= 0xA1 && e <= 0xFE ? 3 : 0;
            }
            case UTF16 -> {
                if (left < 2) {
                    yield 0;
                }
                if ((c & 0xFC) == 0xD8) {
                    yield left >= 4 && (at(s, i + 2) & 0xFC) == 0xDC ? 4 : 0;
                }
                yield (c & 0xFC) == 0xDC ? 0 : 2;
            }
            case UTF32 -> 4;
            case GB18030 -> {
                if (left <= 1 || c < 0x81 || c > 0xFE) {
                    yield 0;
                }
                if (d >= 0x40 && d <= 0x7E || d >= 0x80 && d <= 0xFE) {
                    yield 2;
                }
                int e = at(s, i + 2);
                int f = at(s, i + 3);
                yield left > 3 && d >= 0x30 && d <= 0x39 && e >= 0x81 && e <= 0xFE && f >= 0x30 && f <= 0x39 ? 4 : 0;
            }
            case UTF8MB3, UTF8MB4 -> {
                int length = utf8Sequence(s, i, charset == Encoding.UTF8MB4);
                yield length > 1 ? length : 0;
            }
        };
    }

    /** check_mb_utf8(mb3)_sequence: the length of the UTF-8 character at i, 0 if invalid. */
    private static int utf8Sequence(byte[] s, int i, boolean fourBytes) {
        int left = s.length - i;
        int c = s[i] & 0xFF;
        if (c < 0x80) {
            return 1;
        }
        if (c < 0xC2) {
            return 0;
        }
        if (c < 0xE0) {
            return left >= 2 && ((at(s, i + 1) ^ 0x80) < 0x40) ? 2 : 0;
        }
        if (c < 0xF0) {
            int d = at(s, i + 1);
            return left >= 3 && (d ^ 0x80) < 0x40 && (at(s, i + 2) ^ 0x80) < 0x40 && (c >= 0xE1 || d >= 0xA0)
                    ? 3 : 0;
        }
        if (fourBytes && c < 0xF5) {
            int d = at(s, i + 1);
            return left >= 4 && (d ^ 0x80) < 0x40 && (at(s, i + 2) ^ 0x80) < 0x40 && (at(s, i + 3) ^ 0x80) < 0x40
                    && (c >= 0xF1 || d >= 0x90) && (c <= 0xF3 || d <= 0x8F) ? 4 : 0;
        }
        return 0;
    }

    /**
     * mb_charlen(*escapestr): the character length a lead byte announces. The byte arrives sign-extended, as C's
     * `char` to `unsigned int` on x86, so a byte from 0x80 up is 0xFFFFFF80.. unless the function masks it.
     */
    private static int charLength(Encoding charset, byte b) {
        long c = b & 0xFFFFFFFFL;
        int u = b & 0xFF;
        return switch (charset) {
            case SINGLE_BYTE -> 1;
            case BIG5 -> u >= 0xA1 && u <= 0xF9 ? 2 : 1;
            case CP932, SJIS -> u >= 0x81 && u <= 0x9F || u >= 0xE0 && u <= 0xFC ? 2 : 1;
            case EUCKR -> u >= 0xA1 && u <= 0xFE ? 2 : 1;
            case EUCJPMS, UJIS -> u >= 0xA1 && u <= 0xFE || u == 0x8E ? 2 : u == 0x8F ? 3 : 1;
            case GB2312 -> u >= 0xA1 && u <= 0xF7 ? 2 : 1;
            case GBK -> u >= 0x81 && u <= 0xFE ? 2 : 1;
            case UCS2 -> 2;
            case UTF16 -> (u & 0xFC) == 0xD8 ? 4 : 2;
            case UTF32 -> 4;
            // c <= 0xFF only for ASCII once sign-extended; anything above 0xFFFF is 0
            case GB18030 -> c <= 0xFF ? (u >= 0x81 && u <= 0xFE ? 0 : 1) : 0;
            case UTF8MB3, UTF8MB4 -> c < 0x80 ? 1 : 0;
        };
    }
}
