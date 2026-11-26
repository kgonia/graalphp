package pl.codeloom.php.b1.runtime;

import pl.codeloom.php.ir.OwnedSlot;

/** A CV, alias, or property. Aliases share the slot's one count on its contents. */
public final class B1Ref extends OwnedSlot<B1RequestContext> implements B1GcNode {
    private boolean defined, reference;
    boolean defined() { return defined; }
    boolean reference() { return reference; }
    public B1Ref asReference(B1RequestContext ctx) { markReference(ctx); return this; }
    void markReference(B1RequestContext ctx) {
        if (!defined && type != null && !nullable()) throw ctx.error("Error",
                "Cannot access uninitialized non-nullable property " + property + " by reference");
        reference = true;
        defined = true;
    }
    private boolean nullable() { return type.equals("mixed") || type.startsWith("?"); }
    boolean inGcRoots;
    private int bindings = 1;

    private String type, property;
    void declare(B1RequestContext ctx,String owner,String name,B1RequestContext.Property declaration) {
        type = declaration.type();
        property = owner + "::$" + name;
        if (!defined && declaration.initialized()) setProperty(ctx,declaration.initial());
    }
    public Object readProperty(B1RequestContext ctx) {
        if (type != null && !defined) throw ctx.error("Error","Typed property " + property + " must not be accessed before initialization");
        return get();
    }
    public void set(B1RequestContext ctx, Object next) {
        assign(ctx,next,true);
    }
    public void setProperty(B1RequestContext ctx, Object next) {
        assign(ctx,next,false);
    }
    private void assign(B1RequestContext ctx, Object next, boolean throughReference) {
        if (type != null && !type.equals("mixed") && !(next == null && nullable())) {
            String required = type.startsWith("?") ? type.substring(1) : type;
            Object coerced = B1Frame.scalar(ctx,next,required,ctx.strict());
            if (coerced == null) throw ctx.error("TypeError","Cannot assign " + ctx.typeName(next)
                    + (throughReference ? " to reference held by property " : " to property ") + property + " of type " + type);
            next = coerced;
        }
        defined = true;
        replace(ctx, next);
    }
    void detachProperty() { property = type = null; }
    void clear(B1RequestContext ctx) { defined = false; replace(ctx,null); }
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
        Object old = readProperty(ctx), value = ctx.add(old,up ? 1L : -1L);
        set(ctx,value);
        return prefix ? value : old;
    }
    B1Array writable(B1RequestContext ctx) {
        Object old = get();
        B1Array array = ctx.writableArray(old);
        if (array != old) {
            try { set(ctx,array); } finally { array.release(ctx); }
        }
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
    public int gcCount() { return bindings; }
    boolean shared() { return bindings > 1; }
    void bind() { bindings++; }
    void unbind(B1RequestContext ctx) {
        int remaining = --bindings;
        if (inGcRoots != (remaining > 0)) ctx.cycles.dropped(ctx,this,remaining);
        if (remaining == 0) clear(ctx);
    }
    @Override protected void retain(B1RequestContext ctx, Object value) { B1Object.retain(value); }
    @Override protected void release(B1RequestContext ctx, Object value) { B1Object.release(ctx, value); }
}
