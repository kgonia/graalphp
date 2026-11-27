package pl.codeloom.php.b1.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** An immutable data image. Each request supplies its own symbol tables, bindings and class linkage. */
public final class B1Snapshot {
    private final Map<String,Object> globals, statics, constants;
    /** Function statics by {@link #staticKey}: a request that links the same file again finds them. */
    private final Map<String,Map<String,Object>> staticVariables;
    private final Set<String> included;
    private B1Snapshot(Map<String,Object> globals,Map<String,Object> statics,Map<String,Map<String,Object>> staticVariables,
                       Map<String,Object> constants,Set<String> included) {
        this.globals = globals;
        this.statics = statics;
        this.staticVariables = staticVariables;
        this.constants = constants;
        this.included = included;
    }
    static B1Snapshot capture(Map<String,B1Ref> globals, Map<String,B1Ref> statics,
                              Map<B1Callable,? extends Map<String,B1Ref>> staticVariables,
                              Map<String,Map<String,Object>> unboundStatics, Map<String,Object> constants, Set<String> included) {
        var copies = new IdentityHashMap<B1Array,B1Array>();
        var functionStatics = new LinkedHashMap<String,Map<String,Object>>(unboundStatics);
        staticVariables.forEach((fn,slots) -> {
            // ZEND_BIND_STATIC makes every static slot a reference; between calls only the table holds it.
            var data = new LinkedHashMap<String,Object>();
            slots.forEach((name,slot) -> {
                if (slot.shared()) throw new IllegalArgumentException("Snapshot contains a PHP reference");
                if (slot.defined()) data.put(name,pureValue(slot.get(),copies));
            });
            functionStatics.put(staticKey(fn),Collections.unmodifiableMap(data));
        });
        var constantData = new LinkedHashMap<String,Object>();
        constants.forEach((name,value) -> constantData.put(name,pureValue(value,copies)));
        return new B1Snapshot(captureSlots(globals,copies),captureSlots(statics,copies),Collections.unmodifiableMap(functionStatics),
                Collections.unmodifiableMap(constantData),Collections.unmodifiableSet(new LinkedHashSet<>(included)));
    }
    /** The request-independent name of a function for its static variables. */
    static String staticKey(B1Callable fn) { return fn.declaredFile + '\0' + fn.declaredLine + '\0' + fn.display; }
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
        return pureValue(value,copies);
    }
    private static Object pureValue(Object value,IdentityHashMap<B1Array,B1Array> copies) {
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
    /** Frozen data: the restored request binds a function's statics when it first runs that function. */
    Map<String,Map<String,Object>> staticVariables() { return staticVariables; }
    Map<String,Object> constants() { return constants; }
    Set<String> included() { return included; }
}
