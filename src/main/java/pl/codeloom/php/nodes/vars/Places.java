package pl.codeloom.php.nodes.vars;

import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;

/** Shared rules for places that hold a value or a reference. */
final class Places {
    private Places() {
    }

    /** The array behind a reference, exclusive and created if null; null if it holds an object. */
    static PhpArray refArrayForWrite(PhpRef ref) {
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
