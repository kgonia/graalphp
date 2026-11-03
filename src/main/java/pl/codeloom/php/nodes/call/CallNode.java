package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
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

    /** A temporary value sent to a by-reference parameter (zend_cannot_pass_by_reference). */
    @TruffleBoundary
    public static PhpError notReferenceable(PhpFunction function, int index) {
        PhpFunction.Param[] params = function.getParams();
        int last = params.length - 1;
        String name = index < params.length ? params[index].name()
                : last >= 0 && params[last].variadic() ? params[last].name() : null;
        return new PhpError(function.displayName() + "(): Argument #" + (index + 1)
                + (name == null ? "" : " ($" + name + ")") + " could not be passed by reference");
    }

    @TruffleBoundary
    public static void notice(Node site, String message, SourceLocation at) {
        ErrorFunctions.raise(PhpContext.get(site), ErrorFunctions.E_NOTICE, Str.of(message), at);
    }
}
