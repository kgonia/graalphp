/*
 * Hand-written: a port of the C in php-src 8.5.12's Zend/zend_language_scanner.l around and in its re2c rules
 * (the scanner's globals, lex_scan and its epilogue, the helpers, and the rule actions with C beyond the scanner
 * macros). php-src is under the Zend Engine License 2.00 (its Zend/LICENSE).
 */
package pl.codeloom.php.parser.zend;

import java.util.ArrayList;
import java.util.Arrays;

import static pl.codeloom.php.parser.zend.derived.ZendConstants.*;
import static pl.codeloom.php.parser.zend.generated.ZendLanguageParser.Lexer.*;
import static pl.codeloom.php.parser.zend.generated.ZendLanguageScanner.*;

/**
 * The scanner's state and C, which the scanner generated from php-src's re2c rules (ZendLanguageScanner) extends.
 * Names are php-src's: the globals SCNG(yy_text), SCNG(yy_cursor) ... are fields, macros and functions are methods,
 * so the derived rules' actions read as php-src's. Pointers into the script become offsets into {@link #yy_input},
 * which ends, as php-src's buffer, in ZEND_MMAP_AHEAD zero bytes that a scan may read past the script's end.
 */
public abstract class ZendScannerBase {
    /** The zero bytes after the script (php-src's ZEND_MMAP_AHEAD), at least the DFA's YYMAXFILL. */
    public static final int ZEND_MMAP_AHEAD = 32;
    /** scan()'s "goto restart": scan again from the cursor. Tokens are 0 or more. */
    protected static final int RESTART = -1;
    public static final int SUCCESS = 0;
    public static final int FAILURE = -1;

