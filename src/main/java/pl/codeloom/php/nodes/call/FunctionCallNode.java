package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;

/**
 * `name(args)`. The function is looked up in the calling context by the name's language-level id (an array
 * load), then called through an inline cache on its identity. Declarations are language-level PhpFunction
 * objects, so contexts running the same code find the same function and the call stays direct, which lets Graal
 * inline the callee. While the language serves one context, the function found first is kept as a compile-time
 * constant (functions can't be redeclared), and the lookup leaves compiled code.
 */
public final class FunctionCallNode {
    /**
     * The function a call by name calls in context: by the name's id, else (an unqualified name in a namespace)
     * the global function's; an error if neither is declared.
     */
    public static PhpFunction find(PhpContext context, String displayName, int id, int fallbackId, int site) {
        PhpFunction function = lookUp(context, id, fallbackId, site);
        if (function == null) {
            CompilerDirectives.transferToInterpreter();
            throw undefined(displayName);
        }
        return function;
    }

    /**
     * The function, or null. An unqualified name in a namespace (fallbackId) finds the namespace's function, else the
     * global one, and its site keeps the one it found first for the request (INIT_NS_FCALL_BY_NAME's cache slot),
     * though the namespace declares one later.
     */
    public static PhpFunction lookUp(PhpContext context, int id, int fallbackId, int site) {
        if (fallbackId < 0) {
            return context.functionAt(id);
        }
        PhpFunction function = context.fallbackFunction(site);
        if (function == null) {
            function = context.functionAt(id);
            if (function == null) {
                function = context.functionAt(fallbackId);
            }
            if (function != null) {
                context.keepFallbackFunction(site, function);
            }
        }
        return function;
    }

    /**
     * The built-in a call by name calls in every context, or null: a user function, or a name in a namespace,
     * whose function a context may yet declare (see Lookup).
     */
    public static PhpFunction builtinOrNull(PhpContext context, String displayName, int id, int fallbackId) {
        if (fallbackId >= 0) {
            return null;
        }
        PhpFunction function = find(context, displayName, id, fallbackId, -1);
        return function.getBuiltin() != null ? function : null;
    }

    @TruffleBoundary
    private static PhpError undefined(String displayName) {
        return new PhpError("Call to undefined function " + displayName + "()");
    }

}
