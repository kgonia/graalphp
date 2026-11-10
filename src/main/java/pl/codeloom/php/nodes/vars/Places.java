package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;

/** Shared rules for places that hold a value or a reference. */
public final class Places {
    private Places() {
    }

    /**
     * The array behind a reference, exclusive and created if null; null if it holds an object. Compiled, it is the
     * array that is the reference's alone, and one call for the rest (copied if shared, created, an object, the
     * error): with the three inline, each `$q['x'] = ...` through `$q = &$this->query_vars` was 0.9 KB of machine
     * code, and where their paths met the compiler at times copied the store that follows for each
     * (wiki/ceiling.md).
     */
    public static PhpArray refArrayForWrite(PhpRef ref) {
        if (ref.get() instanceof PhpArray array && !array.isShared()) {
            return array;
        }
        return otherForWrite(ref);
    }

    @TruffleBoundary
    private static PhpArray otherForWrite(PhpRef ref) {
        Object inner = ref.get();
        if (inner instanceof PhpArray) {
            return ref.arrayForWrite();
        }
        if (inner == PhpNull.NULL || inner == Boolean.FALSE) {
            PhpArray created = new PhpArray();
            ref.set(created);
            return created;
        }
        if (inner instanceof PhpObject) {
            return null;
        }
        throw new PhpError("Cannot use a scalar value as an array");
    }
}
