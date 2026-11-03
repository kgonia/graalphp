package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.EncapsulatingNodeReference;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.FileRootNode;
import pl.codeloom.php.nodes.vars.IncludeScope;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * include / require (and _once). The included file runs in the including scope, with its $this and static class:
 * the global scope from top-level code (or the scope that code itself was included into), and the call's
 * variables from a function ({@link IncludeScope}, made on the call's first include and kept in a frame slot).
 */
public final class IncludeNode extends ExprNode {
    @Child private ExprNode path;
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
        CallTarget file = resolve(this, this, path.execute(frame), once);
        if (file == null) {
            return Boolean.TRUE;
        }
        return run(this, file, frame.getArguments(), slots == null ? null : callScope(frame));
    }

    /**
     * The included file's call target, or null when an _once include finds the file included already. One call
     * for the path's conversion, its resolution and the file's loading, which run once per include: inlined as
     * five calls, they made top-level code with hundreds of includes (wp-settings.php) too large to install.
     * Also a function's include on bytecode (site: its instruction's node). includeSite tells the include sites
     * apart for {@link FileRootNode#includedAgain}: the node itself on the tree; on bytecode the instruction's
     * IncludeSite, since its bound node is the whole function's BytecodeNode, another object once it goes cached.
     */
    @TruffleBoundary
    public static CallTarget resolve(Node site, Object includeSite, Object pathValue, boolean once) {
        PhpContext context = PhpContext.get(site);
        String canonical = context.canonicalPath(located(Str.java(Convert.toStr(pathValue)), site));
        if (!context.markIncluded(canonical) && once) {
            return null;
        }
        FileRootNode root = context.loadFile(canonical);
        if (context.includedBefore(canonical, includeSite)) {
            root.includedAgain();
        }
        return root.getCallTarget();
    }

    /**
     * php_resolve_path with the include_path ".": a relative name that doesn't start with ./ or ../ and isn't in
     * the working directory is looked for in the directory of the file that includes it.
     */
    @TruffleBoundary
    private static String located(String path, Node site) {
        if (path.startsWith("/") || path.startsWith("./") || path.startsWith("../") || Files.exists(Path.of(path))
                || !(site.getRootNode() instanceof SourceLocation.Code code) || code.getPath() == null) {
            return path;
        }
        Path beside = Path.of(code.getPath()).resolveSibling(path);
        return Files.exists(beside) ? beside.toString() : path;
    }

    /**
     * Runs the included file, out of line too: an include runs once per request, and an inlined indirect call (its
     * arguments, its exception handling) was most of what was left of an include's compiled code. This node is the
     * call's location, as an inlined call node's parent would be, so traces list the include. What the file throws
     * (an exit(), an error) leaves the including code compiled. own: the including code's arguments; callScope:
     * a function's variables (null at the top, where the file gets the scope the including code has). site: the
     * call's location (on bytecode, the include instruction's node).
     */
    @TruffleBoundary(transferToInterpreterOnException = false)
    public static Object run(Node site, CallTarget file, Object[] own, IncludeScope callScope) {
        Object[] arguments = new Object[PhpFunction.ARG_FIRST];
        arguments[PhpFunction.ARG_THIS] = own[PhpFunction.ARG_THIS];
        arguments[PhpFunction.ARG_STATIC] = own[PhpFunction.ARG_STATIC];
        arguments[IncludeScope.ARG] = callScope != null ? callScope
                : own[IncludeScope.ARG] instanceof IncludeScope scope ? scope : null;
        EncapsulatingNodeReference encapsulating = EncapsulatingNodeReference.getCurrent();
        Node previous = encapsulating.set(site);
        try {
            return file.call(arguments);
        } finally {
            encapsulating.set(previous);
        }
    }

    private IncludeScope callScope(VirtualFrame frame) {
        return IncludeScope.forCall(frame, scopeSlot, slots, lexicalClass);
    }
}
