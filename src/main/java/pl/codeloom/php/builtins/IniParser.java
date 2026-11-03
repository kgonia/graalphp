package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * parse_ini_string() and parse_ini_file(): a port of php-src's Zend/zend_ini_scanner.l (each state's rules matched
 * as re2c does, the longest match and the first rule on a tie) and of Zend/zend_ini_parser.y, run on the tables
 * Bison generated for it (zend_ini_parser.c), so the syntax errors are Bison's own. Strings are bytes held in Java
 * strings, one char per byte (ISO-8859-1).
 */
final class IniParser {
    static final int NORMAL = 0;
    static final int RAW = 1;
    static final int TYPED = 2;

    // the tokens' numbers, as the scanner returns them (zend_ini_parser.h)
    private static final int TC_SECTION = 258;
    private static final int TC_RAW = 259;
    private static final int TC_CONSTANT = 260;
    private static final int TC_NUMBER = 261;
    private static final int TC_STRING = 262;
    private static final int TC_WHITESPACE = 263;
    private static final int TC_LABEL = 264;
    private static final int TC_OFFSET = 265;
    private static final int TC_DOLLAR_CURLY = 266;
    private static final int TC_VARNAME = 267;
    private static final int TC_QUOTED_STRING = 268;
    private static final int TC_FALLBACK = 269;
    private static final int BOOL_TRUE = 270;
    private static final int BOOL_FALSE = 271;
    private static final int NULL_NULL = 272;
    private static final int END_OF_LINE = 273;

    // the scanner's states
    private static final int INITIAL = 0;
    private static final int ST_OFFSET = 1;
    private static final int ST_SECTION_VALUE = 2;
    private static final int ST_VALUE = 3;
    private static final int ST_SECTION_RAW = 4;
    private static final int ST_DOUBLE_QUOTES = 5;
    private static final int ST_VARNAME = 6;
    private static final int ST_VAR_FALLBACK = 7;
    private static final int ST_RAW = 8;

