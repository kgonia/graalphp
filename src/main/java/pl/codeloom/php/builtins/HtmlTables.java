package pl.codeloom.php.builtins;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * php-src's ext/standard/html_tables.h, loaded from html-tables.txt (extracted from the header by
 * tools/html-tables.php): the charsets html.c knows, single-byte charsets to and from Unicode, the code point to
 * entity tables of htmlentities and htmlspecialchars, and the entity to code point tables of the decoders. Each
 * group is a holder class, so a table is parsed on first use (class initialization makes that once and
 * thread-safe) and then read lock-free.
 */
final class HtmlTables {
    /** enum entity_charset. */
    static final int CS_UTF_8 = 0;
    static final int CS_8859_1 = 1;
    static final int CS_CP1252 = 2;
    static final int CS_8859_15 = 3;
    static final int CS_CP1251 = 4;
    static final int CS_8859_5 = 5;
    static final int CS_CP866 = 6;
    static final int CS_MACROMAN = 7;
    static final int CS_KOI8R = 8;
    static final int CS_BIG5 = 9;
    static final int CS_GB2312 = 10;
    static final int CS_BIG5HKSCS = 11;
    static final int CS_SJIS = 12;
    static final int CS_EUCJP = 13;

    /** The longest entity name, without & and ;. */
    static final int LONGEST_ENTITY_LENGTH = 31;

    private HtmlTables() {
    }

    /** A code point's entity (name without & and ;), or for an ambiguous row the entities of two-code-point pairs. */
    record Entity(byte[] name, int[] secondCps, byte[][] secondNames) {
        boolean ambiguous() {
            return secondCps != null;
        }
    }

