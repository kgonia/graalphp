package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import pl.codeloom.php.runtime.PhpArray;

/**
 * An array literal's place in a function (ArrayLiteralNode's per-site state): its size, whether it is a record
 * (every element a value under a distinct int or string literal key, one a string: built as a hash directly), and
 * whether the arrays it built have held anything but ints. Until then they start in the int strategy, after as an
 * Object[] sized for the elements. Made once per site; a reparse takes the same one.
 */
public final class LiteralSite {
    private final int size;
    private final boolean record;
    @CompilationFinal private boolean seenObjects;

    public LiteralSite(int size, boolean record) {
        this.size = size;
        this.record = record;
    }

    PhpArray newArray() {
        if (record) {
            return PhpArray.ofHashCapacity(size);
        }
        return seenObjects ? PhpArray.ofCapacity(size) : new PhpArray();
    }

    void built(PhpArray array) {
        if (!record && !seenObjects && !array.holdsLongs()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            seenObjects = true;
        }
    }
}
