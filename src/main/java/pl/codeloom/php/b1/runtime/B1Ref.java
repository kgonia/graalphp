package pl.codeloom.php.b1.runtime;

import pl.codeloom.php.ir.OwnedSlot;

/** A CV, alias, or property. Aliases share the slot's one count on its contents. */
public final class B1Ref extends OwnedSlot<B1RequestContext> {
    private boolean defined, reference;
    boolean defined() { return defined; }
    boolean reference() { return reference; }
    void markReference() { reference = true; defined = true; }
    private int bindings = 1;

    public void set(B1RequestContext ctx, Object next) { defined = true; replace(ctx, next); }
    void bind() { bindings++; }
    void unbind(B1RequestContext ctx) {
        if (--bindings == 0) set(ctx, null);
    }
    @Override protected void retain(B1RequestContext ctx, Object value) { B1Object.retain(value); }
    @Override protected void release(B1RequestContext ctx, Object value) { B1Object.release(ctx, value); }
}