    // zend_ini_parser.c's tables
    private static final int YYFINAL = 2;
    private static final int YYLAST = 143;
    private static final int YYNTOKENS = 45;
    private static final int YYPACT_NINF = -46;
    /** The characters yytranslate maps to symbols 19 on; any other character is symbol 2 ("invalid token"). */
    private static final String CHAR_TOKENS = "=:,.\"'^+-/*%$~<>?@{}|&!]()";
    private static final String[] YYTNAME = {"end of file", "error", "invalid token", "TC_SECTION", "TC_RAW",
        "TC_CONSTANT", "TC_NUMBER", "TC_STRING", "TC_WHITESPACE", "TC_LABEL", "TC_OFFSET", "TC_DOLLAR_CURLY",
        "TC_VARNAME", "TC_QUOTED_STRING", "TC_FALLBACK", "BOOL_TRUE", "BOOL_FALSE", "NULL_NULL", "END_OF_LINE",
        "'='", "':'", "','", "'.'", "'\"'", "'\\''", "'^'", "'+'", "'-'", "'/'", "'*'", "'%'", "'$'", "'~'", "'<'",
        "'>'", "'?'", "'@'", "'{'", "'}'", "'|'", "'&'", "'!'", "']'", "'('", "')'"};
    private static final byte[] YYPACT = {-46, 118, -46, 73, -17, 81, -46, -46, -46, -46, -46, -46, -46, 0, -46,
        -34, 94, -46, -46, -1, -46, -46, -46, -46, -46, -46, -31, 102, -46, -46, 6, 59, -46, -46, -46, -46, -46, -46,
        -46, -46, 28, 28, 28, -46, 102, 25, 80, 2, -46, -46, -46, 81, -46, -46, -46, -46, 109, -46, -46, 72, 28, 28,
        28, -46, -1, 120, 102, -20, -46, -46, -46, -46, -46, -46, -46, -46};
    private static final byte[] YYDEFACT = {3, 0, 1, 10, 7, 17, 8, 2, 45, 44, 46, 47, 48, 0, 20, 0, 9, 21, 22, 0,
        50, 49, 51, 52, 53, 20, 0, 16, 27, 28, 0, 0, 4, 20, 24, 25, 12, 13, 14, 15, 0, 0, 0, 5, 33, 11, 0, 0, 20, 30,
        31, 43, 40, 19, 23, 18, 0, 37, 38, 0, 0, 0, 0, 29, 0, 0, 42, 0, 26, 39, 36, 34, 35, 6, 32, 41};
    private static final byte[] YYPGOTO = {-46, -46, -46, -46, -45, -46, 4, -46, -4, 14, -3, -46, 7, -18};
    private static final byte[] YYDEFGOTO = {0, 1, 7, 15, 43, 26, 31, 16, 44, 45, 28, 67, 18, 29};
    private static final byte[] YYTABLE = {17, 27, 19, 20, 21, 22, 23, 24, 32, 50, 13, 47, 30, 34, 36, 37, 38, 39,
        75, 73, 51, 64, 25, 35, 49, 0, 50, 0, 55, 46, 0, 40, 20, 21, 22, 23, 24, 56, 0, 13, 41, 49, 42, 55, 52, 0, 0,
        66, 50, 0, 60, 25, 65, 55, 57, 58, 59, 0, 0, 0, 40, 0, 55, 49, 61, 62, 0, 0, 0, 41, 13, 42, 53, 0, 70, 71, 72,
        8, 9, 10, 11, 12, 54, 0, 13, 20, 21, 22, 23, 24, 0, 13, 13, 53, 0, 0, 14, 60, 8, 9, 10, 11, 12, 63, 25, 13,
        20, 21, 22, 23, 24, 61, 62, 13, 0, 0, 69, 33, 2, 0, 13, 3, 53, 0, 0, 48, 0, 4, 5, 0, 0, 13, 68, 53, 0, 0, 6,
        0, 0, 0, 0, 0, 0, 74};
    private static final byte[] YYCHECK = {3, 5, 19, 4, 5, 6, 7, 8, 42, 27, 11, 42, 12, 16, 15, 16, 17, 18, 38, 64,
        14, 19, 23, 16, 27, -1, 44, -1, 31, 25, -1, 32, 4, 5, 6, 7, 8, 33, -1, 11, 41, 44, 43, 46, 38, -1, -1, 51,
        66, -1, 25, 23, 48, 56, 40, 41, 42, -1, -1, -1, 32, -1, 65, 66, 39, 40, -1, -1, -1, 41, 11, 43, 13, -1, 60,
        61, 62, 4, 5, 6, 7, 8, 23, -1, 11, 4, 5, 6, 7, 8, -1, 11, 11, 13, -1, -1, 23, 25, 4, 5, 6, 7, 8, 23, 23, 11,
        4, 5, 6, 7, 8, 39, 40, 11, -1, -1, 44, 23, 0, -1, 11, 3, 13, -1, -1, 23, -1, 9, 10, -1, -1, 11, 23, 13, -1,
        -1, 18, -1, -1, -1, -1, -1, -1, 23};
    private static final byte[] YYR1 = {0, 45, 46, 46, 47, 47, 47, 47, 47, 48, 48, 49, 49, 49, 49, 49, 50, 50, 51,
        51, 51, 52, 52, 52, 52, 52, 52, 53, 53, 53, 53, 53, 53, 54, 54, 54, 54, 54, 54, 54, 55, 55, 56, 56, 57, 57, 57,
        57, 57, 58, 58, 58, 58, 58};
    private static final byte[] YYR2 = {0, 2, 2, 0, 3, 3, 5, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 0, 2, 2, 0, 1, 1, 3, 2,
        2, 4, 1, 1, 3, 2, 2, 4, 1, 3, 3, 3, 2, 2, 3, 3, 5, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};

    private final PhpContext context;
    private final byte[] s;
    private final int limit;
    private final int mode;
    private final String filename;
    private final boolean sections;
    private final PhpArray result = new PhpArray();
    /** BG(active_ini_file_section): the section the entries go to; null before the first. */
    private PhpArray section;

    private int cursor;
    private int state = INITIAL;
    private final ArrayDeque<Integer> states = new ArrayDeque<>();
    private int lineno = 1;
    /** The token's value (ini_lval): a String, or with INI_SCANNER_TYPED a Boolean or null for BOOL_* and NULL_NULL. */
    private Object lval;

    private IniParser(PhpContext context, byte[] s, int limit, int mode, String filename, boolean sections) {
        this.context = context;
        this.s = s;
        this.limit = limit;
        this.mode = mode;
        this.filename = filename;
        this.sections = sections;
    }

