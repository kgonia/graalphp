package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;

/**
 * A class by name (ClassRefNode.named; self and parent are names here too), or `static`'s class outside a
 * late-static-binding call: the constant operand of the instruction resolving it.
 */
public final class ClassSite {
    final String name;
    /** The lowercase name's {@link pl.codeloom.php.PhpLanguage#classId}. */
    private final int id;

    public ClassSite(String name, int id) {
        this.name = name;
        this.id = id;
    }

    /** `static` in code written outside a class: no class to fall back to when no called class was passed. */
    public static ClassSite noScope() {
        return new ClassSite(null, -1);
    }

    PhpClass find(PhpContext context) {
        if (name == null) {
            throw noScopeError();
        }
        return ClassRefNode.find(context, id, name);
    }

    @TruffleBoundary
    private static PhpError noScopeError() {
        return new PhpError("Cannot access \"static\" when no class scope is active");
    }

    @Override
    public String toString() {
        return name == null ? "static" : name;
    }
}