    protected static final int HEREDOC_USING_SPACES = 1;
    protected static final int HEREDOC_USING_TABS = 2;
    /** MAX_LENGTH_OF_LONG and LONG_MIN_DIGITS of 64-bit php-src (zend_long.h). */
    private static final int MAX_LENGTH_OF_LONG = 20;
    private static final byte[] LONG_MIN_DIGITS = "9223372036854775808".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] EMPTY = new byte[0];
    /** ZSTR_CHAR: php-src's interned one-character strings. */
    private static final byte[][] CHARS = new byte[256][];

    static {
        for (int c = 0; c < 256; c++) {
            CHARS[c] = new byte[]{(byte) c};
        }
    }

    protected final CompilerGlobals CG;

    // ---- SCNG(...) ----
    /** The script's bytes, then ZEND_MMAP_AHEAD zero bytes. */
    public final byte[] yy_input;
    public int yy_start;
    public int yy_text;
    public int yy_leng;
    public int yy_cursor;
    public int yy_marker;
    public int yy_limit;
    /** The condition (yyc* of ZendLanguageScanner). */
    public int yy_state;
    protected IntStack state_stack = new IntStack();
    protected NestStack nest_location_stack = new NestStack();
    protected ArrayList<HeredocLabel> heredoc_label_stack = new ArrayList<>();
    protected boolean heredoc_scan_ahead;
    protected int heredoc_indentation;
    protected boolean heredoc_indentation_uses_spaces;
    /** SET/GET_DOUBLE_QUOTES_SCANNED_LENGTH. */
    protected int scanned_string_len;

    // ---- lex_scan's arguments and locals, fields so the rules' actions see them ----
    protected zval zendlval;
    protected ZendParserStackElem elem;
    protected int start_line;

    /** The scanner over script, as zend_prepare_string_for_scanning sets it up (php-ast parses a file so). */
    protected ZendScannerBase(CompilerGlobals CG, byte[] script) {
        this.CG = CG;
        this.yy_input = Arrays.copyOf(script, script.length + ZEND_MMAP_AHEAD);
        this.yy_start = 0;
        this.yy_cursor = 0;
        this.yy_limit = script.length;
        CG.zend_lineno = 1;
        CG.increment_lineno = 0;
        RESET_DOC_COMMENT();
        yy_state = yycINITIAL;
    }

    /** The re2c DFA: one token, or RESTART. */
    protected abstract int scan();

    /**
     * lex_scan: the next token; elem is the parser's value of it (PARSER_MODE), or null for token_get_all's
     * scan, which returns whitespace, comments and open tags too. Reentrant, as the heredoc scan-ahead needs.
     */
    public final int lex_scan(zval zendlval, ZendParserStackElem elem) {
        zval outerValue = this.zendlval;
        ZendParserStackElem outerElem = this.elem;
        int outerStartLine = this.start_line;
        this.zendlval = zendlval;
        this.elem = elem;
        this.start_line = CG.zend_lineno;
        try {
            zendlval.value = zval.UNDEF;
            int token;
            do {
                token = scan();
            } while (token == RESTART);
            return token;
        } finally {
            this.zendlval = outerValue;
            this.elem = outerElem;
            this.start_line = outerStartLine;
        }
    }

    // ---- lex_scan's epilogue ----

    protected final int emit_token_with_str(int token, int offset) {
        zend_copy_value(zendlval, yy_text + offset, yy_leng - offset);
        return emit_token_with_val(token);
    }

    protected final int emit_token_with_val(int token) {
        if (PARSER_MODE()) {
            if (zendlval.value == zval.UNDEF) {
                throw new IllegalStateException("a token's value is undefined");
            }
            elem.ast = new ZendAstZval(zendlval.value, 0, start_line);
            elem.value = elem.ast;
        }
        return token;
    }

    protected final int emit_token(int token) {
        return token;
    }

    protected final int emit_token_with_ident(int token) {
        if (PARSER_MODE()) {
            elem.ident = yy_text;
            elem.value = yy_text;
        }
        return token;
    }

    protected final int return_whitespace() {
        HANDLE_NEWLINES(yy_text, yy_leng);
        if (PARSER_MODE()) {
            start_line = CG.zend_lineno;
            return RESTART;
        }
        return T_WHITESPACE;
    }

    protected final int skip_token(int token) {
        start_line = CG.zend_lineno;
        return RESTART;
    }

    // ---- the scanner's macros ----

    protected final boolean PARSER_MODE() {
        return elem != null;
    }

    /** yytext[i]: a byte of the token. */
    protected final int yytext(int i) {
        return yy_input[yy_text + i] & 0xff;
    }

    protected final void yyless(int x) {
        yy_cursor = yy_text + x;
        yy_leng = x;
    }

    protected final void BEGIN(int state) {
        yy_state = state;
    }

    protected final void yy_push_state(int new_state) {
        state_stack.push(yy_state);
        yy_state = new_state;
    }

    protected final void yy_pop_state() {
        yy_state = state_stack.pop();
    }

    protected final boolean zend_stack_is_empty(IntStack stack) {
        return stack.isEmpty();
    }

    protected final void HANDLE_NEWLINES(int s, int l) {
        int boundary = s + l;
        for (int p = s; p < boundary; p++) {
            if (yy_input[p] == '\n' || (yy_input[p] == '\r' && yy_input[p + 1] != '\n')) {
                CG.zend_lineno++;
            }
        }
    }

    protected final void HANDLE_NEWLINE(int c) {
        if (c == '\n' || c == '\r') {
            CG.zend_lineno++;
        }
    }

    protected final void RESET_DOC_COMMENT() {
        CG.doc_comment = null;
    }

    protected static boolean IS_LABEL_START(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c >= 0x80;
    }

    protected static boolean IS_LABEL_SUCCESSOR(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c >= 0x80;
    }

    private static boolean ZEND_IS_OCT(int c) {
        return c >= '0' && c <= '7';
    }

    private static boolean ZEND_IS_HEX(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    protected final void ZVAL_NULL(zval zv) {
        zv.value = null;
    }

    protected final void ZVAL_UNDEF(zval zv) {
        zv.value = zval.UNDEF;
    }

    protected final void ZVAL_STRINGL(zval zv, int str, int len) {
        zv.value = Arrays.copyOfRange(yy_input, str, str + len);
    }

    protected final void ZVAL_INTERNED_STR(zval zv, byte[] str) {
        zv.value = str;
    }

    protected static byte[] ZSTR_CHAR(int c) {
        return CHARS[c & 0xff];
    }

    /** zend_copy_value: the token's text as a string (without php-src's multibyte output filter). */
    private void zend_copy_value(zval zv, int yytext, int yyleng) {
        if (yyleng == 1) {
            ZVAL_INTERNED_STR(zv, ZSTR_CHAR(yy_input[yytext]));
        } else {
            ZVAL_STRINGL(zv, yytext, yyleng);
        }
    }

    protected final void zend_throw_exception(int ce, String message, int code) {
        CG.zend_throw_exception(ce == zend_ce_parse_error, message);
    }

    protected final void zend_error(int type, String message) {
        CG.zend_error(type, message);
    }

    protected static final int zend_ce_parse_error = 1;
    protected static final int zend_ce_compile_error = 2;
    protected static final int E_DEPRECATED = CompilerGlobals.E_DEPRECATED;
    protected static final int E_COMPILE_WARNING = CompilerGlobals.E_COMPILE_WARNING;

    // ---- nesting of {} [] () ----

    /** report_bad_nesting */
    private void report_bad_nesting(int opening, int opening_lineno, int closing) {
        StringBuilder buf = new StringBuilder("Unclosed '").append((char) opening).append('\'');
        if (opening_lineno != CG.zend_lineno) {
            buf.append(" on line ").append(opening_lineno);
        }
        if (closing != 0) {
            // 'closing' is 0 at the end of the file
            buf.append(" does not match '").append((char) closing).append('\'');
        }
        zend_throw_exception(zend_ce_parse_error, buf.toString(), 0);
    }

    protected final void enter_nesting(int opening) {
        nest_location_stack.push(opening, CG.zend_lineno, yy_text);
    }

    protected final int exit_nesting(int closing) {
        if (nest_location_stack.isEmpty()) {
            zend_throw_exception(zend_ce_parse_error, "Unmatched '" + (char) closing + "'", 0);
            return FAILURE;
        }
        int opening = nest_location_stack.topText();
        if ((opening == '{' && closing != '}') || (opening == '[' && closing != ']')
                || (opening == '(' && closing != ')')) {
            report_bad_nesting(opening, nest_location_stack.topLineno(), closing);
            return FAILURE;
        }
        if (opening == '{') {
            closedBrace(nest_location_stack.topOffset(), yy_text);
        }
        nest_location_stack.pop();
        return SUCCESS;
    }

    protected final int check_nesting_at_end() {
        if (!nest_location_stack.isEmpty()) {
            report_bad_nesting(nest_location_stack.topText(), nest_location_stack.topLineno(), 0);
            return FAILURE;
        }
        return SUCCESS;
    }

    /** Where each '{' (by its offset) was closed: where a function body ends, to parse it again. */
    final java.util.HashMap<Integer, Integer> brace_ends = new java.util.HashMap<>();

    private void closedBrace(int opening, int closing) {
        brace_ends.put(opening, closing);
    }

    // ---- numbers ----

    /** The digits of yy_input[from, from + len) without underscores (strip_underscores), as ASCII text. */
    private String digits(int from, int len) {
        StringBuilder text = new StringBuilder(len);
        for (int i = from; i < from + len; i++) {
            if (yy_input[i] != '_') {
                text.append((char) yy_input[i]);
            }
        }
        return text.toString();
    }

    /** ZEND_STRTOL over digits in base: the value, or null where strtol sets errno to ERANGE. */
    private static Long strtol(String digits, int base) {
        try {
            return Long.parseLong(digits, base);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** zend_hex_strtod: the digits' value, accumulated in double arithmetic as php-src does. */
    private static double zend_hex_strtod(String s) {
        double value = 0;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                c -= '0';
            } else if (c >= 'A' && c <= 'F') {
                c -= 'A' - 10;
            } else if (c >= 'a' && c <= 'f') {
                c -= 'a' - 10;
            } else {
                break;
            }
            value = value * 16 + c;
        }
        return value;
    }

    /** zend_oct_strtod */
    private static double zend_oct_strtod(String s) {
        double value = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '7') {
                break;
            }
            value = value * 8 + c - '0';
        }
        return value;
    }

    /** zend_bin_strtod */
    private static double zend_bin_strtod(String s) {
        double value = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '0' || c == '1') {
                value = value * 2 + c - '0';
            } else {
                break;
            }
        }
        return value;
    }

    /** zend_strtod of a decimal literal: correctly rounded, as php-src's (David Gay's) strtod. */
    private static double zend_strtod(String s) {
        return Double.parseDouble(s);
    }

    /** {@code <ST_IN_SCRIPTING>{BNUM}} */
    protected final int scan_bnum() {
        // the +/- 2 skips "0b"
        int len = yy_leng - 2;
        int bin = yy_text + 2;
        // skip any leading 0s
        while (len > 0 && (yy_input[bin] == '0' || yy_input[bin] == '_')) {
            ++bin;
            --len;
        }
        String digits = digits(bin, len);
        len = digits.length();
        if (len < 64) {
            zendlval.value = len == 0 ? 0L : strtol(digits, 2);
            return emit_token_with_val(T_LNUMBER);
        }
        // errno isn't checked since we allow HUGE_VAL/INF overflow
        zendlval.value = zend_bin_strtod(digits);
        return emit_token_with_val(T_DNUMBER);
    }

    /** {@code <ST_IN_SCRIPTING>{ONUM}} */
    protected final int scan_onum() {
        // the +/- 2 skips "0o"
        int len = yy_leng - 2;
        int octal = yy_text + 2;
        // skip any leading 0s
        while (len > 0 && (yy_input[octal] == '0' || yy_input[octal] == '_')) {
            ++octal;
            --len;
        }
        if (len == 0) {
            zendlval.value = 0L;
            return emit_token_with_val(T_LNUMBER);
        }
        String digits = digits(octal, len);
        Long value = strtol(digits, 8);
        if (value != null) {
            zendlval.value = value;
            return emit_token_with_val(T_LNUMBER);
        }
        // overflow
        zendlval.value = zend_oct_strtod(digits);
        return emit_token_with_val(T_DNUMBER);
    }

    /** {@code <ST_IN_SCRIPTING>{LNUM}} */
    protected final int scan_lnum() {
        int len = yy_leng;
        boolean is_octal = yy_input[yy_text] == '0';
        String lnum = digits(yy_text, len);
        len = lnum.length();

        // digits 8 and 9 are illegal in octal literals
        if (is_octal) {
            for (int i = 0; i < len; i++) {
                if (lnum.charAt(i) == '8' || lnum.charAt(i) == '9') {
                    zend_throw_exception(zend_ce_parse_error, "Invalid numeric literal", 0);
                    if (PARSER_MODE()) {
                        ZVAL_UNDEF(zendlval);
                        return emit_token(T_ERROR);
                    }
                    // continue in order to determine if this is T_LNUMBER or T_DNUMBER
                    len = i;
                    break;
                }
            }
        }
        lnum = lnum.substring(0, len);

        if (len < MAX_LENGTH_OF_LONG - 1) {
            // won't overflow
            zendlval.value = len == 0 ? 0L : strtol(lnum, is_octal ? 8 : 10);
        } else {
            // strtol with base 0: octal after a leading 0
            Long value = strtol(lnum, is_octal ? 8 : 10);
            if (value == null) {
                // overflow
                zendlval.value = is_octal ? zend_oct_strtod(lnum) : zend_strtod(lnum);
                return emit_token_with_val(T_DNUMBER);
            }
            zendlval.value = value;
        }
        return emit_token_with_val(T_LNUMBER);
    }

    /** {@code <ST_IN_SCRIPTING>{HNUM}} */
    protected final int scan_hnum() {
        // the +/- 2 skips "0x"
        int len = yy_leng - 2;
        int hex = yy_text + 2;
        // skip any leading 0s
        while (len > 0 && (yy_input[hex] == '0' || yy_input[hex] == '_')) {
            ++hex;
            --len;
        }
        String digits = digits(hex, len);
        len = digits.length();
        if (len < 16 || (len == 16 && digits.charAt(0) <= '7')) {
            zendlval.value = len == 0 ? 0L : strtol(digits, 16);
            return emit_token_with_val(T_LNUMBER);
        }
        // errno isn't checked since we allow HUGE_VAL/INF overflow
        zendlval.value = zend_hex_strtod(digits);
        return emit_token_with_val(T_DNUMBER);
    }

    /** {@code <ST_VAR_OFFSET>[0]|([1-9][0-9]*)}: an offset that could be treated as a long */
    protected final int scan_var_offset_long() {
        if (yy_leng < MAX_LENGTH_OF_LONG - 1
                || (yy_leng == MAX_LENGTH_OF_LONG - 1 && Arrays.compare(yy_input, yy_text, yy_text + yy_leng,
                LONG_MIN_DIGITS, 0, LONG_MIN_DIGITS.length) < 0)) {
            Long value = strtol(digits(yy_text, yy_leng), 10);
            if (value != null) {
                zendlval.value = value;
                return emit_token_with_val(T_NUM_STRING);
            }
        }
        ZVAL_STRINGL(zendlval, yy_text, yy_leng);
        return emit_token_with_val(T_NUM_STRING);
    }

    /** {@code <ST_IN_SCRIPTING>{DNUM}|{EXPONENT_DNUM}} */
    protected final int scan_dnum() {
        // errno isn't checked since we allow HUGE_VAL/INF overflow
        zendlval.value = zend_strtod(digits(yy_text, yy_leng));
        return emit_token_with_val(T_DNUMBER);
    }

    // ---- inline HTML and comments ----

    /** {@code <INITIAL>{ANY_CHAR}} */
    protected final int scan_inline_html() {
        if (yy_cursor > yy_limit) {
            return check_nesting_at_end() != SUCCESS && PARSER_MODE() ? emit_token(T_ERROR) : emit_token(END);
        }
        return inline_char_handler();
    }

    /** The inline_char_handler label of {@code <INITIAL>{ANY_CHAR}}, which the open tag rules jump to. */
    protected final int inline_char_handler() {
        while (true) {
            int ptr = indexOf('<', yy_cursor, yy_limit);
            yy_cursor = ptr >= 0 ? ptr + 1 : yy_limit;
            if (yy_cursor >= yy_limit) {
                break;
            }
            if (yy_input[yy_cursor] == '?') {
                if (CG.short_tags // <?
                        || yy_input[yy_cursor + 1] == '=' // <?=
                        || (startsWithIgnoreCase(yy_cursor + 1, "php") // <?php[ \t\r\n]
                        && (yy_cursor + 4 == yy_limit || yy_input[yy_cursor + 4] == ' '
                        || yy_input[yy_cursor + 4] == '\t' || yy_input[yy_cursor + 4] == '\n'
                        || yy_input[yy_cursor + 4] == '\r'))) {
                    yy_cursor--;
                    break;
                }
            }
        }
        yy_leng = yy_cursor - yy_text;
        zend_copy_value(zendlval, yy_text, yy_leng);
        HANDLE_NEWLINES(yy_text, yy_leng);
        return emit_token_with_val(T_INLINE_HTML);
    }

    private int indexOf(int c, int from, int to) {
        for (int i = from; i < to; i++) {
            if (yy_input[i] == c) {
                return i;
            }
        }
        return -1;
    }

    /** strncasecmp(at, lower, n) == 0 for an ASCII lower-case word. */
    private boolean startsWithIgnoreCase(int at, String lower) {
        for (int i = 0; i < lower.length(); i++) {
            int c = yy_input[at + i];
            if (c >= 'A' && c <= 'Z') {
                c += 'a' - 'A';
            }
            if (c != lower.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** {@code <ST_IN_SCRIPTING,ST_LOOKING_FOR_PROPERTY>"#"|"//"} */
    protected final int scan_line_comment() {
        while (yy_cursor < yy_limit) {
            byte c = yy_input[yy_cursor++];
            if (c == '\r' || c == '\n') {
                yy_cursor--;
                break;
            }
            if (c == '?' && yy_input[yy_cursor] == '>') {
                yy_cursor--;
                break;
            }
        }
        yy_leng = yy_cursor - yy_text;
        return PARSER_MODE() ? skip_token(T_COMMENT) : emit_token(T_COMMENT);
    }

    /** {@code <ST_IN_SCRIPTING,ST_LOOKING_FOR_PROPERTY>"/*"|"/**"{WHITESPACE}} */
    protected final int scan_block_comment() {
        boolean doc_com;
        if (yy_leng > 2) {
            doc_com = true;
            RESET_DOC_COMMENT();
        } else {
            doc_com = false;
        }
        while (yy_cursor < yy_limit) {
            if (yy_input[yy_cursor++] == '*' && yy_input[yy_cursor] == '/') {
                break;
            }
        }
        if (yy_cursor < yy_limit) {
            yy_cursor++;
        } else {
            zend_throw_exception(zend_ce_parse_error, "Unterminated comment starting line " + CG.zend_lineno, 0);
            if (PARSER_MODE()) {
                return emit_token(T_ERROR);
            }
        }
        yy_leng = yy_cursor - yy_text;
        HANDLE_NEWLINES(yy_text, yy_leng);
        if (doc_com) {
            CG.doc_comment = Arrays.copyOfRange(yy_input, yy_text, yy_text + yy_leng);
            return PARSER_MODE() ? skip_token(T_DOC_COMMENT) : emit_token(T_DOC_COMMENT);
        }
        return PARSER_MODE() ? skip_token(T_COMMENT) : emit_token(T_COMMENT);
    }

    // ---- strings ----

    /** {@code <ST_IN_SCRIPTING>b?[']} */
    protected final int scan_single_quoted() {
        int bprefix = yy_input[yy_text] != '\'' ? 1 : 0;
        while (true) {
            if (yy_cursor < yy_limit) {
                if (yy_input[yy_cursor] == '\'') {
                    yy_cursor++;
                    yy_leng = yy_cursor - yy_text;
                    break;
                } else if (yy_input[yy_cursor++] == '\\' && yy_cursor < yy_limit) {
                    yy_cursor++;
                }
            } else {
                yy_leng = yy_limit - yy_text;
                // unclosed single quotes: treat similar to double quotes, but without a separate token for '
                // (unrecognized by parser)
                ZVAL_NULL(zendlval);
                return emit_token_with_val(T_ENCAPSED_AND_WHITESPACE);
            }
        }

        if (yy_leng - bprefix - 2 <= 1) {
            if (yy_leng - bprefix - 2 < 1) {
                zendlval.value = EMPTY;
            } else {
                int c = yy_input[yy_text + bprefix + 1] & 0xff;
                HANDLE_NEWLINE(c);
                ZVAL_INTERNED_STR(zendlval, ZSTR_CHAR(c));
            }
            return emit_token_with_val(T_CONSTANT_ENCAPSED_STRING);
        }
        // the string as a zend_string: its bytes, then the terminating NUL some reads below see
        int length = yy_leng - bprefix - 2;
        byte[] str = Arrays.copyOfRange(yy_input, yy_text + bprefix + 1, yy_text + bprefix + 1 + length + 1);
        str[length] = 0;

        // convert escape sequences
        int s = 0;
        int end = length;
        while (true) {
            if (str[s] == '\\') {
                break;
            }
            if (str[s] == '\n' || (str[s] == '\r' && str[s + 1] != '\n')) {
                CG.zend_lineno++;
            }
            s++;
            if (s == end) {
                zendlval.value = Arrays.copyOf(str, length);
                return emit_token_with_val(T_CONSTANT_ENCAPSED_STRING);
            }
        }

        int t = s;
        while (s < end) {
            if (str[s] == '\\') {
                s++;
                if (str[s] == '\\' || str[s] == '\'') {
                    str[t++] = str[s];
                } else {
                    str[t++] = '\\';
                    str[t++] = str[s];
                }
            } else {
                str[t++] = str[s];
            }
            if (str[s] == '\n' || (str[s] == '\r' && str[s + 1] != '\n')) {
                CG.zend_lineno++;
            }
            s++;
        }
        zendlval.value = Arrays.copyOf(str, t);
        return emit_token_with_val(T_CONSTANT_ENCAPSED_STRING);
    }

    /** {@code <ST_IN_SCRIPTING>b?["]} */
    protected final int scan_double_quoted() {
        int bprefix = yy_input[yy_text] != '"' ? 1 : 0;
        while (yy_cursor < yy_limit) {
            int c = yy_input[yy_cursor++];
            if (c == '"') {
                yy_leng = yy_cursor - yy_text;
                if (zend_scan_escape_string(zendlval, yy_text + bprefix + 1, yy_leng - bprefix - 2, '"') == SUCCESS
                        || !PARSER_MODE()) {
                    return emit_token_with_val(T_CONSTANT_ENCAPSED_STRING);
                }
                return emit_token(T_ERROR);
            } else if (c == '$') {
                if (IS_LABEL_START(yy_input[yy_cursor] & 0xff) || yy_input[yy_cursor] == '{') {
                    yy_cursor--;
                    break;
                }
            } else if (c == '{') {
                if (yy_input[yy_cursor] == '$') {
                    yy_cursor--;
                    break;
                }
            } else if (c == '\\') {
                if (yy_cursor < yy_limit) {
                    yy_cursor++;
                }
            }
        }
        // remember how much was scanned to save rescanning
        scanned_string_len = yy_cursor - yy_text - yy_leng;
        yy_cursor = yy_text + yy_leng;
        BEGIN(yycST_DOUBLE_QUOTES);
        return emit_token('"');
    }

    /** {@code <ST_DOUBLE_QUOTES>{ANY_CHAR}} */
    protected final int scan_double_quotes_content() {
        if (scanned_string_len != 0) {
            yy_cursor += scanned_string_len - 1;
            scanned_string_len = 0;
        } else {
            if (yy_cursor > yy_limit) {
                return check_nesting_at_end() != SUCCESS && PARSER_MODE() ? emit_token(T_ERROR) : emit_token(END);
            }
            if (yy_input[yy_text] == '\\' && yy_cursor < yy_limit) {
                yy_cursor++;
            }
            scanUntilInterpolation('"');
        }
        // double_quotes_scan_done
        yy_leng = yy_cursor - yy_text;
        if (zend_scan_escape_string(zendlval, yy_text, yy_leng, '"') == SUCCESS || !PARSER_MODE()) {
            return emit_token_with_val(T_ENCAPSED_AND_WHITESPACE);
        }
        return emit_token(T_ERROR);
    }

    /** {@code <ST_BACKQUOTE>{ANY_CHAR}} */
    protected final int scan_backquote_content() {
        if (yy_cursor > yy_limit) {
            return check_nesting_at_end() != SUCCESS && PARSER_MODE() ? emit_token(T_ERROR) : emit_token(END);
        }
        if (yy_input[yy_text] == '\\' && yy_cursor < yy_limit) {
            yy_cursor++;
        }
        scanUntilInterpolation('`');
        yy_leng = yy_cursor - yy_text;
        if (zend_scan_escape_string(zendlval, yy_text, yy_leng, '`') == SUCCESS || !PARSER_MODE()) {
            return emit_token_with_val(T_ENCAPSED_AND_WHITESPACE);
        }
        return emit_token(T_ERROR);
    }

    /**
     * The loop of the double-quotes and backquote content rules: up to the closing quote, a "$" starting a variable
     * or "${", or a "{$", whichever comes first, leaving the cursor on it.
     */
    private void scanUntilInterpolation(int quote) {
        while (yy_cursor < yy_limit) {
            int c = yy_input[yy_cursor++];
            if (c == quote) {
                yy_cursor--;
                return;
            } else if (c == '$') {
                if (IS_LABEL_START(yy_input[yy_cursor] & 0xff) || yy_input[yy_cursor] == '{') {
                    yy_cursor--;
                    return;
                }
            } else if (c == '{') {
                if (yy_input[yy_cursor] == '$') {
                    yy_cursor--;
                    return;
                }
            } else if (c == '\\') {
                if (yy_cursor < yy_limit) {
                    yy_cursor++;
                }
            }
        }
    }

    /**
     * zend_scan_escape_string: str[0, len) of the input with its escape sequences converted, into zv. quote_type:
     * '"' or '`' (the quote escaped in it), or 0 for a heredoc.
     */
    protected final int zend_scan_escape_string(zval zv, int str, int len, int quote_type) {
        return zend_scan_escape_string(zv, yy_input, str, len, quote_type);
    }

    /** zend_scan_escape_string of bytes[str, str + len). */
    private int zend_scan_escape_string(zval zv, byte[] bytes, int str, int len, int quote_type) {
        if (len <= 1) {
            if (len < 1) {
                zv.value = EMPTY;
            } else {
                int c = bytes[str] & 0xff;
                HANDLE_NEWLINE(c);
                ZVAL_INTERNED_STR(zv, ZSTR_CHAR(c));
            }
            return SUCCESS;
        }
        // as php-src's zend_string, the copy ends in a NUL that reads past the text see
        byte[] copy = Arrays.copyOfRange(bytes, str, str + len + 1);
        copy[len] = 0;
        return escape(zv, copy, len, quote_type);
    }

    /** zend_scan_escape_string over a NUL-terminated copy: the conversion itself. */
    private int escape(zval zv, byte[] str, int len, int quote_type) {
        // convert escape sequences
        int s = 0;
        int end = len;
        while (true) {
            if (str[s] == '\\') {
                break;
            }
            if (str[s] == '\n' || (str[s] == '\r' && str[s + 1] != '\n')) {
                CG.zend_lineno++;
            }
            s++;
            if (s == end) {
                zv.value = Arrays.copyOf(str, len);
                return SUCCESS;
            }
        }

        int t = s;
        while (s < end) {
            if (str[s] == '\\') {
                s++;
                if (s >= end) {
                    str[t++] = '\\';
                    break;
                }
                switch (str[s]) {
                    case 'n' -> str[t++] = '\n';
                    case 'r' -> str[t++] = '\r';
                    case 't' -> str[t++] = '\t';
                    case 'f' -> str[t++] = '\f';
                    case 'v' -> str[t++] = 0x0b;
                    case 'e' -> str[t++] = 0x1b;
                    case '"', '`', '\\', '$' -> {
                        if ((str[s] == '"' || str[s] == '`') && str[s] != quote_type) {
                            str[t++] = '\\';
                        }
                        str[t++] = str[s];
                    }
                    case 'x', 'X' -> {
                        if (ZEND_IS_HEX(str[s + 1])) {
                            int value = Character.digit(str[++s], 16);
                            if (ZEND_IS_HEX(str[s + 1])) {
                                value = value * 16 + Character.digit(str[++s], 16);
                            }
                            str[t++] = (byte) value;
                        } else {
                            str[t++] = '\\';
                            str[t++] = str[s];
                        }
                    }
                    case 'u' -> {
                        // UTF-8 codepoint escape, format: /\\u\{\x+\}/
                        int start = s + 1;
                        int length = 0;
                        boolean valid = true;
                        if (str[start] != '{') {
                            // silently let this pass to avoid breaking code with JSON in string literals
                            str[t++] = '\\';
                            str[t++] = 'u';
                            break;
                        }
                        // on the other hand, invalid \\u{blah} errors
                        s++;
                        length++;
                        s++;
                        while (str[s] != '}') {
                            if (!ZEND_IS_HEX(str[s])) {
                                valid = false;
                                break;
                            }
                            length++;
                            s++;
                        }
                        if (str[s] == '}') {
                            valid = true;
                            length++;
                        }
                        // \\u{} is invalid
                        if (length <= 2) {
                            valid = false;
                        }
                        if (!valid) {
                            zend_throw_exception(zend_ce_parse_error, "Invalid UTF-8 codepoint escape sequence", 0);
                            ZVAL_UNDEF(zv);
                            return FAILURE;
                        }
                        // strtoul(start + 1, NULL, 16): the hex digits after '{', errno on overflow
                        // (past 0x10FFFF, overflowing or not, it is too large)
                        long codepoint = 0;
                        for (int i = start + 1; ZEND_IS_HEX(str[i]) && codepoint <= 0x10FFFF; i++) {
                            codepoint = codepoint * 16 + Character.digit(str[i], 16);
                        }
                        // per RFC 3629, UTF-8 can only represent 21 bits
                        if (codepoint > 0x10FFFF) {
                            zend_throw_exception(zend_ce_parse_error,
                                    "Invalid UTF-8 codepoint escape sequence: Codepoint too large", 0);
                            ZVAL_UNDEF(zv);
                            return FAILURE;
                        }
                        int cp = (int) codepoint;
                        if (cp < 0x80) {
                            str[t++] = (byte) cp;
                        } else if (cp <= 0x7FF) {
                            str[t++] = (byte) ((cp >> 6) + 0xC0);
                            str[t++] = (byte) ((cp & 0x3F) + 0x80);
                        } else if (cp <= 0xFFFF) {
                            str[t++] = (byte) ((cp >> 12) + 0xE0);
                            str[t++] = (byte) (((cp >> 6) & 0x3F) + 0x80);
                            str[t++] = (byte) ((cp & 0x3F) + 0x80);
                        } else {
                            str[t++] = (byte) ((cp >> 18) + 0xF0);
                            str[t++] = (byte) (((cp >> 12) & 0x3F) + 0x80);
                            str[t++] = (byte) (((cp >> 6) & 0x3F) + 0x80);
                            str[t++] = (byte) ((cp & 0x3F) + 0x80);
                        }
                    }
                    default -> {
                        // check for an octal
                        if (ZEND_IS_OCT(str[s])) {
                            int[] octal_buf = {str[s], 0, 0};
                            int digits = 1;
                            if (ZEND_IS_OCT(str[s + 1])) {
                                octal_buf[1] = str[++s];
                                digits++;
                                if (ZEND_IS_OCT(str[s + 1])) {
                                    octal_buf[2] = str[++s];
                                    digits++;
                                }
                            }
                            if (octal_buf[2] != 0 && octal_buf[0] > '3' && !heredoc_scan_ahead) {
                                // 3 octit values must not overflow 0xFF (\377)
                                zend_error(E_COMPILE_WARNING, "Octal escape sequence overflow \\"
                                        + (char) octal_buf[0] + (char) octal_buf[1] + (char) octal_buf[2]
                                        + " is greater than \\377");
                            }
                            int value = 0;
                            for (int i = 0; i < digits; i++) {
                                value = value * 8 + octal_buf[i] - '0';
                            }
                            str[t++] = (byte) value;
                        } else {
                            str[t++] = '\\';
                            str[t++] = str[s];
                        }
                    }
                }
            } else {
                str[t++] = str[s];
            }
            if (str[s] == '\n' || (str[s] == '\r' && str[s + 1] != '\n')) {
                CG.zend_lineno++;
            }
            s++;
        }
        zv.value = Arrays.copyOf(str, t);
        return SUCCESS;
    }

    // ---- heredoc and nowdoc ----

    /** zend_heredoc_label */
    protected static final class HeredocLabel {
        byte[] label;
        int length;
        int indentation;
        boolean indentation_uses_spaces;

        HeredocLabel copy() {
            HeredocLabel copy = new HeredocLabel();
            copy.label = label;
            copy.length = length;
            copy.indentation = indentation;
            copy.indentation_uses_spaces = indentation_uses_spaces;
            return copy;
        }
    }

    /** zend_lex_state: what zend_save_lexical_state keeps of the scanner, for the heredoc scan-ahead. */
    private record LexState(int yy_leng, int yy_start, int yy_text, int yy_cursor, int yy_marker, int yy_limit,
                            IntStack state_stack, NestStack nest_location_stack,
                            ArrayList<HeredocLabel> heredoc_label_stack, int yy_state, int lineno,
                            boolean compiled_filename) {
    }

    private LexState zend_save_lexical_state() {
        LexState state = new LexState(yy_leng, yy_start, yy_text, yy_cursor, yy_marker, yy_limit, state_stack,
                nest_location_stack, heredoc_label_stack, yy_state, CG.zend_lineno, CG.compiled_filename);
        CG.compiled_filename = false;
        state_stack = new IntStack();
        nest_location_stack = new NestStack();
        heredoc_label_stack = new ArrayList<>();
        return state;
    }

    private void zend_restore_lexical_state(LexState state) {
        yy_leng = state.yy_leng();
        yy_start = state.yy_start();
        yy_text = state.yy_text();
        yy_cursor = state.yy_cursor();
        yy_marker = state.yy_marker();
        yy_limit = state.yy_limit();
        state_stack = state.state_stack();
        nest_location_stack = state.nest_location_stack();
        heredoc_label_stack = state.heredoc_label_stack();
        yy_state = state.yy_state();
        CG.zend_lineno = state.lineno();
        CG.compiled_filename = state.compiled_filename();
        RESET_DOC_COMMENT();
    }

    /** {@code <ST_IN_SCRIPTING>b?"<<<"{TABS_AND_SPACES}({LABEL}|([']{LABEL}['])|(["]{LABEL}["])){NEWLINE}} */
    protected final int scan_heredoc_start() {
        int bprefix = yy_input[yy_text] != '<' ? 1 : 0;
        int spacing = 0;
        int indentation = 0;
        HeredocLabel heredoc_label = new HeredocLabel();
        boolean is_heredoc = true;

        CG.zend_lineno++;
        heredoc_label.length = yy_leng - bprefix - 3 - 1 - (yytext(yy_leng - 2) == '\r' ? 1 : 0);
        int s = yy_text + bprefix + 3;
        while (yy_input[s] == ' ' || yy_input[s] == '\t') {
            s++;
            heredoc_label.length--;
        }
        if (yy_input[s] == '\'') {
            s++;
            heredoc_label.length -= 2;
            is_heredoc = false;
            BEGIN(yycST_NOWDOC);
        } else {
            if (yy_input[s] == '"') {
                s++;
                heredoc_label.length -= 2;
            }
            BEGIN(yycST_HEREDOC);
        }
        heredoc_label.label = Arrays.copyOfRange(yy_input, s, s + heredoc_label.length);
        heredoc_label.indentation_uses_spaces = false;
        heredoc_label.indentation = 0;
        int saved_cursor = yy_cursor;

        heredoc_label_stack.add(heredoc_label);

        while (yy_cursor < yy_limit && (yy_input[yy_cursor] == ' ' || yy_input[yy_cursor] == '\t')) {
            if (yy_input[yy_cursor] == '\t') {
                spacing |= HEREDOC_USING_TABS;
            } else {
                spacing |= HEREDOC_USING_SPACES;
            }
            ++yy_cursor;
            ++indentation;
        }

        if (yy_cursor == yy_limit) {
            yy_cursor = saved_cursor;
            return emit_token(T_START_HEREDOC);
        }

        // check for ending label on the next line
        if (heredoc_label.length < yy_limit - yy_cursor
                && Arrays.equals(yy_input, yy_cursor, yy_cursor + heredoc_label.length, yy_input, s,
                s + heredoc_label.length)) {
            if (!IS_LABEL_SUCCESSOR(yy_input[yy_cursor + heredoc_label.length] & 0xff)) {
                if (spacing == (HEREDOC_USING_SPACES | HEREDOC_USING_TABS)) {
                    zend_throw_exception(zend_ce_parse_error, "Invalid indentation - tabs and spaces cannot be mixed",
                            0);
                    if (PARSER_MODE()) {
                        return emit_token(T_ERROR);
                    }
                }
                yy_cursor = saved_cursor;
                heredoc_label.indentation = indentation;
                BEGIN(yycST_END_HEREDOC);
                return emit_token(T_START_HEREDOC);
            }
        }

        yy_cursor = saved_cursor;

        if (is_heredoc && !heredoc_scan_ahead) {
            byte[] saved_doc_comment = CG.doc_comment;
            int heredoc_nesting_level = 1;
            int first_token = 0;
            boolean error = false;

            LexState current_state = zend_save_lexical_state();

            heredoc_scan_ahead = true;
            heredoc_indentation = 0;
            heredoc_indentation_uses_spaces = false;
            CG.doc_comment = null;

            // copy_heredoc_label_stack, oldest first
            for (HeredocLabel label : current_state.heredoc_label_stack()) {
                heredoc_label_stack.add(label.copy());
            }

            // zend_exception_save: the scan-ahead's own exceptions are cleared
            ZendCompileError saved_exception = CG.exception;
            CG.exception = null;
            zval zv = new zval();
            while (heredoc_nesting_level != 0) {
                int retval = lex_scan(zv, null);
                if (CG.exception != null) {
                    CG.exception = null;
                    break;
                }
                if (first_token == 0) {
                    first_token = retval;
                }
                switch (retval) {
                    case T_START_HEREDOC -> ++heredoc_nesting_level;
                    case T_END_HEREDOC -> --heredoc_nesting_level;
                    case END -> heredoc_nesting_level = 0;
                    default -> {
                    }
                }
            }
            // zend_exception_restore
            CG.exception = saved_exception;

            if ((first_token == T_VARIABLE || first_token == T_DOLLAR_OPEN_CURLY_BRACES
                    || first_token == T_CURLY_OPEN) && heredoc_indentation != 0) {
                zend_throw_exception(zend_ce_parse_error, "Invalid body indentation level (expecting an indentation "
                        + "level of at least " + heredoc_indentation + ")", 0);
                error = true;
            }

            heredoc_label.indentation = heredoc_indentation;
            heredoc_label.indentation_uses_spaces = heredoc_indentation_uses_spaces;

            zend_restore_lexical_state(current_state);
            heredoc_scan_ahead = false;
            CG.increment_lineno = 0;
            CG.doc_comment = saved_doc_comment;

            if (PARSER_MODE() && error) {
                return emit_token(T_ERROR);
            }
        }

        return emit_token(T_START_HEREDOC);
    }

    /** {@code <ST_END_HEREDOC>{ANY_CHAR}} */
    protected final int scan_heredoc_end() {
        HeredocLabel heredoc_label = heredoc_label_stack.remove(heredoc_label_stack.size() - 1);
        yy_leng = heredoc_label.indentation + heredoc_label.length;
        yy_cursor += yy_leng - 1;
        BEGIN(yycST_IN_SCRIPTING);
        return emit_token(T_END_HEREDOC);
    }

    private HeredocLabel heredoc_label_top() {
        return heredoc_label_stack.get(heredoc_label_stack.size() - 1);
    }

    /** {@code <ST_HEREDOC>{ANY_CHAR}} */
    protected final int scan_heredoc_content() {
        HeredocLabel heredoc_label = heredoc_label_top();
        int newline = 0;
        int indentation = 0;
        int spacing = 0;

        if (yy_cursor > yy_limit) {
            return check_nesting_at_end() != SUCCESS && PARSER_MODE() ? emit_token(T_ERROR) : emit_token(END);
        }

        yy_cursor--;

        scan:
        while (yy_cursor < yy_limit) {
            int c = yy_input[yy_cursor++];
            switch (c) {
                case '\r', '\n' -> {
                    if (c == '\r' && yy_input[yy_cursor] == '\n') {
                        yy_cursor++;
                    }
                    indentation = spacing = 0;
                    while (yy_cursor < yy_limit && (yy_input[yy_cursor] == ' ' || yy_input[yy_cursor] == '\t')) {
                        if (yy_input[yy_cursor] == '\t') {
                            spacing |= HEREDOC_USING_TABS;
                        } else {
                            spacing |= HEREDOC_USING_SPACES;
                        }
                        ++yy_cursor;
                        ++indentation;
                    }
                    if (yy_cursor == yy_limit) {
                        yy_leng = yy_cursor - yy_text;
                        HANDLE_NEWLINES(yy_text, yy_leng);
                        ZVAL_NULL(zendlval);
                        return emit_token_with_val(T_ENCAPSED_AND_WHITESPACE);
                    }
                    // check for ending label on the next line
                    if (IS_LABEL_START(yy_input[yy_cursor] & 0xff) && heredoc_label.length < yy_limit - yy_cursor
                            && Arrays.equals(yy_input, yy_cursor, yy_cursor + heredoc_label.length,
                            heredoc_label.label, 0, heredoc_label.length)) {
                        if (IS_LABEL_SUCCESSOR(yy_input[yy_cursor + heredoc_label.length] & 0xff)) {
                            continue;
                        }
                        if (spacing == (HEREDOC_USING_SPACES | HEREDOC_USING_TABS)) {
                            zend_throw_exception(zend_ce_parse_error,
                                    "Invalid indentation - tabs and spaces cannot be mixed", 0);
                            if (PARSER_MODE()) {
                                return emit_token(T_ERROR);
                            }
                        }
                        // the newline before the label is left out of the text, but yyleng/yytext include it
                        if (yy_input[yy_cursor - indentation - 2] == '\r'
                                && yy_input[yy_cursor - indentation - 1] == '\n') {
                            newline = 2; // Windows newline
                        } else {
                            newline = 1;
                        }
                        CG.increment_lineno = 1; // for the newline before the label
                        if (heredoc_scan_ahead) {
                            heredoc_indentation = indentation;
                            heredoc_indentation_uses_spaces = spacing == HEREDOC_USING_SPACES;
                        } else {
                            yy_cursor -= indentation;
                        }
                        BEGIN(yycST_END_HEREDOC);
                        break scan;
                    }
                }
                case '$' -> {
                    if (IS_LABEL_START(yy_input[yy_cursor] & 0xff) || yy_input[yy_cursor] == '{') {
                        yy_cursor--;
                        break scan;
                    }
                }
                case '{' -> {
                    if (yy_input[yy_cursor] == '$') {
                        yy_cursor--;
                        break scan;
                    }
                }
                case '\\' -> {
                    if (yy_cursor < yy_limit && yy_input[yy_cursor] != '\n' && yy_input[yy_cursor] != '\r') {
                        yy_cursor++;
                    }
                }
                default -> {
                }
            }
        }

        // heredoc_scan_done
        yy_leng = yy_cursor - yy_text;
        ZVAL_STRINGL(zendlval, yy_text, yy_leng - newline);

        if (!heredoc_scan_ahead && CG.exception == null && PARSER_MODE()) {
            boolean newline_at_start = yy_input[yy_text - 1] == '\n' || yy_input[yy_text - 1] == '\r';
            if (!strip_multiline_string_indentation(zendlval, heredoc_label.indentation,
                    heredoc_label.indentation_uses_spaces, newline_at_start, newline != 0)) {
                return emit_token(T_ERROR);
            }
            // the escapes of the text without its indentation, as php-src's copy is the stripped string
            byte[] copy = (byte[]) zendlval.value;
            if (zend_scan_escape_string(zendlval, copy, 0, copy.length, 0) != SUCCESS) {
                return emit_token(T_ERROR);
            }
        } else {
            HANDLE_NEWLINES(yy_text, yy_leng - newline);
        }
        return emit_token_with_val(T_ENCAPSED_AND_WHITESPACE);
    }

    /** {@code <ST_NOWDOC>{ANY_CHAR}} */
    protected final int scan_nowdoc_content() {
        HeredocLabel heredoc_label = heredoc_label_top();
        int newline = 0;
        int indentation = 0;
        int spacing = -1;

        if (yy_cursor > yy_limit) {
            return check_nesting_at_end() != SUCCESS && PARSER_MODE() ? emit_token(T_ERROR) : emit_token(END);
        }

        yy_cursor--;

        scan:
        while (yy_cursor < yy_limit) {
            int c = yy_input[yy_cursor++];
            if (c != '\r' && c != '\n') {
                continue;
            }
            if (c == '\r' && yy_input[yy_cursor] == '\n') {
                yy_cursor++;
            }
            indentation = spacing = 0;
            while (yy_cursor < yy_limit && (yy_input[yy_cursor] == ' ' || yy_input[yy_cursor] == '\t')) {
                if (yy_input[yy_cursor] == '\t') {
                    spacing |= HEREDOC_USING_TABS;
                } else {
                    spacing |= HEREDOC_USING_SPACES;
                }
                ++yy_cursor;
                ++indentation;
            }
            if (yy_cursor == yy_limit) {
                yy_leng = yy_cursor - yy_text;
                HANDLE_NEWLINES(yy_text, yy_leng);
                ZVAL_NULL(zendlval);
                return emit_token_with_val(T_ENCAPSED_AND_WHITESPACE);
            }
            // check for ending label on the next line
            if (IS_LABEL_START(yy_input[yy_cursor] & 0xff) && heredoc_label.length < yy_limit - yy_cursor
                    && Arrays.equals(yy_input, yy_cursor, yy_cursor + heredoc_label.length,
                    heredoc_label.label, 0, heredoc_label.length)) {
                if (IS_LABEL_SUCCESSOR(yy_input[yy_cursor + heredoc_label.length] & 0xff)) {
                    continue;
                }
                if (spacing == (HEREDOC_USING_SPACES | HEREDOC_USING_TABS)) {
                    zend_throw_exception(zend_ce_parse_error, "Invalid indentation - tabs and spaces cannot be mixed",
                            0);
                    if (PARSER_MODE()) {
                        return emit_token(T_ERROR);
                    }
                }
                // the newline before the label is left out of the text, but yyleng/yytext include it
                if (yy_input[yy_cursor - indentation - 2] == '\r' && yy_input[yy_cursor - indentation - 1] == '\n') {
                    newline = 2; // Windows newline
                } else {
                    newline = 1;
                }
                CG.increment_lineno = 1; // for the newline before the label
                yy_cursor -= indentation;
                heredoc_label.indentation = indentation;
                BEGIN(yycST_END_HEREDOC);
                break scan;
            }
        }

        // nowdoc_scan_done
        yy_leng = yy_cursor - yy_text;
        ZVAL_STRINGL(zendlval, yy_text, yy_leng - newline);

        if (CG.exception == null && spacing != -1 && PARSER_MODE()) {
            boolean newline_at_start = yy_input[yy_text - 1] == '\n' || yy_input[yy_text - 1] == '\r';
            if (!strip_multiline_string_indentation(zendlval, indentation, spacing == HEREDOC_USING_SPACES,
                    newline_at_start, newline != 0)) {
                return emit_token(T_ERROR);
            }
        }

        HANDLE_NEWLINES(yy_text, yy_leng - newline);
        return emit_token_with_val(T_ENCAPSED_AND_WHITESPACE);
    }

    /** next_newline: the offset of the next newline in str[from, end), its length in newline_len[0]; -1 if none. */
    private static int next_newline(byte[] str, int from, int end, int[] newline_len) {
        for (int i = from; i < end; i++) {
            if (str[i] == '\r') {
                newline_len[0] = i + 1 < end && str[i + 1] == '\n' ? 2 : 1;
                return i;
            } else if (str[i] == '\n') {
                newline_len[0] = 1;
                return i;
            }
        }
        newline_len[0] = 0;
        return -1;
    }

    /** strip_multiline_string_indentation: zv's string (a heredoc's text) without its body's indentation. */
    private boolean strip_multiline_string_indentation(zval zv, int indentation, boolean using_spaces,
                                                       boolean newline_at_start, boolean newline_at_end) {
        byte[] str = (byte[]) zv.value;
        int end = str.length;
        int s = 0;
        int copy = 0;
        int newline_count = 0;
        int[] newline_len = new int[1];
        int nl;

        if (!newline_at_start) {
            nl = next_newline(str, s, end, newline_len);
            if (nl < 0) {
                return true;
            }
            s = nl + newline_len[0];
            copy = nl + newline_len[0];
            newline_count++;
        } else {
            nl = s;
        }

        // <= intentional
        while (s <= end && nl >= 0) {
            nl = next_newline(str, s, end, newline_len);
            if (nl < 0 && newline_at_end) {
                nl = end;
            }
            // try to skip indentation
            for (int skip = 0; skip < indentation; skip++, s++) {
                if (s == nl) {
                    // don't require full indentation on whitespace-only lines
                    break;
                }
                if (s == end || (str[s] != ' ' && str[s] != '\t')) {
                    CG.zend_lineno += newline_count;
                    zend_throw_exception(zend_ce_parse_error, "Invalid body indentation level (expecting an "
                            + "indentation level of at least " + indentation + ")", 0);
                    ZVAL_UNDEF(zv);
                    return false;
                }
                if ((!using_spaces && str[s] == ' ') || (using_spaces && str[s] == '\t')) {
                    CG.zend_lineno += newline_count;
                    zend_throw_exception(zend_ce_parse_error, "Invalid indentation - tabs and spaces cannot be mixed",
                            0);
                    ZVAL_UNDEF(zv);
                    return false;
                }
            }
            if (s == end) {
                break;
            }
            int len = nl >= 0 ? nl - s + newline_len[0] : end - s;
            System.arraycopy(str, s, str, copy, len);
            s += len;
            copy += len;
            newline_count++;
        }
        zv.value = Arrays.copyOf(str, copy);
        return true;
    }

    // ---- what the parser asks of the scanner ----

    /**
     * zend_lex_tstring: the keyword at ident (a reserved word used as a name) as a T_STRING's value. FAILURE, with a
     * ParseError, for "<?=", which the scanner returns as T_ECHO.
     */
    public final int zend_lex_tstring(zval zv, int ident) {
        int end = ident;
        while ((yy_input[end] >= 'a' && yy_input[end] <= 'z') || (yy_input[end] >= 'A' && yy_input[end] <= 'Z')
                || yy_input[end] == '_') {
            end++;
        }
        int length = end - ident;
        if (length == 0) {
            zend_throw_exception(zend_ce_parse_error, "Cannot use \"<?=\" as an identifier", 0);
            return FAILURE;
        }
        ZVAL_STRINGL(zv, ident, length);
        return SUCCESS;
    }

    /** zend_get_scanned_file_offset (without php-src's multibyte input filter): the cursor's offset. */
    public final int zend_get_scanned_file_offset() {
        return yy_cursor - yy_start;
    }

    /** zend_stop_lexing: scanning ends at the cursor (__halt_compiler). */
    public final void zend_stop_lexing() {
        yy_cursor = yy_limit;
    }

    // ---- stacks ----

    /** zend_stack of ints (the condition stack). */
    protected static final class IntStack {
        private int[] items = new int[8];
        private int size;

        void push(int value) {
            if (size == items.length) {
                items = Arrays.copyOf(items, size * 2);
            }
            items[size++] = value;
        }

        int pop() {
            return items[--size];
        }

        boolean isEmpty() {
            return size == 0;
        }
    }

    /** zend_stack of zend_nest_location: the bracket, its line, and (for the parser) its offset. */
    protected static final class NestStack {
        private int[] items = new int[24];
        private int size;

        void push(int text, int lineno, int offset) {
            if (size + 3 > items.length) {
                items = Arrays.copyOf(items, items.length * 2);
            }
            items[size++] = text;
            items[size++] = lineno;
            items[size++] = offset;
        }

        void pop() {
            size -= 3;
        }

        boolean isEmpty() {
            return size == 0;
        }

        int topText() {
            return items[size - 3];
        }

        int topLineno() {
            return items[size - 2];
        }

        int topOffset() {
            return items[size - 1];
        }
    }
}