    /** zend_parse_ini_string / zend_parse_ini_file with php_simple_ini_parser_cb or its with_sections form. */
    @TruffleBoundary
    static Object parse(PhpContext context, byte[] text, boolean sections, long mode, String filename) {
        if (mode != NORMAL && mode != RAW && mode != TYPED) {
            ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("Invalid scanner mode"),
                    SourceLocation.current());
            return Boolean.FALSE;
        }
        int limit = text.length;
        if (filename == null) {
            // zend_ini_prepare_string_for_scanning: the string ends at its first NUL (strlen)
            for (limit = 0; limit < text.length && text[limit] != 0; limit++) {
            }
        }
        IniParser parser = new IniParser(context, text, limit, (int) mode, filename == null ? "Unknown" : filename,
                sections);
        return parser.parse() ? parser.result : Boolean.FALSE;
    }

    // ---- the scanner ----

    /** The byte at i, 0 past the end (the input is followed by NULs: ZEND_MMAP_AHEAD). */
    private int at(int i) {
        return i < limit ? s[i] & 0xFF : 0;
    }

    private String text(int from, int to) {
        return new String(s, from, to - from, StandardCharsets.ISO_8859_1);
    }

    private int tabsAndSpaces(int p) {
        int i = p;
        while (at(i) == ' ' || at(i) == '\t') {
            i++;
        }
        return i - p;
    }

    private int newline(int p) {
        return at(p) == '\r' ? at(p + 1) == '\n' ? 2 : 1 : at(p) == '\n' ? 1 : 0;
    }

    private static boolean labelChar(int c) {
        return c != 0 && "=\n\r\t;&|^$~(){}!\"[]".indexOf(c) < 0;
    }

    private int label(int p) {
        int i = p;
        while (i < limit && labelChar(at(i))) {
            i++;
        }
        return i - p;
    }

    /** One of the words (case-insensitive: re2c --case-inverted), the longest, then {TABS_AND_SPACES}*. */
    private int keyword(int p, String... words) {
        int best = 0;
        for (String word : words) {
            if (word.length() > best && p + word.length() <= limit
                    && text(p, p + word.length()).equalsIgnoreCase(word)) {
                best = word.length();
            }
        }
        return best == 0 ? 0 : best + tabsAndSpaces(p + best);
    }

    private int constant(int p) {
        int i = p;
        while (i < limit && (Character.isLetter(at(i)) && at(i) < 128 || at(i) == '_' || i > p && isDigit(at(i)))) {
            i++;
        }
        return i - p;
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private int digits(int p) {
        int i = p;
        while (i < limit && isDigit(at(i))) {
            i++;
        }
        return i - p;
    }

    /** NUMBER: [-]?{LNUM}|{DNUM}. */
    private int number(int p) {
        int sign = at(p) == '-' ? 1 : 0;
        int integer = digits(p + sign);
        int best = integer > 0 ? sign + integer : 0;
        int whole = digits(p);
        if (at(p + whole) == '.') {
            int fraction = digits(p + whole + 1);
            if (whole > 0 || fraction > 0) {
                best = Math.max(best, whole + 1 + fraction);
            }
        }
        return best;
    }

    /** LITERAL_DOLLAR: "$" then a byte but "{" and NUL, or "\" and any byte. */
    private int literalDollar(int p) {
        if (p + 1 >= limit || at(p + 1) == '{' || at(p + 1) == 0) {
            return 0;
        }
        return at(p + 1) == '\\' && p + 2 < limit ? 3 : 2;
    }

    /**
     * VALUE_CHARS+ (excluded the bytes of VALUE_CHARS' class, backslashes taking the next byte false),
     * FALLBACK_CHARS+ and SECTION_VALUE_CHARS+ (backslashes taking the next byte true).
     */
    private int chars(int p, String excluded, boolean escapes) {
        int i = p;
        while (i < limit) {
            int c = at(i);
            if (c == '$') {
                int n = literalDollar(i);
                if (n == 0) {
                    break;
                }
                i += n;
            } else if (escapes && c == '\\') {
                if (i + 1 >= limit) {
                    break;
                }
                i += 2;
            } else if (excluded.indexOf(c) >= 0 || !escapes && c == 0) {
                break;
            } else {
                i++;
            }
        }
        return i - p;
    }

    /** "'"{SINGLE_QUOTED_CHARS}+"'". */
    private int singleQuoted(int p) {
        if (at(p) != '\'') {
            return 0;
        }
        for (int i = p + 2; i < limit; i++) {
            if (at(i) == '\'') {
                return at(p + 1) == '\'' ? 0 : i - p + 1;
            }
        }
        return 0;
    }

    /** {TABS_AND_SPACES}*[;][^\r\n]*{NEWLINE}. */
    private int comment(int p) {
        int i = p + tabsAndSpaces(p);
        if (at(i) != ';') {
            return 0;
        }
        i++;
        while (i < limit && at(i) != '\r' && at(i) != '\n') {
            i++;
        }
        int n = newline(i);
        return n == 0 ? 0 : i + n - p;
    }

    /** {TABS_AND_SPACES}* then the byte. */
    private int spacedChar(int p, int c) {
        int t = tabsAndSpaces(p);
        return at(p + t) == c ? t + 1 : 0;
    }

    /** {TABS_AND_SPACES}*{NEWLINE}. */
    private int spacedNewline(int p) {
        int t = tabsAndSpaces(p);
        int n = newline(p + t);
        return n == 0 ? 0 : t + n;
    }

    /** "]"{TABS_AND_SPACES}*{NEWLINE}?. */
    private int sectionEnd(int p) {
        if (at(p) != ']') {
            return 0;
        }
        int t = tabsAndSpaces(p + 1);
        return 1 + t + newline(p + 1 + t);
    }

    private int best;
    private int rule;

    /** Takes a rule's match if longer than the longest so far (re2c: the first rule wins a tie). */
    private void match(int length, int ruleNumber) {
        if (length > best) {
            best = length;
            rule = ruleNumber;
        }
    }

    /** RETURN_TOKEN: the text, typed (BOOL_*, NULL_NULL) in a value with INI_SCANNER_TYPED. */
    private int token(int type, String value) {
        if (mode == TYPED && (state == ST_VALUE || state == ST_RAW)
                && (type == BOOL_TRUE || type == BOOL_FALSE || type == NULL_NULL)) {
            lval = type == NULL_NULL ? PhpNull.NULL : (Object) (type == BOOL_TRUE);
        } else {
            lval = value;
        }
        return type;
    }

    private void push(int newState) {
        states.push(state);
        state = newState;
    }

    private void pop() {
        state = states.pop();
    }

    /** The text without leading spaces and tabs, then without trailing whitespace (and the extra byte). */
    private String trimmed(int from, int to, int extra) {
        while (from < to && (at(from) == ' ' || at(from) == '\t')) {
            from++;
        }
        while (to > from && (at(to - 1) == extra || at(to - 1) == '\n' || at(to - 1) == '\r' || at(to - 1) == '\t'
                || at(to - 1) == ' ')) {
            to--;
        }
        return text(from, to);
    }

    private int lex() {
        while (true) {
            int p = cursor;
            if (p >= limit) {
                if (state == ST_VALUE || state == ST_RAW) {
                    state = INITIAL;
                    return END_OF_LINE;
                }
                return 0;
            }
            if (p == 0 && limit > 3 && at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) {
                cursor = 3;
                continue;
            }
            best = 0;
            rule = 0;
            int c = at(p);
            switch (state) {
                case INITIAL -> {
                    match(c == '[' ? 1 : 0, 1);
                    int l = label(p);
                    match(l > 0 && at(p + l) == '[' ? l + 1 + tabsAndSpaces(p + l + 1) : 0, 2);
                    match(keyword(p, "true", "on", "yes"), 3);
                    match(keyword(p, "false", "off", "no", "none"), 4);
                    match(keyword(p, "null"), 5);
                    match(l, 6);
                    int t = spacedChar(p, '=');
                    match(t == 0 ? 0 : t + tabsAndSpaces(p + t), 7);
                    match(":,.[]\"'()&|^+-/*=%$!~<>?@{}".indexOf(c) >= 0 && c != 0 ? 1 : 0, 8);
                    match(tabsAndSpaces(p), 9);
                    match(spacedNewline(p), 10);
                    match(comment(p), 11);
                    match(1, 12);
                }
                case ST_VALUE -> {
                    match(singleQuoted(p), 20);
                    match(c == '$' && at(p + 1) == '{' ? 2 : 0, 21);
                    match(keyword(p, "true", "on", "yes"), 3);
                    match(keyword(p, "false", "off", "no", "none"), 4);
                    match(keyword(p, "null"), 5);
                    match(spacedNewline(p), 22);
                    match(constant(p), 23);
                    match(number(p), 24);
                    match("&|^~()!".indexOf(c) >= 0 ? 1 + tabsAndSpaces(p + 1) : 0, 25);
                    match(c == '=' ? 1 : 0, 26);
                    match(chars(p, "$= \t\n\r;&|^~()!\"'", false), 27);
                    match(spacedChar(p, '"'), 28);
                    match(tabsAndSpaces(p), 29);
                    match(comment(p), 11);
                    match(1, 30);
                }
                case ST_SECTION_VALUE, ST_OFFSET -> {
                    match(singleQuoted(p), 20);
                    match(state == ST_SECTION_VALUE ? sectionEnd(p) : spacedChar(p, ']'), 31);
                    match(c == '$' && at(p + 1) == '{' ? 2 : 0, 21);
                    match(constant(p), 23);
                    match(number(p), 24);
                    match(chars(p, "$\n\r;\"'\\]", true), 27);
                    match(spacedChar(p, '"'), 28);
                    match(tabsAndSpaces(p), 29);
                    match(1, 12);
                }
                case ST_SECTION_RAW -> {
                    match(sectionEnd(p), 31);
                    int i = p;
                    while (i < limit && at(i) != ']' && at(i) != '\n' && at(i) != '\r') {
                        i++;
                    }
                    match(i - p, 32);
                    match(1, 12);
                }
                case ST_VARNAME -> {
                    match(c == ':' && at(p + 1) == '-' ? 2 : 0, 40);
                    match(labelChar(c) ? 1 : 0, 41);
                    match(c == '}' ? 1 : 0, 42);
                    match(1, 12);
                }
                case ST_VAR_FALLBACK -> {
                    match(c == '$' && at(p + 1) == '{' ? 2 : 0, 21);
                    match(c == '}' ? 1 : 0, 42);
                    match(constant(p), 23);
                    match(number(p), 24);
                    match(chars(p, "$\n\r;\"'}\\", true), 27);
                    match(spacedChar(p, '"'), 28);
                    match(tabsAndSpaces(p), 29);
                    match(1, 12);
                }
                case ST_DOUBLE_QUOTES -> {
                    match(c == '$' && at(p + 1) == '{' ? 2 : 0, 21);
                    match(c == '"' ? 1 + tabsAndSpaces(p + 1) : 0, 50);
                    match(1, 51);
                }
                default -> { // ST_RAW
                    match(c != '\n' && c != '\r' && c != ';' && c != 0 ? 1 : 0, 60);
                    match(spacedNewline(p), 22);
                    match(tabsAndSpaces(p), 9);
                    match(comment(p), 11);
                    match(1, 30);
                }
            }
            int end = p + best;
            cursor = end;
            switch (rule) {
                case 1 -> {
                    state = mode == RAW ? ST_SECTION_RAW : ST_SECTION_VALUE;
                    return TC_SECTION;
                }
                case 2 -> {
                    state = ST_OFFSET;
                    return token(TC_OFFSET, trimmed(p, end, '['));
                }
                case 3 -> {
                    return token(BOOL_TRUE, "1");
                }
                case 4 -> {
                    return token(BOOL_FALSE, "");
                }
                case 5 -> {
                    return token(NULL_NULL, "");
                }
                case 6 -> {
                    return token(TC_LABEL, trimmed(p, end, -1));
                }
                case 7 -> {
                    state = mode == RAW ? ST_RAW : ST_VALUE;
                    return '=';
                }
                case 8, 25 -> {
                    return c;
                }
                case 9 -> {
                    continue;
                }
                case 10 -> {
                    lineno++;
                    return END_OF_LINE;
                }
                case 11, 22 -> {
                    state = INITIAL;
                    lineno++;
                    return END_OF_LINE;
                }
                case 12 -> {
                    return 0;
                }
                case 20 -> {
                    return token(TC_RAW, text(p + 1, end - 1));
                }
                case 21 -> {
                    push(ST_VARNAME);
                    return TC_DOLLAR_CURLY;
                }
                case 23 -> {
                    return token(TC_CONSTANT, text(p, end));
                }
                case 24 -> {
                    return token(TC_NUMBER, text(p, end));
                }
                case 26 -> {
                    cursor = p; // yyless(0)
                    state = INITIAL;
                    return END_OF_LINE;
                }
                case 27 -> {
                    return token(TC_STRING, text(p, end));
                }
                case 28 -> {
                    push(ST_DOUBLE_QUOTES);
                    return '"';
                }
                case 29 -> {
                    return token(TC_WHITESPACE, text(p, end));
                }
                case 30 -> {
                    state = INITIAL;
                    return END_OF_LINE;
                }
                case 31 -> {
                    if (state != ST_OFFSET) {
                        lineno++;
                    }
                    state = INITIAL;
                    return ']';
                }
                case 32 -> {
                    return token(TC_RAW, text(p, end));
                }
                case 40 -> {
                    pop();
                    push(ST_VAR_FALLBACK);
                    return TC_FALLBACK;
                }
                case 41 -> {
                    return varname(p);
                }
                case 42 -> {
                    pop();
                    return '}';
                }
                case 50 -> {
                    pop();
                    return '"';
                }
                case 51 -> {
                    return quoted(p);
                }
                default -> {
                    return rawValue(p);
                }
            }
        }
    }

    /** <ST_VARNAME>{LABEL_CHAR}: the name runs to a byte a label can't have, or to ":-". */
    private int varname(int p) {
        int length = 1;
        if (at(cursor) == ':' && at(cursor + 1) == '-') {
            cursor++;
            pop();
            push(ST_VAR_FALLBACK);
            return TC_FALLBACK;
        }
        while (cursor < limit) {
            int c = at(cursor++);
            if ("=\n\r\t;&|^$~(){}!\"[]".indexOf(c) >= 0 || c == ':' && at(cursor) == '-') {
                cursor--;
                length = cursor - p;
                break;
            }
        }
        return token(TC_VARNAME, trimmed(p, p + length, -1));
    }

    /** <ST_DOUBLE_QUOTES>[^]: the string's contents to a quote or "${", the escapes converted. */
    private int quoted(int p) {
        int i = p;
        while (i < limit) {
            int c = at(i++);
            if (c == '"' || c == '$' && i < limit && at(i) == '{') {
                i--;
                break;
            }
            if (c == '\\' && i < limit) {
                int escaped = at(i++);
                // key="C:\path\": a backslash before the closing quote at the end of a line
                if (escaped == '"' && (i >= limit || at(i) == '\n' || at(i) == '\r')) {
                    i--;
                    break;
                }
            }
        }
        cursor = i;
        // zend_ini_escape_string
        StringBuilder out = new StringBuilder();
        for (int j = p; j < i; j++) {
            int c = at(j);
            if (c == '\\') {
                j++;
                if (j >= i) {
                    out.append('\\');
                    continue;
                }
                c = at(j);
                if (c == '"' || c == '\\' || c == '$') {
                    out.append((char) c);
                } else {
                    out.append('\\').append((char) c);
                }
            } else {
                out.append((char) c);
            }
            if (c == '\n' || c == '\r' && (j + 1 >= i || at(j + 1) != '\n')) {
                lineno++;
            }
        }
        lval = out.toString();
        return TC_QUOTED_STRING;
    }

    /** <ST_RAW>{RAW_VALUE_CHARS}: the rest of the line to a ';' outside quotes, without enclosing quotes. */
    private int rawValue(int p) {
        int start = p;
        int length = 1;
        if (at(start) == ' ' || at(start) == '\t') {
            start++;
            length = 0;
        }
        int semicolon = -1;
        while (cursor < limit && at(cursor) != '\n' && at(cursor) != '\r') {
            if (at(cursor) == ';' && semicolon < 0) {
                semicolon = cursor;
            } else if (at(cursor) == '"' && at(start) == '"') {
                semicolon = -1;
            }
            cursor++;
        }
        length = (semicolon >= 0 ? semicolon : cursor) - start;
        int end = start + length;
        while (end > start && (at(end - 1) == '\n' || at(end - 1) == '\r' || at(end - 1) == '\t'
                || at(end - 1) == ' ')) {
            end--;
        }
        if (end - start > 1 && at(start) == '"' && at(end - 1) == '"') {
            start++;
            end--;
        }
        return token(TC_RAW, text(start, end));
    }

    // ---- the parser ----

    private static int translate(int token) {
        if (token >= TC_SECTION) {
            return token - 255;
        }
        int index = CHAR_TOKENS.indexOf(token);
        return index >= 0 ? index + 19 : 2;
    }

    /** yyparse: false after a syntax error, which it reports as ini_error does. */
    private boolean parse() {
        int[] stateStack = new int[16];
        Object[] values = new Object[16];
        boolean[] numbers = new boolean[16]; // Z_EXTRA(...) == INI_ZVAL_IS_NUMBER
        int top = 0;
        int yystate = 0;
        int yychar = -2;
        int yytoken = 0;
        while (true) {
            if (top + 6 >= stateStack.length) {
                stateStack = Arrays.copyOf(stateStack, top * 2 + 12);
                values = Arrays.copyOf(values, stateStack.length);
                numbers = Arrays.copyOf(numbers, stateStack.length);
            }
            stateStack[top] = yystate;
            if (yystate == YYFINAL) {
                return true;
            }
            int yyn = YYPACT[yystate];
            int rule = -1;
            if (yyn != YYPACT_NINF) {
                if (yychar == -2) {
                    yychar = lex();
                }
                yytoken = yychar <= 0 ? 0 : translate(yychar);
                yyn += yytoken;
                if (yyn >= 0 && yyn <= YYLAST && YYCHECK[yyn] == yytoken) {
                    yyn = YYTABLE[yyn];
                    if (yyn > 0) {
                        top++;
                        values[top] = yychar <= 0 ? null : lval;
                        numbers[top] = false;
                        yystate = yyn;
                        yychar = -2;
                        continue;
                    }
                    rule = -yyn;
                }
            }
            if (rule < 0) {
                rule = YYDEFACT[yystate];
                if (rule == 0) {
                    error(yystate, yychar == -2 ? -1 : yytoken);
                    return false;
                }
            }
            int length = YYR2[rule];
            int base = top - length + 1;
            Object value = length > 0 ? values[base] : null;
            boolean number = length > 0 && numbers[base];
            switch (rule) {
                case 4 -> sectionEntry((String) values[base + 1]);
                case 5 -> entry((String) values[base], values[base + 2]);
                case 6 -> popEntry((String) values[base], values[base + 4], (String) values[base + 1]);
                case 10, 15, 17, 20, 43 -> {
                    value = "";
                    number = false;
                }
                case 11 -> value = normalize(value, number);
                case 18, 19, 24, 25, 30, 31 -> value = (String) values[base] + values[base + 1];
                case 23, 29, 39 -> {
                    value = values[base + 1];
                    number = numbers[base + 1];
                }
                case 26, 32 -> value = (String) values[base] + values[base + 2];
                case 34, 35, 36 -> value = operation(rule == 34 ? '|' : rule == 35 ? '&' : '^', values[base],
                        values[base + 2]);
                case 37 -> value = operation('~', values[base + 1], null);
                case 38 -> value = operation('!', values[base + 1], null);
                case 40 -> value = variable((String) values[base + 1], null);
                case 41 -> value = variable((String) values[base + 1], (String) values[base + 3]);
                case 49 -> value = constantValue((String) value);
                case 51 -> number = true;
                default -> {
                }
            }
            top = base - 1;
            int lhs = YYR1[rule] - YYNTOKENS;
            int from = stateStack[top];
            int goTo = YYPGOTO[lhs] + from;
            yystate = goTo >= 0 && goTo <= YYLAST && YYCHECK[goTo] == from ? YYTABLE[goTo] : YYDEFGOTO[lhs];
            top++;
            values[top] = value;
            numbers[top] = number;
        }
    }

    /** yysyntax_error, reported by ini_error: the unexpected token and up to four expected ones. */
    private void error(int yystate, int yytoken) {
        StringBuilder message = new StringBuilder("syntax error");
        if (yytoken >= 0) {
            message.append(", unexpected ").append(YYTNAME[yytoken]);
            StringBuilder expected = new StringBuilder();
            int count = 0;
            int yyn = YYPACT[yystate];
            if (yyn != YYPACT_NINF) {
                int xend = Math.min(YYLAST - yyn + 1, YYNTOKENS);
                for (int x = yyn < 0 ? -yyn : 0; x < xend; x++) {
                    if (YYCHECK[x + yyn] == x && x != 1) {
                        if (++count == 5) {
                            break;
                        }
                        expected.append(count == 1 ? ", expecting " : " or ").append(YYTNAME[x]);
                    }
                }
            }
            if (count < 5) {
                message.append(expected);
            }
        }
        message.append(" in ").append(filename).append(" on line ").append(lineno).append('\n');
        ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of(message.toString()), SourceLocation.current());
    }

    // ---- the semantic actions ----

    private static Object php(Object value) {
        return value instanceof String text ? Str.of(text.getBytes(StandardCharsets.ISO_8859_1)) : value;
    }

    private static Object key(String name) {
        return ArrayKey.of(Str.of(name.getBytes(StandardCharsets.ISO_8859_1)));
    }

    /** php_ini_parser_cb_with_sections, ZEND_INI_PARSER_SECTION. */
    private void sectionEntry(String name) {
        if (sections) {
            section = new PhpArray();
            result.put(key(name), section);
        }
    }

    private PhpArray target() {
        return section != null ? section : result;
    }

    /** php_simple_ini_parser_cb, ZEND_INI_PARSER_ENTRY. */
    private void entry(String name, Object value) {
        target().put(key(name), php(value));
    }

    /** php_simple_ini_parser_cb, ZEND_INI_PARSER_POP_ENTRY: name[offset] = value. */
    private void popEntry(String name, Object value, String offset) {
        PhpArray array = target();
        byte[] bytes = name.getBytes(StandardCharsets.ISO_8859_1);
        Object key = Str.of(bytes);
        if (!(bytes.length > 1 && bytes[0] == '0') && Convert.parseNumber(bytes, false) instanceof Long) {
            key = strtoul(name);
        }
        PhpArray hash;
        if (array.get(key) instanceof PhpArray found) {
            hash = found;
        } else {
            hash = new PhpArray();
            array.put(key, hash);
        }
        if (offset.isEmpty()) {
            hash.append(php(value));
        } else {
            hash.put(key(offset), php(value));
        }
    }

    /** ZEND_STRTOUL(s, NULL, 0) on a numeric integer string: leading whitespace, a sign, octal after a 0. */
    private static long strtoul(String text) {
        String t = text.stripLeading();
        boolean negative = t.startsWith("-");
        if (negative || t.startsWith("+")) {
            t = t.substring(1);
        }
        int base = t.length() > 1 && t.charAt(0) == '0' ? 8 : 10;
        long value = 0;
        for (int i = 0; i < t.length() && Character.digit(t.charAt(i), base) >= 0; i++) {
            value = value * base + Character.digit(t.charAt(i), base);
        }
        return negative ? -value : value;
    }

    /** normalize_value: with INI_SCANNER_TYPED, a value that began with a number is an int or float if numeric. */
    private Object normalize(Object value, boolean number) {
        if (mode != TYPED || !number || !(value instanceof String text)) {
            return value;
        }
        Object parsed = Convert.parseNumber(text.getBytes(StandardCharsets.ISO_8859_1), false);
        if (parsed instanceof Double && text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
            return value; // an integer too big: is_numeric_string_ex's overflow
        }
        return parsed != null ? parsed : value;
    }

    /** zend_ini_do_op on get_int_val's ints (atoi for a string). */
    private Object operation(char type, Object op1, Object op2) {
        int a = intValue(op1);
        int b = op2 == null ? 0 : intValue(op2);
        int r = switch (type) {
            case '|' -> a | b;
            case '&' -> a & b;
            case '^' -> a ^ b;
            case '~' -> ~a;
            default -> a == 0 ? 1 : 0;
        };
        return mode == TYPED ? (Object) (long) r : Integer.toString(r);
    }

    private static int intValue(Object value) {
        if (value instanceof Long l) {
            return (int) (long) l;
        }
        // atoi: (int) strtol(s, NULL, 10)
        String t = value.toString();
        int i = 0;
        while (i < t.length() && (t.charAt(i) == ' ' || t.charAt(i) >= '\t' && t.charAt(i) <= '\r')) {
            i++;
        }
        boolean negative = i < t.length() && t.charAt(i) == '-';
        if (i < t.length() && (t.charAt(i) == '-' || t.charAt(i) == '+')) {
            i++;
        }
        long v = 0;
        for (; i < t.length() && isDigit(t.charAt(i)); i++) {
            v = v > Long.MAX_VALUE / 10 ? Long.MAX_VALUE : v * 10 + t.charAt(i) - '0';
        }
        return (int) (negative ? -v : v);
    }

    /** zend_ini_get_var: a php.ini setting, else the environment variable, else the fallback, else "". */
    private Object variable(String name, String fallback) {
        String setting = context.ini().configuration().get(name);
        if (setting == null) {
            setting = context.getEnv().getEnvironment().get(name);
            if (setting != null) {
                setting = new String(setting.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
            }
        }
        return setting != null ? setting : fallback != null ? fallback : "";
    }

    /** zend_ini_get_constant: a defined constant's value as a string, else the name. */
    private Object constantValue(String name) {
        Object value = context.findConstant(name);
        if (value == null) {
            switch (Str.lower(name)) {
                case "true" -> value = Boolean.TRUE;
                case "false" -> value = Boolean.FALSE;
                case "null" -> value = PhpNull.NULL;
                default -> {
                    return name;
                }
            }
        }
        return new String(Str.bytes(Convert.toStr(value)), StandardCharsets.ISO_8859_1);
    }
}
