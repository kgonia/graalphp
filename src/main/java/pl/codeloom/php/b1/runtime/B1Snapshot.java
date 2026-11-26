package pl.codeloom.php.b1.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** An immutable data image. Each request supplies its own symbol tables, bindings and class linkage. */
public final class B1Snapshot {
    private final Map<String,Object> globals, statics;
    private B1Snapshot(Map<String,Object> globals,Map<String,Object> statics) {
        this.globals = globals;
        this.statics = statics;
    }
    static B1Snapshot capture(Map<String,B1Ref> globals, Map<String,B1Ref> statics) {
        var copies = new IdentityHashMap<B1Array,B1Array>();
        return new B1Snapshot(captureSlots(globals,copies),captureSlots(statics,copies));
    }
    private static Map<String,Object> captureSlots(Map<String,B1Ref> slots,IdentityHashMap<B1Array,B1Array> copies) {
        var data = new LinkedHashMap<String,Object>();
        slots.forEach((name,slot) -> {
            if (slot != null && slot.defined()) data.put(name,pure(slot,copies));
        });
        return Collections.unmodifiableMap(data);
    }
    static Object pure(Object value,IdentityHashMap<B1Array,B1Array> copies) {
        if (value instanceof B1Ref ref) {
            if (ref.reference() || ref.shared()) throw new IllegalArgumentException("Snapshot contains a PHP reference");
            value = ref.get();
        }
        if (value instanceof B1Array array) return array.snapshot(copies);
        requireScalar(value);
        return value;
    }
    static void requireScalar(Object value) {
        if (value != null && !(value instanceof B1Bytes || value instanceof String || value instanceof Long
                || value instanceof Double || value instanceof Boolean))
            throw new IllegalArgumentException("Snapshot contains " + value.getClass().getSimpleName());
    }
    void restore(B1RequestContext ctx,Map<String,B1Ref> targetGlobals,Map<String,B1Ref> targetStatics) {
        globals.forEach((name,value) -> targetGlobals.computeIfAbsent(name,k -> new B1Ref()).set(ctx,value));
        statics.forEach((name,value) -> targetStatics.computeIfAbsent(name,k -> new B1Ref()).set(ctx,value));
    }
}
