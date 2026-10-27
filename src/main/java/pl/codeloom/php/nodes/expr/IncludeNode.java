package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.FileRootNode;
import pl.codeloom.php.nodes.vars.FrameSlots;
import pl.codeloom.php.nodes.vars.IncludeScope;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.Str;

import java.util.Map;

/**
 * include / require (and _once). The included file runs in the including scope, with its $this and static class:
 * the global scope from top-level code (or the scope that code itself was included into), and the call's
 * variables from a function ({@link IncludeScope}, made on the call's first include and kept in a frame slot).
 */
public final class IncludeNode extends ExprNode {
    @Child private ExprNode path;
    @Child private IndirectCallNode call = IndirectCallNode.create();
    private final boolean once;
    /** include, require, include_once or require_once, as traces name the call. */
    private final String kind;
    /** In a function: its variables' slots by name, and the slot keeping the call's IncludeScope; null at the top. */
    private final Map<String, Integer> slots;
    private final int scopeSlot;
    /** The class (lowercase) the including function is written in, or null. */
    private final String lexicalClass;

    public IncludeNode(ExprNode path, String kind, Map<String, Integer> slots, int scopeSlot, String lexicalClass) {
        this.path = path;
        this.kind = kind;
        this.once = kind.endsWith("_once");
        this.slots = slots;
        this.scopeSlot = scopeSlot;
        this.lexicalClass = lexicalClass;
    }

    @Override
    public String includeKind() {
        return kind;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpContext context = PhpContext.get(this);
        String canonical = context.canonicalPath(Str.java(Convert.toStr(path.execute(frame))));
        if (!context.markIncluded(canonical) && once) {
            return Boolean.TRUE;
        }
        FileRootNode root = context.loadFile(canonical);
        Object[] own = frame.getArguments();
        Object[] arguments = new Object[PhpFunction.ARG_FIRST];
        arguments[PhpFunction.ARG_THIS] = own[PhpFunction.ARG_THIS];
        arguments[PhpFunction.ARG_STATIC] = own[PhpFunction.ARG_STATIC];
        arguments[IncludeScope.ARG] = slots == null ? IncludeScope.of(frame) : callScope(frame);
        return call.call(root.getCallTarget(), arguments);
    }

    private IncludeScope callScope(VirtualFrame frame) {
        if (frame.isObject(scopeSlot) && frame.getObject(scopeSlot) instanceof IncludeScope scope) {
            return scope;
        }
        // a closure's scope is the class it is bound to
        String classScope = frame.getArguments()[PhpFunction.ARG_CLOSURE] instanceof PhpClosure closure
                ? closure.getScope() == null ? null : closure.getScope().getLowerName() : lexicalClass;
        IncludeScope scope = newScope(frame.materialize(), slots, classScope);
        FrameSlots.setObject(frame, scopeSlot, scope);
        return scope;
    }

    @TruffleBoundary
    private static IncludeScope newScope(MaterializedFrame frame, Map<String, Integer> slots, String classScope) {
        return new IncludeScope(frame, slots, classScope);
    }
}
