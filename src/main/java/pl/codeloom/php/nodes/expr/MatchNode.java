package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.Ini;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Operators;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.SourceLocation;

import java.nio.charset.StandardCharsets;

/**
 * `match (subject) { conditions => body, ..., default => body }`, as php-src compiles it (zend_compile_match): the
 * subject is evaluated once, then the conditions in order, each compared to it with `===`, until one is identical;
 * that condition's arm is evaluated, and its value is the match's. With none, the default arm is; without a
 * default, UnhandledMatchError (zend_match_unhandled_error). php-src's jump table (all conditions int or string
 * literals) is the same comparison.
 *
 * <p>For partial evaluation the arms are constant: the conditions and the bodies are unrolled, and an arm's body is
 * compiled only once the arm has been taken (as is the error), so a compiled match holds the arms it has met.
 */
public final class MatchNode {
    /**
     * zend_match_unhandled_error: the value as smart_str_append_zval shows it (a scalar, a string cut to
     * zend.exception_string_param_max_len), else, or with zend.exception_ignore_args, "of type" its type. The
     * error is at errorLine of the site's file (0: the site's statement).
     */
    @TruffleBoundary
    public static PhpError unhandled(Node site, Object value, int errorLine) {
        Ini ini = PhpContext.get(site).ini();
        int maxLength = ini.exceptionStringParamMaxLen();
        ByteBuilder message = new ByteBuilder();
        if (ini.exceptionIgnoreArgs() || value instanceof TruffleString && maxLength == 0
                || !Backtrace.appendZval(message, value, maxLength)) {
            message.truncate(0);
            message.writeAscii("of type ");
            message.writeAscii(Operators.zvalTypeName(value));
        }
        PhpError error = new PhpError("UnhandledMatchError",
                "Unhandled match case " + new String(message.toByteArray(), StandardCharsets.US_ASCII));
        if (errorLine > 0) {
            SourceLocation statement = SourceLocation.of(site);
            if (!SourceLocation.UNKNOWN.equals(statement)) {
                error.locatedAt(new SourceLocation(statement.file(), errorLine));
            }
        }
        return error;
    }
}
