package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Refcount;

/**
 * A site's writes through references ({@link PhpRef#set}), with a profile of the holder updates they have met: a
 * write that has never stored an array, or replaced one, compiles without that update. PhpRef.set inlined both at
 * every write through a global, a reference variable or a reference property (about 30 IR nodes a site). A case
 * not met yet deoptimizes once. A node keeps one in a final field, so the profile is a constant in its compiled
 * code.
 */
public final class RefWrites {
    @CompilationFinal private int seen;
    private static final int NEW_ARRAY = 1;
    private static final int OLD_ARRAY = 1 << 1;
    private static final int NEW_OBJECT = 1 << 2;
    private static final int OLD_OBJECT = 1 << 3;
    private final Refcount.Releases releases = Refcount.Releases.create();

    public static RefWrites create() {
        return new RefWrites();
    }

    /** ref.set(value), as PhpRef.set does it: the new array gains the reference as a holder, the old one loses it. */
    public void set(PhpRef ref, Object value) {
        boolean holds = ref.holdsArrays();
        if (value instanceof PhpArray array) {
            met(NEW_ARRAY);
            if (holds) {
                array.addHolder();
            }
        } else if (value instanceof PhpObject object) {
            met(NEW_OBJECT);
            if (holds) {
                object.addRef();
            }
        }
        Object old = ref.exchange(value);
        if (old instanceof PhpArray array) {
            // also when it is the value: its holder was added above
            met(OLD_ARRAY);
            if (holds) {
                array.removeHolder();
            }
        } else if (old instanceof PhpObject object) {
            met(OLD_OBJECT);
            if (holds) {
                releases.release(object);
            }
        }
    }

    /** ref.set of an int or a float: only the old value can be an array. */
    public void setScalar(PhpRef ref, Object value) {
        Object old = ref.exchange(value);
        if (old instanceof PhpArray array) {
            met(OLD_ARRAY);
            if (ref.holdsArrays()) {
                array.removeHolder();
            }
        } else if (old instanceof PhpObject object) {
            met(OLD_OBJECT);
            if (ref.holdsArrays()) {
                releases.release(object);
            }
        }
    }

    private void met(int bit) {
        if ((seen & bit) == 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            seen |= bit;
        }
    }
}
