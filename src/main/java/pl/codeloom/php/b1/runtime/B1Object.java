package pl.codeloom.php.b1.runtime;

import java.util.LinkedHashMap;
import java.util.List;

/** A request-local instance. The registry tracks destructors independently of the JVM garbage collector. */
public non-sealed class B1Object implements B1GcNode {
    final long id;
    final String type;
    private final B1RequestContext.ClassDef definition;
    private final LinkedHashMap<String, B1Ref> properties = new LinkedHashMap<>();
    private int count = 1; // NEW owns its result
    boolean inGcRoots;
    private boolean destructed;
    boolean constructing = true;

    B1Object(B1RequestContext ctx,long id, String type, B1RequestContext.ClassDef definition) {
        this.id = id;
        this.type = type;
        this.definition = definition;
        for (String name : definition.props()) {
            B1Ref slot = new B1Ref();
            properties.put(name,slot);
            var prop = definition.properties().get(name);
            if (prop != null) slot.declare(ctx,definition.name(),name,prop);
        }
    }

    @HotPath(lookup = true)
    B1Callable method(B1RequestContext ctx, String folded) { return definition.methods().get(folded); }

    boolean has(String name) { return properties.containsKey(name); }
    public Object read(String name) { return properties.get(name).get(); }
    boolean accessible(B1RequestContext ctx,String name) {
        return !definition.visibility().containsKey(name) || type.equals(ctx.scope());
    }
    boolean allowDynamicProperties() { return true; }
    boolean magicAccess() { return definition.magicAccess(); }
    /** FETCH_OBJ_RW: an undefined property is created, then reported. */
    public B1Ref propertyRW(B1RequestContext ctx,String name) {
        boolean existed = has(name) && accessible(ctx,name);
        if (!existed && magicAccess()) throw ctx.unsupported("__get dispatch");
        B1Ref ref = property(ctx,name);
        if (!existed) ctx.diagnostic("Warning","Undefined property: " + definition.name() + "::$" + name);
        return ref;
    }
    /** FETCH_OBJ_UNSET: an existing property, or null. */
    public B1Ref existingProperty(B1RequestContext ctx,String name) {
        if (!has(name)) return null;
        return property(ctx,name);
    }
    public B1Ref property(B1RequestContext ctx,String name) {
        if (!accessible(ctx,name)) {
            String modifier = definition.visibility().get(name) == 2 ? "protected" : "private";
            throw ctx.error("Error","Cannot access " + modifier + " property " + definition.name() + "::$" + name);
        }
        B1Ref existing = properties.get(name);
        if (existing != null) return existing;
        if (!allowDynamicProperties())
            throw ctx.error("Error","Cannot create dynamic property " + definition.name() + "::$" + name);
        // PHP 8.2: user classes without #[AllowDynamicProperties] deprecate creation (stdClass allows it).
        if (definition.declaration() != null && !definition.allowDynamicProperties())
            ctx.diagnostic("Deprecated","Creation of dynamic property " + definition.name() + "::$" + name + " is deprecated");
        B1Ref created = new B1Ref();
        properties.put(name,created);
        return created;
    }
    B1Array publicProperties(B1RequestContext ctx) {
        B1Array result = new B1Array(properties.size());
        properties.forEach((name,ref) -> { if (ref.defined() && accessible(ctx,name)) result.put(ctx,name,ref.get()); });
        return result;
    }
    void dump(B1RequestContext ctx,B1Dump dump,int depth) {
        var debug = method(ctx,"__debuginfo");
        if (debug != null) {
            Object result = debug.invoke0(ctx,this);
            try {
                if (result == null) ctx.echo("object(" + definition.name() + ")#" + id + " (0) {\n" + "  ".repeat(depth) + "}\n");
                else if (result instanceof B1Array props) dump.object(definition.name(),id,props,depth);
                else throw ctx.error("TypeError","__debuginfo() must return an array");
            } finally { release(ctx,result); }
            return;
        }
        long count = properties.values().stream().filter(B1Ref::defined).count();
        ctx.echo("object(" + definition.name() + ")#" + id + " (" + count + ") {\n");
        String indent = "  ".repeat(depth + 1);
        properties.forEach((name,ref) -> {
            if (!ref.defined() && !definition.properties().containsKey(name)) return;
            String visibility = switch (definition.visibility().getOrDefault(name,1)) {
                case 2 -> ":protected";
                case 4 -> ":\"" + definition.name() + "\":private";
                default -> "";
            };
            ctx.echo(indent + "[\"" + name + "\"" + visibility + "]=>\n");
            if (ref.defined()) dump.value(ref,depth+1);
            else ctx.echo(indent + "uninitialized(" + definition.properties().get(name).type() + ")\n");
        });
        ctx.echo("  ".repeat(depth) + "}\n");
    }
    public void write(B1RequestContext ctx, String name, Object value) {
        property(ctx,name).setProperty(ctx,value);
    }

    public static Object retain(Object value) {
        if (value instanceof B1Ref ref) ref.bind();
        if (value instanceof B1Array a) a.retain();
        if (value instanceof B1Closure c) c.retain();
        if (value instanceof B1Object obj) obj.count++;
        if (value instanceof java.util.Map<?, ?> map) map.values().forEach(B1Object::retain);
        if (value instanceof List<?> list) list.forEach(B1Object::retain);
        return value;
    }

    public static void release(B1RequestContext ctx, Object value) {
        if (value instanceof B1Ref ref) ref.unbind(ctx);
        if (value instanceof B1Array a) a.release(ctx);
        if (value instanceof B1Generator.Cursor it) release(ctx,it.generator);
        if (value instanceof B1Array.Cursor it) it.array.release(ctx);
        if (value instanceof B1Array.RefCursor it) it.source.unbind(ctx);
        if (value instanceof B1Closure c) c.release(ctx);
        if (value instanceof java.util.Map<?, ?> map) releaseElements(ctx, map.values());
        if (value instanceof List<?> list) releaseElements(ctx, list);
        if (value instanceof B1Object obj) {
            if (--obj.count < 0) throw new IllegalStateException("negative PHP refcount");
            if (obj.count == 0) {
                if (obj.inGcRoots) ctx.cycles.dropped(ctx,obj,0);
                obj.disposeIfUnheld(ctx);
            } else if (!obj.inGcRoots) ctx.cycles.dropped(ctx,obj,obj.count);
        }
    }

    private static void releaseElements(B1RequestContext ctx, Iterable<?> elements) {
        Throwable pending = null;
        for (Object item : elements) {
            try { release(ctx, item); }
            catch (Throwable next) { pending = B1Thrown.chain(ctx, next, pending); }
        }
        B1Thrown.propagate(pending);
    }

    public int gcCount() { return count; }
    public void gcEdges(java.util.function.Consumer<Object> visit) { properties.values().forEach(visit); }
    void clear(B1RequestContext ctx) {
        var slots = new java.util.ArrayList<>(properties.values());
        slots.forEach(B1Ref::detachProperty);
        properties.clear();
        releaseElements(ctx,slots);
    }

    boolean uniquelyHeld() { return count == 1; }

    void disposeIfUnheld(B1RequestContext ctx) {
        if (count != 0) return;
        Throwable pending = null;
        try { destruct(ctx); } catch (Throwable ex) { pending = ex; }
        if (count == 0) {
            try { clear(ctx); } catch (Throwable ex) { pending = B1Thrown.chain(ctx, ex, pending); }
            if (inGcRoots) ctx.cycles.dropped(ctx,this,0);
            ctx.releaseObjectId(id);
        }
        B1Thrown.propagate(pending);
    }

    void destruct(B1RequestContext ctx) {
        if (destructed) return;
        destructed = true;
        ctx.forget(this);
        if (constructing || ctx.destructorsAborted()) return;
        var destructor = method(ctx,"__destruct");
        if (destructor != null) {
            count++;
            try { release(ctx, destructor.invoke0(ctx, this)); } finally { count--; }
        }
    }
}
