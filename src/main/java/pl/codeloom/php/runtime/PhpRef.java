package pl.codeloom.php.runtime;

/**
 * A PHP reference: a shared box that several variables, array elements or properties point at. Slots that are
 * references hold a PhpRef instead of the value; a PhpRef never holds another PhpRef.
 *
 * <p>bindings approximates php-src's refcount of the reference: the variables bound to it (locals, parameters,
 * globals, properties, static variables, closure captures) and the array elements made references (`&$a[k]`,
 * `$a[k] = &$x`, `[&$x]`, `foreach ($a as &$v)`). A reference only one of them still holds (after `foreach`
 * has moved on, or the function that bound it returned) behaves as a value when its array is copied, as
 * php-src does for a reference whose refcount fell to 1. Variables release their binding; array elements and
 * the bindings that last for good (globals, statics, captures) never do, which only keeps such references
 * shared. An array copy that keeps a reference doesn't count it again: a reference two arrays share only by
 * copying copies as a value once its other holders are gone (WordPress copies arrays whose `foreach` reference
 * is still bound, and must see them unshared once the function returns), where php-src would keep it.
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

    /**
     * This reference as an element of a copy of source: the value if the element alone holds it, else itself. A
     * reference to the source array itself (`$a[] = &$a`) stays one, as in php-src.
     */
    public Object copyElement(PhpArray source) {
        return bindings <= 1 && value != source ? value : this;
    }

    public static Object deref(Object value) {
        return value instanceof PhpRef ref ? ref.value : value;
    }
}
