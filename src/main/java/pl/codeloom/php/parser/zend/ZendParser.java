/*
 * Hand-written: php-src 8.5.12's zend_compile_string_to_ast (Zend/zend_language_scanner.l), as php-ast's
 * parse_file calls it, over the scanner and parser generated from php-src's. php-src is under the Zend Engine
 * License 2.00 (its Zend/LICENSE).
 */
package pl.codeloom.php.parser.zend;

import pl.codeloom.php.parser.Ast;
import pl.codeloom.php.parser.zend.generated.ZendLanguageParser;
import pl.codeloom.php.parser.zend.generated.ZendLanguageScanner;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static pl.codeloom.php.parser.zend.derived.ZendConstants.ZEND_AST_ARROW_FUNC;
import static pl.codeloom.php.parser.zend.generated.ZendLanguageParser.Lexer.T_FUNCTION;
import static pl.codeloom.php.parser.zend.generated.ZendLanguageParser.Lexer.T_STRING;

/**
 * PHP source to the tree php-ast exports (version 120): php-src's scanner and grammar build php-src's AST, which
 * {@link PhpAst} turns into php-ast's shape. A syntax error is a {@link ZendCompileError}, worded and placed as
 * php-src's.
 * <p>
 * Function bodies can be left unparsed, as {@link SourceBody}: what php-src's parse of the whole file would give
 * for a body, parsing the body alone gives too, when the parse starts as the whole file's reaches the body's '{'.
 * The grammar has no start symbol for a body, and none is added: the parser gets the tokens
 * {@code function x()} and then the scanner's tokens from the '{' on, at the '{''s line and with its
 * CG(doc_comment), so it parses a function declaration whose statements are the body's. A '{' that opens a
 * statement list is followed by the list's reduction before any token is read (the states after a body's '{' are
 * consistent), so the list's line, and everything after, come out as in the file. Decl ids continue from where the
 * body's were in the file. Arrow functions, whose body is an expression ended by whatever token follows it, are
 * parsed with their file.
 */
public final class ZendParser {
    private ZendParser() {
    }

    /** A parsed file: its tree, and the warnings and deprecations php-src raises while compiling it. */
    public record Result(Ast ast, List<CompilerGlobals.Diagnostic> diagnostics) {
    }

    /** php-src's AST of a script (zend_compile_string_to_ast); throws its ParseError or CompileError. */
    public static ZendAst zendAst(byte[] script, CompilerGlobals CG) {
        ZendLanguageScanner scanner = new ZendLanguageScanner(CG, script);
        return parse(CG, scanner, new ZendLexer(CG, scanner));
    }

    private static ZendAst parse(CompilerGlobals CG, ZendScannerBase scanner, ZendLexer lexer) {
        ZendLanguageParser parser = new ZendLanguageParser(lexer);
        CG.ast = null;
        boolean parsed;
        try {
            parsed = parser.parse();
        } catch (ZendParserBase.MemoryExhausted e) {
            // yyexhaustedlab
            lexer.yyerror("memory exhausted");
            parsed = false;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        if (!parsed || CG.exception != null) {
            if (CG.exception == null) {
                throw new IllegalStateException("the parse failed without an error");
            }
            throw CG.exception;
        }
        return CG.ast;
    }

    /** The tree php-ast's parse_file gives for a script, every function body in it. */
    public static Result parse(byte[] script) {
        return parse(script, null, true);
    }

    /**
     * The tree php-ast's parse_file gives for a script. With text (the script's {@link ScriptText}), function,
     * method and closure bodies are left unparsed, as ranges of it, to parse again when read. shortTags: php.ini's
     * short_open_tag.
     */
    public static Result parse(byte[] script, ScriptText text, boolean shortTags) {
        CompilerGlobals CG = new CompilerGlobals();
        CG.short_tags = shortTags;
        ZendScannerBase scanner = new ZendLanguageScanner(CG, script);
        ZendAst ast = parse(CG, scanner, new ZendLexer(CG, scanner));
        Bodies bodies = text == null ? null : new Bodies(text, script, 0, scanner.brace_ends, shortTags);
        return new Result((Ast) PhpAst.convert(ast, 0, bodies), List.copyOf(CG.diagnostics));
    }

    /** Leaves each function body whose braces the parse saw as a SourceBody over the script's text. */
    private static final class Bodies implements PhpAst.FunctionBodies {
        private final ScriptText text;
        private final ScriptText.Offsets offsets;
        /** Where the bytes parsed start in the text: a body parsed again is a slice of the script. */
        private final int charBase;
        private final Map<Integer, Integer> braceEnds;
        private final boolean shortTags;

        Bodies(ScriptText text, byte[] bytes, int charBase, Map<Integer, Integer> braceEnds, boolean shortTags) {
            this.text = text;
            this.offsets = text.new Offsets(bytes);
            this.charBase = charBase;
            this.braceEnds = braceEnds;
            this.shortTags = shortTags;
        }

        @Override
        public Object leave(ZendAstDecl decl, ZendAst stmts, long firstDeclId, long declsInBody) {
            if (decl.kind == ZEND_AST_ARROW_FUNC || !(stmts instanceof ZendAstList list) || list.braceOffset < 0) {
                return null;
            }
            Integer close = braceEnds.get(list.braceOffset);
            if (close == null) {
                return null;
            }
            int from = charBase + offsets.charOffset(list.braceOffset);
            int to = charBase + offsets.charOffset(close + 1);
            return new SourceBody(text, from, to, list.braceLine, list.braceDocComment, firstDeclId, decl.kind,
                    shortTags);
        }
    }

    private static final byte[] NAME = "x".getBytes(StandardCharsets.US_ASCII);

    /** A body left unparsed (SourceBody): text[charFrom, charTo) from its '{' to its '}', parsed on its own. */
    static Ast parseBody(ScriptText text, int charFrom, int charTo, int line, byte[] docComment, long firstDeclId,
                         int declKind, boolean shortTags) {
        byte[] body = text.bytes(charFrom, charTo);
        CompilerGlobals CG = new CompilerGlobals();
        CG.short_tags = shortTags;
        ZendScannerBase scanner = new ZendLanguageScanner(CG, body);
        // the '{' is scanned in the scripting state, at its line
        scanner.yy_state = ZendLanguageScanner.yycST_IN_SCRIPTING;
        CG.zend_lineno = line;
        ZendLexer lexer = new ZendLexer(CG, scanner);
        lexer.prefix(new int[]{T_FUNCTION, T_STRING, '(', ')'},
                new Object[]{0, new ZendAstZval(NAME, 0, line), null, null}, docComment);
        ZendAst declared;
        try {
            declared = parse(CG, scanner, lexer);
        } catch (ZendCompileError e) {
            throw new IllegalStateException("a function body that parsed in its file doesn't on its own (line "
                    + line + "): " + e.getMessage(), e);
        }
        ZendAst function = ((ZendAstList) declared).child[0];
        Bodies bodies = new Bodies(text, body, charFrom, scanner.brace_ends, shortTags);
        return (Ast) PhpAst.convertBody(declKind, function.child[2], firstDeclId, bodies);
    }
}
