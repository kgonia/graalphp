/*
 * Hand-written: php-src 8.5.12's zend_compile_string_to_ast (Zend/zend_language_scanner.l), as php-ast's
 * parse_file calls it, over the scanner and parser generated from php-src's. php-src is under the Zend Engine
 * License 2.00 (its Zend/LICENSE).
 */
package pl.codeloom.php.parser.zend;

import pl.codeloom.php.parser.Ast;
import pl.codeloom.php.parser.zend.generated.ZendLanguageParser;
import pl.codeloom.php.parser.zend.generated.ZendLanguageScanner;

import java.util.List;

/**
 * PHP source to the tree php-ast exports (version 120): php-src's scanner and grammar build php-src's AST, which
 * {@link PhpAst} turns into php-ast's shape. A syntax error is a {@link ZendCompileError}, worded and placed as
 * php-src's.
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
        ZendLexer lexer = new ZendLexer(CG, scanner);
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
        CompilerGlobals CG = new CompilerGlobals();
        ZendAst ast = zendAst(script, CG);
        return new Result((Ast) PhpAst.convert(ast), List.copyOf(CG.diagnostics));
    }
}