    private static List<String[]> lines(String kind, String table) {
        List<String[]> result = new ArrayList<>();
        String text = new String(StringFunctions.resource("html-tables.txt"), StandardCharsets.ISO_8859_1);
        for (String line : text.split("\n")) {
            if (line.startsWith(kind + " ") && (table == null || line.startsWith(kind + " " + table + " "))) {
                result.add(line.split(" "));
            }
        }
        return result;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    // ---- charsets ----

    private static final class Charsets {
        /** charset_map by lowercase name (php-src compares case-insensitively). */
        static final Map<String, Integer> BY_NAME = load();
        /** enc_to_uni_index: per charset, the Unicode code point of each byte (0xFFFF unmapped), or null. */
        static final int[][] TO_UNICODE = loadToUnicode();

        private static Map<String, Integer> load() {
            Map<String, Integer> map = new HashMap<>();
            for (String[] line : lines("charset", null)) {
                map.put(line[1].toLowerCase(Locale.ROOT), Integer.parseInt(line[2], 16));
            }
            return map;
        }

        private static int[][] loadToUnicode() {
            int[][] tables = new int[CS_EUCJP + 1][];
            for (String[] line : lines("e2u", null)) {
                int[] cps = new int[256];
                for (int i = 0; i < 256; i++) {
                    cps[i] = Integer.parseInt(line[2 + i], 16);
                }
                tables[Integer.parseInt(line[1], 16)] = cps;
            }
            return tables;
        }
    }

    /** The charset for a name in charset_map, or -1. */
    static int charset(String name) {
        Integer cs = Charsets.BY_NAME.get(name.toLowerCase(Locale.ROOT));
        return cs == null ? -1 : cs;
    }

    /** enc_to_uni_index[charset], or null for UTF-8 and the multi-byte charsets. */
    static int[] toUnicode(int charset) {
        return Charsets.TO_UNICODE[charset];
    }

    /** A unimap_* table: Unicode code points ascending, with the charset's byte for each. */
    record UniMap(int[] codePoints, int[] bytes) {
        /** unimap_bsearch: the byte for a code point, or 0. */
        int find(int code) {
            if (code > 0xFFFF) {
                return 0;
            }
            int at = Arrays.binarySearch(codePoints, code);
            return at >= 0 ? bytes[at] : 0;
        }
    }

    private static final class UniMaps {
        static final Map<String, UniMap> TABLES = load();

        private static Map<String, UniMap> load() {
            Map<String, UniMap> map = new HashMap<>();
            for (String[] line : lines("u2e", null)) {
                int n = line.length - 2;
                int[] cps = new int[n];
                int[] bytes = new int[n];
                for (int i = 0; i < n; i++) {
                    String[] pair = line[2 + i].split(":");
                    cps[i] = Integer.parseInt(pair[0], 16);
                    bytes[i] = Integer.parseInt(pair[1], 16);
                }
                map.put(line[1], new UniMap(cps, bytes));
            }
            return map;
        }
    }

    static UniMap uniMap(String name) {
        return UniMaps.TABLES.get(name);
    }

    // ---- code point to entity ----

    private static Map<Integer, Entity> loadEncode(String table) {
        Map<Integer, Entity> map = new HashMap<>();
        for (String[] line : lines("enc", table)) {
            map.put(Integer.parseInt(line[2], 16), new Entity(ascii(line[3]), null, null));
        }
        for (String[] line : lines("encm", table)) {
            int n = line.length - 4;
            int[] seconds = new int[n];
            byte[][] names = new byte[n][];
            for (int i = 0; i < n; i++) {
                String[] pair = line[4 + i].split(":");
                seconds[i] = Integer.parseInt(pair[0], 16);
                names[i] = ascii(pair[1]);
            }
            map.put(Integer.parseInt(line[2], 16), new Entity(line[3].equals("-") ? null : ascii(line[3]), seconds,
                    names));
        }
        return map;
    }

    private static final class EncodeHtml5 {
        static final Map<Integer, Entity> TABLE = loadEncode("html5");
    }

    private static final class EncodeHtml4 {
        static final Map<Integer, Entity> TABLE = loadEncode("html4");
    }

    /** entity_ms_table_html5 or entity_ms_table_html4, by code point. */
    static Map<Integer, Entity> encodeTable(boolean html5) {
        return html5 ? EncodeHtml5.TABLE : EncodeHtml4.TABLE;
    }

    private static byte[][] loadBasic(String table) {
        byte[][] rows = new byte[64][];
        for (String[] line : lines("enc", table)) {
            rows[Integer.parseInt(line[2], 16)] = ascii(line[3]);
        }
        return rows;
    }

    private static final class Basic {
        static final byte[][] APOS = loadBasic("be_apos");
        static final byte[][] NOAPOS = loadBasic("be_noapos");
    }

    /** stage3_table_be_noapos_00000 (HTML 4.01) or stage3_table_be_apos_00000: 64 rows, entity names or null. */
    static byte[][] basicTable(boolean apos) {
        return apos ? Basic.APOS : Basic.NOAPOS;
    }

    // ---- entity to code points ----

    private static Map<String, int[]> loadDecode(String table) {
        Map<String, int[]> map = new HashMap<>();
        for (String[] line : lines("dec", table)) {
            map.put(line[2], new int[]{Integer.parseInt(line[3], 16), Integer.parseInt(line[4], 16)});
        }
        return map;
    }

    private static final class DecodeHtml5 {
        static final Map<String, int[]> TABLE = loadDecode("html5");
    }

    private static final class DecodeHtml4 {
        static final Map<String, int[]> TABLE = loadDecode("html4");
    }

    private static final class DecodeBasic {
        static final Map<String, int[]> APOS = loadDecode("be_apos");
        static final Map<String, int[]> NOAPOS = loadDecode("be_noapos");
    }

    static final int INV_HTML5 = 0;
    static final int INV_HTML4 = 1;
    static final int INV_BE_APOS = 2;
    static final int INV_BE_NOAPOS = 3;

    /** ent_ht_html5, ent_ht_html4, ent_ht_be_apos or ent_ht_be_noapos: entity name to {cp1, cp2 or 0}. */
    static Map<String, int[]> decodeTable(int which) {
        return switch (which) {
            case INV_HTML5 -> DecodeHtml5.TABLE;
            case INV_HTML4 -> DecodeHtml4.TABLE;
            case INV_BE_APOS -> DecodeBasic.APOS;
            default -> DecodeBasic.NOAPOS;
        };
    }
}
