package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.Ini;
import pl.codeloom.php.nodes.ExprNode;
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
public final class MatchNode extends ExprNode {
    @Child private ExprNode subject;
    /** Every arm's conditions, in order. */
    @Children private final ExprNode[] conditions;
    @Children private final CompareNodes.IdenticalNode[] identical;
    /** The arm of each condition. */
    @CompilationFinal(dimensions = 1) private final int[] armOf;
    @Children private final ExprNode[] bodies;
    /** The default arm's index, -1 if there is none. */
    private final int defaultArm;
    /** Which arms have been taken; the last entry is the error. */
    @CompilationFinal(dimensions = 1) private final boolean[] taken;
    /** The line php-src gives the error (the opline's, CG(zend_lineno) when it emits it); 0 for the statement's. */
    private final int errorLine;

    public MatchNode(ExprNode subject, ExprNode[] conditions, int[] armOf, ExprNode[] bodies, int defaultArm,
                     int errorLine) {
        this.subject = subject;
        this.conditions = conditions;
        this.identical = new CompareNodes.IdenticalNode[conditions.length];
        for (int i = 0; i < conditions.length; i++) {
            identical[i] = CompareNodes.IdenticalNode.createEvaluated();
        }
        this.armOf = armOf;
        this.bodies = bodies;
        this.defaultArm = defaultArm;
        this.taken = new boolean[bodies.length + 1];
        this.errorLine = errorLine;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = subject.execute(frame);
        return evaluateArm(frame, selectArm(frame, value), value);
    }

    /** The arm of the first condition identical to the value, else the default arm (-1 if none). */
    @ExplodeLoop
    private int selectArm(VirtualFrame frame, Object value) {
        for (int i = 0; i < conditions.length; i++) {
            if (identical[i].executeIdentical(value, conditions[i].execute(frame))) {
                return armOf[i];
            }
        }
        return defaultArm;
    }

    @ExplodeLoop
    private Object evaluateArm(VirtualFrame frame, int arm, Object value) {
        for (int i = 0; i < bodies.length; i++) {
            if (i == arm) {
                if (!taken[i]) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    taken[i] = true;
                }
                return bodies[i].execute(frame);
            }
        }
        if (!taken[bodies.length]) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            taken[bodies.length] = true;
        }
        throw unhandled(this, value, errorLine);
    }

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
