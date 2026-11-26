package pl.codeloom.php.b1.runtime;

import pl.codeloom.php.ir.OwnedSlot;

/** A CV, alias, or property. Aliases share the slot's one count on its contents. */
public final class B1Ref extends OwnedSlot<B1RequestContext> {
    private boolean defined, reference;
    boolean defined() { return defined; }
    boolean reference() { return reference; }
    public B1Ref asReference() { markReference(); return this; }
    void markReference() { reference = true; defined = true; }
    private int bindings = 1;

    public void set(B1RequestContext ctx, Object next) { defined = true; replace(ctx, next); }
    public void unsetElement(B1RequestContext ctx,Object key) {
        Object base = get();
        if (base instanceof B1Array array) {
            Object canonical = ctx.arrayKey(key);
            if (array.contains(canonical)) writable(ctx).remove(ctx,canonical);
        } else if (base instanceof B1Bytes || base instanceof String)
            throw ctx.error("Error","Cannot unset string offsets");
        else if (base instanceof B1Object) throw ctx.error("Error","Cannot use object of type " + ctx.typeName(base) + " as array");
    }
    public Object increment(B1RequestContext ctx,boolean up,boolean prefix) {
        Object old = get(), value = ctx.add(old,up ? 1L : -1L);
        set(ctx,value);
        return prefix ? value : old;
    }
    B1Array writable(B1RequestContext ctx) {
        Object old = get();
        B1Array array = ctx.writableArray(old);
        if (array != old) { set(ctx,array); array.release(ctx); }
        return array;
    }
    public B1Ref child(B1RequestContext ctx,Object key,boolean append) {
        if (!append && (get() instanceof B1Bytes || get() instanceof String)) throw ctx.error("Error","Cannot use string offset as an array");
        B1Array array = writable(ctx);
        return array.reference(ctx,append ? array.appendKey(ctx) : ctx.arrayKey(key));
    }
    public void writeElement(B1RequestContext ctx,Object key,boolean append,Object value) {
        if (!append && (get() instanceof B1Bytes || get() instanceof String)) {
            byte[] bytes = B1Bytes.bytes(get());
            Long offset = ctx.stringOffset(key,false);
            if (offset == null) throw ctx.error("TypeError","Cannot access offset of type " + ctx.typeName(key) + " on string");
            long index = offset < 0 ? offset + bytes.length : offset;
            if (index < 0) { ctx.diagnostic("Warning","Illegal string offset " + offset); return; }
            byte[] source = ctx.stringBytes(value);
            if (source.length == 0) throw ctx.error("Error","Cannot assign an empty string to a string offset");
            if (source.length > 1) ctx.diagnostic("Warning","Only the first byte will be assigned to the string offset");
            byte[] result = java.util.Arrays.copyOf(bytes,Math.max(bytes.length,Math.toIntExact(index + 1)));
            if (index > bytes.length) java.util.Arrays.fill(result,bytes.length,(int)index,(byte)' ');
            result[(int)index] = source[0];
            set(ctx,B1Bytes.of(result)); return;
        }
        B1Array array = writable(ctx);
        if (append) array.append(ctx,value); else array.put(ctx,key,value);
    }
    boolean shared() { return bindings > 1; }
    void bind() { bindings++; }
    void unbind(B1RequestContext ctx) {
        if (--bindings == 0) set(ctx, null);
    }
    @Override protected void retain(B1RequestContext ctx, Object value) { B1Object.retain(value); }
    @Override protected void release(B1RequestContext ctx, Object value) { B1Object.release(ctx, value); }
}
