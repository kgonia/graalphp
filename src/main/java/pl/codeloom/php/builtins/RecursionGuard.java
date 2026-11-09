package pl.codeloom.php.builtins;

import pl.codeloom.php.runtime.PhpArray;

import java.util.Arrays;
import java.util.IdentityHashMap;

/**
 * The recursion marks php-src keeps on the arrays and objects a walk is inside, per context, so that a walk user code
 * starts inside another (var_dump from __debugInfo, json_encode from jsonSerialize) sees the outer one's:
 * <ul>
 * <li>an array has one mark (GC_PROTECT_RECURSION) that every walk shares; a frozen array, immutable in php-src,
 * gets none;</li>
 * <li>an object has one per kind of walk (ZEND_GUARD_RECURSION_DEBUG, _EXPORT, _JSON): var_dump's and print_r's
 * are one, var_export's and json_encode's their own.</li>
 * </ul>
 * Walks nest, so the marks set are a stack: {@link #leave} clears the innermost, {@link #unwind} what a walk an
 * exception ended left.
 */
final class RecursionGuard {
    static final int ARRAY = 1;
    static final int DEBUG = 2;
    static final int EXPORT = 4;
    static final int JSON = 8;

    /** Each marked value's marks. */
    private final IdentityHashMap<Object, Integer> marks = new IdentityHashMap<>();
    /** The values entered, innermost last, with the mark each set (0 for a frozen array). */
    private Object[] values = new Object[8];
    private int[] kinds = new int[8];
    private int depth;

    /** Whether value has mark (a walk of that kind is inside it). */
    boolean isProtected(Object value, int mark) {
        Integer set = marks.get(value);
        return set != null && (set & mark) != 0;
    }

    /** Enters value with mark; false, entering nothing, if it has the mark already (a recursion). */
    boolean enter(Object value, int mark) {
        int kind = value instanceof PhpArray array && array.isFrozen() ? 0 : mark;
        if (kind != 0) {
            Integer set = marks.get(value);
            int bits = set == null ? 0 : set;
            if ((bits & kind) != 0) {
                return false;
            }
            marks.put(value, bits | kind);
        }
        if (depth == values.length) {
            values = Arrays.copyOf(values, depth * 2);
            kinds = Arrays.copyOf(kinds, depth * 2);
        }
        values[depth] = value;
        kinds[depth] = kind;
        depth++;
        return true;
    }

    /** Leaves the innermost value entered, clearing its mark. */
    void leave() {
        depth--;
        Object value = values[depth];
        values[depth] = null;
        int kind = kinds[depth];
        if (kind != 0) {
            int bits = marks.get(value) & ~kind;
            if (bits == 0) {
                marks.remove(value);
            } else {
                marks.put(value, bits);
            }
        }
    }

    int depth() {
        return depth;
    }

    /** Leaves the values entered beyond depth: what a walk that ended by an exception left marked. */
    void unwind(int depth) {
        while (this.depth > depth) {
            leave();
        }
    }
}
