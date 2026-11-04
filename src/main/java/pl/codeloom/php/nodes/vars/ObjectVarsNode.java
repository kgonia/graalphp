package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpObject;

/**
 * A direct call of get_object_vars($object): the properties the calling scope may access (inside the class, its
 * protected and private ones too).
 */
public final class ObjectVarsNode {
    /** The properties of value scope (a class, lowercase, or null) may access; also get_object_vars() on bytecode. */
    public static Object objectVars(Object value, String scope) {
        if (value instanceof PhpObject phpObject) {
            return phpObject.visibleProperties(scope);
        }
        throw notAnObject(value);
    }

    @TruffleBoundary
    private static PhpError notAnObject(Object value) {
        return new PhpError("TypeError", "get_object_vars(): Argument #1 ($object) must be of type object, "
                + Convert.typeName(value) + " given");
    }
}
