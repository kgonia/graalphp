package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.EncapsulatingNodeReference;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.bytecode.IncludeSite;
import pl.codeloom.php.bytecode.PhpBytecodeRootNode;
import pl.codeloom.php.nodes.vars.IncludeScope;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * include / require (and _once). The included file runs in the including scope, with its $this and static class:
 * the global scope from top-level code (or the scope that code itself was included into), and the call's
 * variables from a function ({@link IncludeScope}, made on the call's first include and kept in a frame slot).
 */
public final class IncludeNode {
    /**
     * The included file's call target; else the include's value: true when an _once include finds the file included
     * already, false when the file can't be opened (an include's warnings; a require's Error). One call
     * for the path's conversion, its resolution and the file's loading, which run once per include: inlined as
     * five calls, they made top-level code with hundreds of includes (wp-settings.php) too large to install.
     * Also a function's include on bytecode (site: its instruction's node). includeSite tells the include sites
     * apart for {@link FileRootNode#includedAgain}: the node itself on the tree; on bytecode the instruction's
     * IncludeSite, since its bound node is the whole function's BytecodeNode, another object once it goes cached.
     */
    @TruffleBoundary
    public static Object resolve(Node site, IncludeSite includeSite, Object pathValue, boolean once) {
        PhpContext context = PhpContext.get(site);
        String path = Str.java(Convert.toStr(pathValue));
        if (path.isEmpty()) {
            throw new PhpError("ValueError", "Path must not be empty");
        }
        String canonical = context.canonicalPath(located(path, site));
        String failure = openFailure(canonical);
        if (failure != null) {
            return failedOpening(context, site, includeSite.includeKind(), path, failure);
        }
        // included once it has opened (zend_include_or_eval), whether it then compiles or not
        if (!context.markIncluded(canonical) && once) {
            return Boolean.TRUE;
        }
        RootNode root = context.loadFile(canonical);
        if (context.includedBefore(canonical, includeSite) && root instanceof PhpBytecodeRootNode file) {
            file.includedAgain();
        }
        return root.getCallTarget();
    }

    /** Why the file can't be opened for reading (strerror's text, as php_stream_open reports it), or null. */
    private static String openFailure(String canonical) {
        Path file = Path.of(canonical);
        if (!Files.exists(file)) {
            return "No such file or directory";
        }
        if (Files.isDirectory(file)) {
            // the open succeeds, the first read fails (ENOTTY)
            return "Inappropriate ioctl for device";
        }
        return Files.isReadable(file) ? null : "Permission denied";
    }

    /**
     * zend_include_or_eval's failed open: "Failed to open stream", then for include and include_once a warning and
     * false (php_stream_open_for_zend_ex), for require and require_once an Error (zend_message_dispatcher).
     */
    private static Boolean failedOpening(PhpContext context, Node site, String kind, String path, String failure) {
        // the include's own line: this runs in the including code's frame, not a callee's
        SourceLocation at = SourceLocation.of(site);
        ErrorFunctions.raise(context, ErrorFunctions.E_WARNING,
                Str.of(kind + "(" + path + "): Failed to open stream: " + failure), at);
        String includePath = context.ini().get("include_path");
        if (kind.startsWith("require")) {
            throw new PhpError("Error", "Failed opening required '" + path + "' (include_path='" + includePath
                    + "')");
        }
        ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of(kind + "(): Failed opening '" + path
                + "' for inclusion (include_path='" + includePath + "')"), at);
        return Boolean.FALSE;
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
     * An include (ZEND_INCLUDE_OR_EVAL) as one call out of the including code: resolve, then run; true when an _once
     * include finds the file included already. One call, not two, and an exception out of it (an exit(), an error)
     * goes on in the interpreter: otherwise each such call compiles an exception path to the bytecode root's
     * handler too, and wp-settings.php's top level has hundreds of includes.
     */
    @TruffleBoundary
    public static Object include(Node site, IncludeSite includeSite, Object pathValue, boolean once, Object[] own,
                                 IncludeScope callScope) {
        Object resolved = resolve(site, includeSite, pathValue, once);
        return resolved instanceof CallTarget file ? run(site, file, own, callScope) : resolved;
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

}
