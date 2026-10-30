/*
 * Hand-written: the compiler globals (CG) php-src 8.5.12's scanner and parser share (Zend/zend_globals.h), and
 * the one executor global they use, EG(exception). php-src is under the Zend Engine License 2.00 (its Zend/LICENSE).
 */
package pl.codeloom.php.parser.zend;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * php-src's CG(...) as the scanner and the grammar's actions use them, one object per parse, under php-src's
 * names. EG(exception), the ParseError or CompileError thrown while parsing, is here too.
 */
public final class CompilerGlobals {
    /** zend_error's types the scanner and parser raise (php-src's values). */
    public static final int E_COMPILE_WARNING = 128;
    public static final int E_DEPRECATED = 8192;

    /** A warning or deprecation raised while parsing: zend_error's type, message and the line it was raised on. */
    public record Diagnostic(int type, String message, int line) {
    }

    public int zend_lineno = 1;
    public int increment_lineno;
    public byte[] doc_comment;
    public int extra_fn_flags;
    public int parse_error;
    public ZendAst ast;
    /** short_open_tag: PHP's default is on (php.ini-production turns it off). */
    public boolean short_tags = true;
    /** EG(exception): the first ParseError or CompileError, or null. */
    public ZendCompileError exception;
    /**
     * Whether CG(compiled_filename) is set, as it is while a file is compiled except during the heredoc
     * scan-ahead (zend_save_lexical_state clears it): an exception thrown without it takes the location of the
     * code that is running (the include), as php-src's exceptions do.
     */
    public boolean compiled_filename = true;
    /** zend_error's warnings and deprecations, in the order raised. */
    public final List<Diagnostic> diagnostics = new ArrayList<>();

    /**
     * zend_throw_exception with a ParseError or CompileError at the current line. Like php-src, a second exception
     * while one is pending becomes the new one, the earlier its previous.
     */
    public void zend_throw_exception(boolean parseError, String message) {
        exception = new ZendCompileError(parseError, message.getBytes(StandardCharsets.UTF_8), zend_lineno,
                !compiled_filename, exception);
    }

    /** zend_error during compilation: recorded, for the runtime to raise when the file is compiled. */
    public void zend_error(int type, String message) {
        diagnostics.add(new Diagnostic(type, message, zend_lineno));
    }
}
