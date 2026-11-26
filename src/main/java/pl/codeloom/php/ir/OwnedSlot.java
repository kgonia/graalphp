package pl.codeloom.php.ir;

/** D17 replacement is reentrant: destroying the old value may read or write this very slot. */
public abstract class OwnedSlot<C> {
    protected Object value;

    public final Object get() { return value; }

    protected final void replace(C context, Object next) {
        Object old = value;
        retain(context, next);
        value = next;
        release(context, old);
    }

    protected abstract void retain(C context, Object value);
    protected abstract void release(C context, Object value);
}
