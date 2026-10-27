package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

/**
 * A call of a user function, method or closure, which may return by reference. A function declared `function &f`
 * returns its reference ({@link PhpRef}); execute() dereferences it, as every value context does, and
 * executeRef() keeps it for the contexts that bind to it: `$x = &f()`, `return f();` in a function returning by
 * reference, a by-reference argument, `foreach (f() as &$v)`.
 */
public abstract class CallNode extends ExprNode {
    public static final String RETURNED_VALUE = "Only variable references should be returned by reference";
    public static final String ASSIGNED_VALUE = "Only variables should be assigned by reference";
    public static final String PASSED_VALUE = "Only variables should be passed by reference";

    /** The result as the callee returned it: a PhpRef from a function returning by reference, else the value. */
    public abstract Object executeRef(VirtualFrame frame);

    @Override
    public final Object execute(VirtualFrame frame) {
        return PhpRef.deref(executeRef(frame));
    }

    /** php-src's notice where a reference was due and a value came, at the statement around site. */
    @TruffleBoundary
    public static void notice(Node site, String message) {
        notice(site, message, SourceLocation.of(site));
    }

    @TruffleBoundary
    public static void notice(Node site, String message, SourceLocation at) {
        ErrorFunctions.raise(PhpContext.get(site), ErrorFunctions.E_NOTICE, Str.of(message), at);
    }
}
