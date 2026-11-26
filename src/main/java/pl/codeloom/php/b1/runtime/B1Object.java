package pl.codeloom.php.b1.runtime;

import java.util.LinkedHashMap;
import java.util.List;

/** A request-local instance. The registry tracks destructors independently of the JVM garbage collector. */
public final class B1Object {
    final String type;
    private final LinkedHashMap<String, B1Ref> properties = new LinkedHashMap<>();
    private int count = 1; // NEW owns its result
    private boolean destructed;
    boolean constructing = true;

    B1Object(String type, List<String> declared) {
        this.type = type;
        for (String name : declared) properties.put(name, new B1Ref());
    }

    boolean has(String name) { return properties.containsKey(name); }
    public Object read(String name) { return properties.get(name).get(); }
    public void write(B1RequestContext ctx, String name, Object value) {
        properties.computeIfAbsent(name, unused -> new B1Ref()).set(ctx, value);
    }

    public static Object retain(Object value) {
        if (value instanceof B1Object obj) obj.count++;
        if (value instanceof java.util.Map<?, ?> map) map.values().forEach(B1Object::retain);
        if (value instanceof List<?> list) list.forEach(B1Object::retain);
        return value;
    }

    public static void release(B1RequestContext ctx, Object value) {
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
        }
        B1Thrown.propagate(pending);
    }

    void destruct(B1RequestContext ctx) {
        if (destructed) return;
        destructed = true;
        ctx.forget(this);
        if (constructing || ctx.destructorsAborted()) return;
        var destructor = ctx.method(type, "__destruct");
        if (destructor != null) {
            count++;
            try { release(ctx, destructor.invoke0(ctx, this)); } finally { count--; }
        }
    }
}
