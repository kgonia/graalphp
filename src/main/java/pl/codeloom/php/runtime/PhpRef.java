package pl.codeloom.php.runtime;

/**
 * A PHP reference: a shared box that several variables, array elements or properties point at. Slots that are
 * references hold a PhpRef instead of the value; a PhpRef never holds another PhpRef.
 *
 * <p>bindings is php-src's refcount of the reference as far as the runtime knows it: the variables bound to it
 * (locals, parameters, globals, properties, static variables, closure captures) and the array slots holding it,
 * counted while their array has holders (PhpArray binds its references when it gains its first holder and
 * releases them when it loses its last; a slot overwritten or removed releases its own). A reference only one
 * array slot holds behaves as a value when that array is copied, as php-src does for a reference whose refcount
 * fell to 1 (after `foreach ($a as &$v)` has moved on, or the function that bound it returned). Holders the
 * runtime can't see go away (an outer array no longer referenced, a reference no variable is bound to) leave
 * counts higher, never lower: such a reference stays shared, as it would in php-src until the holder is freed.
 */
public final class PhpRef {
    private Object value;
    private int bindings;

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

    /** A variable is now bound to this reference, or an array element made it. */
    public void bind() {
        bindings++;
    }

    /** A variable bound to this reference was rebound, unset or went out of scope. */
    public void unbind() {
        if (bindings > 0) {
            bindings--;
        }
    }

    /** More than one holder: php-src's refcount above 1, where var_dump marks the value with "&". */
    public boolean isShared() {
        return bindings > 1;
    }

    /**
     * This reference as an element of a copy of source: the value if source's slot alone holds it (a slot counts
     * only while its array has holders), else itself. A reference to the source array itself (`$a[] = &$a`) stays
     * one, as in php-src.
     */
    public Object copyElement(PhpArray source, boolean sourceCounted) {
        return bindings <= (sourceCounted ? 1 : 0) && value != source ? value : this;
    }

    public static Object deref(Object value) {
        return value instanceof PhpRef ref ? ref.value : value;
    }
}
