package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;

/**
 * The rules for a function's local variable, whatever stores it: a frame slot ({@link LocalVarNode}) or a
 * bytecode local. A variable holds a value, or a {@link PhpRef} when it is a reference; it counts as a holder of
 * the array it holds directly. Each method takes what the variable stored before (null when unset) and says what
 * changes besides the store itself, which the caller makes.
 */
public final class Variables {
    private Variables() {
    }

    /** The variable's value: a reference's content; null (unset) reads as PHP null. */
    public static Object value(Object stored) {
        if (stored instanceof PhpRef ref) {
            return ref.get();
        }
        return stored == null ? PhpNull.NULL : stored;
    }

    /** A write stored value over old: the array old held directly loses this variable as a holder. */
    public static void replaced(Object old, Object value) {
        if (old instanceof PhpArray array && old != value) {
            array.removeHolder();
        }
    }

    /** The variable gives up what it stored (unset, rebinding, the end of its function). */
    public static void released(Object old) {
        if (old instanceof PhpArray array) {
            array.removeHolder();
        } else if (old instanceof PhpRef ref) {
            ref.unbind();
        }
    }

    /**
     * The reference a variable that isn't one becomes (`&$x`, a by-reference argument): it takes over the
     * variable's value, and its array's holder moves from the variable to the reference; the variable is bound.
     */
    public static PhpRef newRef(Object stored) {
        PhpRef ref = new PhpRef(stored == null ? PhpNull.NULL : stored);
        if (stored instanceof PhpArray array) {
            array.removeHolder();
        }
        ref.bind();
        return ref;
    }

    /** A shared array the variable writes into: its own copy, which it holds instead. */
    public static PhpArray separated(PhpArray shared) {
        PhpArray copy = shared.copy();
        copy.addHolder();
        shared.removeHolder();
        return copy;
    }

    /** An unset, null or false variable written as an array (`$a[] = 1`) gets a new one, which it holds. */
    public static PhpArray created() {
        PhpArray created = new PhpArray();
        created.addHolder();
        return created;
    }

    /** Whether a stored value becomes an array when written as one. */
    public static boolean becomesArray(Object stored) {
        return stored == null || stored == PhpNull.NULL || stored == Boolean.FALSE;
    }

    @TruffleBoundary
    public static PhpError scalarAsArray() {
        return new PhpError("Cannot use a scalar value as an array");
    }
}
