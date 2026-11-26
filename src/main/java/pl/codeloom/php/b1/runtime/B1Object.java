package pl.codeloom.php.b1.runtime;

import java.util.LinkedHashMap;
import java.util.List;

/** A request-local instance. The registry tracks destructors independently of the JVM garbage collector. */
public final class B1Object {
    final long id;
    final String type;
    private final B1RequestContext.ClassDef definition;
    private final LinkedHashMap<String, B1Ref> properties = new LinkedHashMap<>();
    private int count = 1; // NEW owns its result
    private boolean destructed;
    boolean constructing = true;

    B1Object(long id, String type, B1RequestContext.ClassDef definition) {
        this.id = id;
        this.type = type;
        this.definition = definition;
        for (String name : definition.props()) properties.put(name, new B1Ref());
    }

    @HotPath(lookup = true)
    B1Callable method(B1RequestContext ctx, String folded) { return definition.methods().get(folded); }

    boolean has(String name) { return properties.containsKey(name); }
    public Object read(String name) { return properties.get(name).get(); }
    boolean accessible(B1RequestContext ctx,String name) {
        return !definition.visibility().containsKey(name) || type.equals(ctx.scope());
    }
    public B1Ref property(B1RequestContext ctx,String name) {
        if (!accessible(ctx,name)) {
            String modifier = definition.visibility().get(name) == 2 ? "protected" : "private";
            throw ctx.error("Error","Cannot access " + modifier + " property " + definition.name() + "::$" + name);
        }
        return properties.computeIfAbsent(name,unused -> new B1Ref());
    }
    B1Array publicProperties(B1RequestContext ctx) {
        B1Array result = new B1Array(properties.size());
        properties.forEach((name,ref) -> { if (accessible(ctx,name)) result.put(ctx,name,ref.get()); });
        return result;
    }
    public void write(B1RequestContext ctx, String name, Object value) {
        property(ctx,name).set(ctx,value);
    }
    void initialize(B1RequestContext ctx,String name,Object value) {
        properties.get(name).set(ctx,value);
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
        if (value instanceof B1Array.Cursor it) it.array.release(ctx);
        if (value instanceof B1Closure c) c.release(ctx);
        if (value instanceof java.util.Map<?, ?> map) releaseElements(ctx, map.values());
        if (value instanceof List<?> list) releaseElements(ctx, list);
        if (value instanceof B1Object obj) {
            if (--obj.count < 0) throw new IllegalStateException("negative PHP refcount");
            obj.disposeIfUnheld(ctx);
        }
    }

    private static void releaseElements(B1RequestContext ctx, Iterable<?> elements) {
        Throwable pending = null;
        for (Object item : elements) {
            try { release(ctx, item); }
            catch (B1Thrown next) { pending = B1Thrown.chain(ctx, next, pending); }
        }
        B1Thrown.propagate(pending);
    }

    boolean uniquelyHeld() { return count == 1; }

    void disposeIfUnheld(B1RequestContext ctx) {
        if (count != 0) return;
        Throwable pending = null;
        try { destruct(ctx); } catch (Throwable ex) { pending = ex; }
        if (count == 0) {
            for (var prop : properties.values()) {
                try { prop.set(ctx, null); } catch (Throwable ex) { pending = B1Thrown.chain(ctx, ex, pending); }
            }
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
