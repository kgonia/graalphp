package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.FileRootNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.Str;

/**
 * include / require (and _once). The included file runs in the global scope; this runtime doesn't support
 * including into a function's local scope.
 */
public final class IncludeNode extends ExprNode {
    @Child private ExprNode path;
    @Child private IndirectCallNode call = IndirectCallNode.create();
    private final boolean once;
    /** include, require, include_once or require_once, as traces name the call. */
    private final String kind;

    public IncludeNode(ExprNode path, String kind) {
        this.path = path;
        this.kind = kind;
        this.once = kind.endsWith("_once");
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
        return call.call(root.getCallTarget(), new Object[PhpFunction.ARG_FIRST]);
    }
}
