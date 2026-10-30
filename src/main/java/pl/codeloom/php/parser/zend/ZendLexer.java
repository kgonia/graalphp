/*
 * Hand-written: a port of php-src 8.5.12's zendlex and zenderror (Zend/zend_compile.c), of its zend_yytnamerr
 * (the epilogue of Zend/zend_language_parser.y) and of Bison 3.8.2's yysyntax_error (yacc.c), which php-src's
 * parser calls with it. php-src is under the Zend Engine License 2.00 (its Zend/LICENSE).
 */
package pl.codeloom.php.parser.zend;

import pl.codeloom.php.parser.zend.generated.ZendLanguageParser;
import pl.codeloom.php.parser.zend.generated.ZendLanguageParser.SymbolKind;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The scanner as Bison's Java parser asks for tokens (php-src's zendlex), and its syntax errors worded as
 * php-src's: "syntax error, unexpected token \"}\", expecting ..." with zend_yytnamerr's names for tokens.
 */
public final class ZendLexer implements ZendLanguageParser.Lexer {
    final CompilerGlobals CG;
    final ZendScannerBase scanner;
    private final ZendParserStackElem elem = new ZendParserStackElem();
    private final zval zv = new zval();
    private Object value;

    public ZendLexer(CompilerGlobals CG, ZendScannerBase scanner) {
        this.CG = CG;
        this.scanner = scanner;
    }

    /** zendlex */
    @Override
    public int yylex() {
        if (CG.increment_lineno != 0) {
            CG.zend_lineno++;
            CG.increment_lineno = 0;
        }
        elem.value = null;
        int ret = scanner.lex_scan(zv, elem);
        value = elem.value;
        return ret;
    }

    @Override
    public Object getLVal() {
        return value;
    }

    /** zenderror */
    @Override
    public void yyerror(String msg) {
        zenderror(msg.getBytes(StandardCharsets.UTF_8));
    }

    private void zenderror(byte[] error) {
        CG.parse_error = 0;
        if (CG.exception != null) {
            // an exception was thrown in the lexer, don't throw another in the parser
            return;
        }
        CG.exception = new ZendCompileError(true, error, CG.zend_lineno, !CG.compiled_filename, null);
    }

    /** yysyntax_error with php-src's zend_yytnamerr, then zenderror. */
    @Override
    public void reportSyntaxError(ZendLanguageParser.Context ctx) {
        // YYARGS_MAX: the unexpected token and at most four expected ones
        SymbolKind[] yyarg = new SymbolKind[5];
        int yycount = 0;
        if (ctx.getToken() != null) {
            yyarg[0] = ctx.getToken();
            yycount = 1 + ctx.getExpectedTokens(yyarg, 1, 5);
        }
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        msg.writeBytes("syntax error".getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i < yycount; i++) {
            msg.writeBytes((i == 0 ? ", unexpected " : i == 1 ? ", expecting " : " or ")
                    .getBytes(StandardCharsets.US_ASCII));
            msg.writeBytes(i == 0 ? unexpected(yytname(yyarg[i])) : expected(yytname(yyarg[i])));
        }
        zenderror(msg.toByteArray());
    }

    /**
     * The symbol's name as C's yytname has it: a string alias in double quotes ("\"'array'\"", backslashes and
     * quotes escaped as in the grammar), a character token in single quotes ("'='"), else the symbol ("T_ERROR").
     * Bison's Java tables give the alias's text, so the quotes are put back.
     */
    private static String yytname(SymbolKind symbol) {
        String name = symbol.getName();
        String id = symbol.name();
        boolean character = id.matches("S_\\d+_");
        boolean plain = id.equals("S_YYerror") || id.equals("S_" + name);
        if (character || plain) {
            return name;
        }
        return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** zend_yytnamerr for the unexpected token: its kind and, for most kinds, its text. */
    private byte[] unexpected(String yystr) {
        String toktype = yystr;
        byte[] input = scanner.yy_input;
        if (input[scanner.yy_text] == 0 && scanner.yy_leng == 1 && toktype.equals("\"end of file\"")) {
            return ascii("end of file");
        }
        // prevent the backslash getting doubled in the output
        if (toktype.equals("\"'\\\\'\"")) {
            return ascii("token \"\\\"");
        }
        // "amp" is a dummy label to avoid a duplicate token literal warning
        if (toktype.equals("\"amp\"")) {
            return ascii("token \"&\"");
        }
        // avoid unreadable """
        if (toktype.equals("'\"'")) {
            return ascii("double-quote mark");
        }
        // strip off the outer quote marks
        if (toktype.length() >= 2 && toktype.charAt(0) == '"') {
            toktype = toktype.substring(1, toktype.length() - 1);
        }
        // a token of one form has a single-quoted name, re-formatted with double quotes
        if (!toktype.isEmpty() && toktype.charAt(0) == '\'') {
            return ascii("token \"" + toktype.substring(1, toktype.length() - 1) + "\"");
        }
        // the content of the last seen token
        int tokcontent = scanner.yy_text;
        int tokcontent_len = scanner.yy_leng;
        // for T_BAD_CHARACTER, the content probably won't be a printable char
        if (tokcontent_len == 1 && yystr.equals("\"invalid character\"")) {
            return ascii(String.format("character 0x%02X", input[tokcontent] & 0xff));
        }
        // truncate at line end to avoid messing up log formats
        for (int i = 0; i < tokcontent_len; i++) {
            if (input[tokcontent + i] == '\n') {
                tokcontent_len = i;
                break;
            }
        }
        // try to be helpful about what kind of string was found, before stripping the quotes
        if (tokcontent_len > 0 && yystr.equals("\"quoted string\"")) {
            if (input[tokcontent] == '"') {
                toktype = "double-quoted string";
            } else if (input[tokcontent] == '\'') {
                toktype = "single-quoted string";
            }
        }
        // for quoted strings, strip off another layer of quotes to avoid putting quotes inside quotes
        if (tokcontent_len > 0 && (input[tokcontent] == '\'' || input[tokcontent] == '"')) {
            tokcontent++;
            tokcontent_len--;
        }
        if (tokcontent_len > 0 && (input[tokcontent + tokcontent_len - 1] == '\''
                || input[tokcontent + tokcontent_len - 1] == '"')) {
            tokcontent_len--;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii(toktype + " \""));
        // truncate to 30 characters and add a ...
        if (tokcontent_len > 30 + 3) {
            out.write(input, tokcontent, 30);
            out.writeBytes(ascii("...\""));
        } else {
            out.write(input, tokcontent, tokcontent_len);
            out.writeBytes(ascii("\""));
        }
        return out.toByteArray();
    }

    /** zend_yytnamerr for an expected token: its name, single quotes as double. */
    private static byte[] expected(String yystr) {
        String toktype = yystr;
        // prevent the backslash getting doubled in the output
        if (toktype.equals("\"'\\\\'\"")) {
            return ascii("\"\\\"");
        }
        // "amp" is a dummy label to avoid a duplicate token literal warning
        if (toktype.equals("\"amp\"")) {
            return ascii("token \"&\"");
        }
        // strip off the outer quote marks
        if (toktype.length() >= 2 && toktype.charAt(0) == '"') {
            toktype = toktype.substring(1, toktype.length() - 1);
        }
        return ascii(toktype.replace('\'', '"'));
    }
}
