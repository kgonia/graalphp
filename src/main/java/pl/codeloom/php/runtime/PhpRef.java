package pl.codeloom.php.runtime;

/**
 * A PHP reference: a shared box that several variables, array elements or properties point at. Slots that are
 * references hold a PhpRef instead of the value; a PhpRef never holds another PhpRef.
 */
public final class PhpRef {
    private Object value;

    /** The ref counts as a holder of an array value, like any other slot. */
    public PhpRef(Object value) {
        if (value instanceof PhpArray array) {
            array.addHolder();
        }
        this.value = value;
    }

    public Object get() {
        return value;
    }

    /** Stores a value; arrays count this ref as one more holder. */
    public void set(Object newValue) {
        Object old = value;
        if (newValue instanceof PhpArray array) {
            array.addHolder();
        }
        value = newValue;
        if (old instanceof PhpArray array && old != newValue) {
            array.removeHolder();
        }
    }

    /** The array in this ref, made exclusive (copied if shared) so it can be modified in place. */
    public PhpArray arrayForWrite() {
        Object current = value;
        if (current instanceof PhpArray array) {
            if (array.isShared()) {
                PhpArray copy = array.copy();
                copy.addHolder();
                array.removeHolder();
                value = copy;
                return copy;
            }
            return array;
        }
        return null;
    }

    public static Object deref(Object value) {
        return value instanceof PhpRef ref ? ref.value : value;
    }
}
