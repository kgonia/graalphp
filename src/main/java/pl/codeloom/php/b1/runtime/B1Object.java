package pl.codeloom.php.b1.runtime;

import java.util.List;

/** A request-local instance. The registry tracks destructors independently of the JVM garbage collector.
 * Declared properties live in a flat slot array laid out by the class; dynamic ones follow in creation order. A
 * slot holds its value directly, or a B1Ref once it is typed, undefined, or fetched as a place (a reference). */
public non-sealed class B1Object implements B1GcNode {
    private static final Object[] NO_SLOTS = {};
    final long id;
    final String type;
    private final B1RequestContext.ClassDef definition;
    private final B1RequestContext.MethodSites layout;
    private final Object[] slots;
    private String[] dynNames;
    private B1Bytes[] dynKeys;
    private Object[] dynValues;
    private int dynCount;
    private java.util.HashMap<String,Integer> dynIndex;
    private int count = 1; // NEW owns its result
    boolean inGcRoots;
    int gcSlot = -1;
    private boolean destructed;
    boolean constructing = true;

    B1Object(B1RequestContext ctx,long id, String type, B1RequestContext.ClassDef definition) {
        this.id = id;
        this.type = type;
        this.definition = definition;
        layout = definition.sites();
        int n = layout.names.length;
        slots = n == 0 ? NO_SLOTS : new Object[n];
        for (int i = 0; i < n; i++) {
            var prop = layout.declarations[i];
            if (prop != null && prop.type() == null && prop.initialized()) slots[i] = retain(prop.initial());
            else {
                B1Ref slot = new B1Ref();
                if (prop != null) slot.declare(ctx,definition.name(),layout.names[i],prop);
                slots[i] = slot;
            }
        }
    }

    B1RequestContext.ClassDef definition() { return definition; }
    @HotPath(lookup = true)
    B1Callable method(B1RequestContext ctx, String folded) { return definition.methods().get(folded); }

    private static Object value(Object slot) { return slot instanceof B1Ref ref ? ref.get() : slot; }
    private static boolean defined(Object slot) { return !(slot instanceof B1Ref ref) || ref.defined(); }
    /** The declared slot of a property name, or -1. */
    int slot(String name) {
        Integer i = layout.index.get(name);
        return i == null ? -1 : i;
    }
    private int dynamic(String name) {
        if (dynCount == 0) return -1;
        if (dynIndex != null) {
            Integer i = dynIndex.get(name);
            return i == null ? -1 : i;
        }
        for (int i = 0; i < dynCount; i++) if (dynNames[i].equals(name)) return i;
        return -1;
    }
    private int addDynamic(String name, B1Bytes key, Object value) {
        if (dynNames == null) {
            dynNames = new String[4]; dynKeys = new B1Bytes[4]; dynValues = new Object[4];
        } else if (dynCount == dynNames.length) {
            int capacity = dynCount * 2;
            dynNames = java.util.Arrays.copyOf(dynNames, capacity);
            dynKeys = java.util.Arrays.copyOf(dynKeys, capacity);
            dynValues = java.util.Arrays.copyOf(dynValues, capacity);
        }
        int i = dynCount++;
        dynNames[i] = name; dynKeys[i] = key; dynValues[i] = value;
        if (dynIndex != null) dynIndex.put(name, i);
        else if (dynCount > 8) {
            dynIndex = new java.util.HashMap<>();
            for (int j = 0; j < dynCount; j++) dynIndex.put(dynNames[j], j);
        }
        return i;
    }
    private B1Bytes dynamicKey(int i) {
        B1Bytes key = dynKeys[i];
        if (key == null) dynKeys[i] = key = B1Bytes.ofUtf8(dynNames[i]);
        return key;
    }
    private boolean visible(B1RequestContext ctx, int slot) {
        return layout.visibility[slot] == 1 || type.equals(ctx.scope());
    }

    boolean has(String name) { return slot(name) >= 0 || dynamic(name) >= 0; }
    public Object read(String name) {
        int i = slot(name);
        if (i >= 0) return value(slots[i]);
        int d = dynamic(name);
        return d < 0 ? null : value(dynValues[d]);
    }
    boolean accessible(B1RequestContext ctx,String name) {
        int i = slot(name);
        return i < 0 || visible(ctx,i);
    }
    boolean allowDynamicProperties() { return true; }
    boolean magicAccess() { return definition.magicAccess(); }
    /** A declared, visible property's value at a call site (typed ones check initialization), or MISSING. */
    Object readAt(B1RequestContext ctx, String name, int symbol) {
        int i = layout.slotAt(name, symbol);
        if (i < 0 || !visible(ctx, i)) return B1Array.MISSING;
        Object v = slots[i];
        return v instanceof B1Ref ref ? ref.readProperty(ctx) : v;
    }
    /** isset of a declared, visible property at a call site, or MISSING. */
    Object quietAt(B1RequestContext ctx, String name, int symbol) {
        int i = layout.slotAt(name, symbol);
        if (i < 0 || !visible(ctx, i)) return B1Array.MISSING;
        return value(slots[i]);
    }
    /** $obj->name as a place at a call site. */
    public B1Ref propertyAt(B1RequestContext ctx, String name, int symbol) {
        int i = layout.slotAt(name, symbol);
        if (i >= 0 && visible(ctx, i)) return box(slots, i);
        return property(ctx, name);
    }
    /** $obj->name = value at a call site. */
    public void writeAt(B1RequestContext ctx, String name, int symbol, Object value) {
        int i = layout.slotAt(name, symbol);
        if (i >= 0 && visible(ctx, i)) { assign(ctx, slots, i, value); return; }
        write(ctx, name, value);
    }
    /** FETCH_OBJ_R of a declared property visible from the current scope, or MISSING for every other case. */
    Object readVisible(B1RequestContext ctx, String name) {
        int i = slot(name);
        if (i < 0 || !visible(ctx, i)) return B1Array.MISSING;
        Object v = slots[i];
        return v instanceof B1Ref ref ? ref.readProperty(ctx) : v;
    }
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
    private static B1Ref box(Object[] table, int i) {
        Object v = table[i];
        if (v instanceof B1Ref ref) return ref;
        B1Ref ref = new B1Ref();
        ref.adopt(v);
        table[i] = ref;
        return ref;
    }
    public B1Ref property(B1RequestContext ctx,String name) {
        int i = slot(name);
        if (i >= 0) {
            if (!visible(ctx,i)) {
                String modifier = layout.visibility[i] == 2 ? "protected" : "private";
                throw ctx.error("Error","Cannot access " + modifier + " property " + definition.name() + "::$" + name);
            }
            return box(slots, i);
        }
        int d = dynamic(name);
        if (d >= 0) return box(dynValues, d);
        B1Ref created = new B1Ref();
        createDynamic(ctx, name, null, created);
        return created;
    }
    private void createDynamic(B1RequestContext ctx, String name, B1Bytes key, Object value) {
        createDynamic(ctx, name, key, value, false);
    }
    /** The guards (and the deprecation, which can run no PHP code) come first; only then is the value counted. */
    private void createDynamic(B1RequestContext ctx, String name, B1Bytes key, Object value, boolean count) {
        if (!allowDynamicProperties())
            throw ctx.error("Error","Cannot create dynamic property " + definition.name() + "::$" + name);
        // PHP 8.2: user classes without #[AllowDynamicProperties] deprecate creation (stdClass allows it).
        if (definition.declaration() != null && !definition.allowDynamicProperties())
            ctx.diagnostic("Deprecated","Creation of dynamic property " + definition.name() + "::$" + name + " is deprecated");
        addDynamic(name, key, count ? retain(value) : value);
    }
    B1Array publicProperties(B1RequestContext ctx) {
        B1Array result = B1Array.hashed(slots.length + dynCount);
        boolean inScope = type.equals(ctx.scope());
        for (int i = 0; i < slots.length; i++) {
            Object v = slots[i];
            if (v instanceof B1Ref ref) { if (!ref.defined()) continue; v = ref.get(); }
            if (layout.visibility[i] != 1 && !inScope) continue;
            result.put(ctx,layout.keys[i],v);
        }
        for (int i = 0; i < dynCount; i++) {
            Object v = dynValues[i];
            if (v instanceof B1Ref ref) { if (!ref.defined()) continue; v = ref.get(); }
            result.put(ctx,dynamicKey(i),v);
        }
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
        long count = 0;
        for (Object v : slots) if (defined(v)) count++;
        for (int i = 0; i < dynCount; i++) if (defined(dynValues[i])) count++;
        ctx.echo("object(" + definition.name() + ")#" + id + " (" + count + ") {\n");
        String indent = "  ".repeat(depth + 1);
        for (int i = 0; i < slots.length; i++) {
            String name = layout.names[i];
            Object v = slots[i];
            if (!defined(v) && !definition.properties().containsKey(name)) continue;
            String visibility = switch (layout.visibility[i]) {
                case 2 -> ":protected";
                case 4 -> ":\"" + definition.name() + "\":private";
                default -> "";
            };
            ctx.echo(indent + "[\"" + name + "\"" + visibility + "]=>\n");
            if (defined(v)) dump.value(v,depth+1);
            else ctx.echo(indent + "uninitialized(" + definition.properties().get(name).type() + ")\n");
        }
        for (int i = 0; i < dynCount; i++) {
            if (!defined(dynValues[i])) continue;
            ctx.echo(indent + "[\"" + dynNames[i] + "\"]=>\n");
            dump.value(dynValues[i],depth+1);
        }
        ctx.echo("  ".repeat(depth) + "}\n");
    }
    public void write(B1RequestContext ctx, String name, Object value) {
        int i = slot(name);
        if (i >= 0 && visible(ctx,i)) { assign(ctx, slots, i, value); return; }
        if (i < 0) {
            int d = dynamic(name);
            if (d >= 0) { assign(ctx, dynValues, d, value); return; }
        }
        property(ctx,name).setProperty(ctx,value);
    }
    /** $obj->$name = value: the name is any PHP value; a string keeps its bytes as the property's array key. */
    public void writeNamed(B1RequestContext ctx, Object nameValue, Object value) {
        if (nameValue instanceof B1Bytes bytes) {
            int i = layout.slotOf(bytes);
            if (i >= 0 && visible(ctx,i)) { assign(ctx, slots, i, value); return; }
            writeResolved(ctx, bytes.toString(), i, bytes, value);
        } else writeConverted(ctx, nameValue, value);
    }
    /** $obj->$name with a name that must be converted. As in PHP the object and the value stay borrowed from their
     * variables while __toString runs, so code there that drops them runs their destructors at that point. PHP's
     * write then lands in freed memory; here a write into a freed object, or of a freed value, does nothing (no
     * value is kept alive by an unreachable object, no freed object comes back). The name stays counted. */
    private void writeConverted(B1RequestContext ctx, Object nameValue, Object value) {
        retain(nameValue);
        Throwable pending = null;
        String name = null;
        try { name = ctx.string(nameValue); }
        catch (Throwable ex) { pending = ex; }
        B1Thrown.propagate(B1Thrown.released(ctx, nameValue, pending));
        if (count == 0 || value instanceof B1Object o && o.count == 0 || value instanceof B1Array a && a.gcCount() == 0
                || value instanceof B1Closure f && f.gcCount() == 0) return;
        writeResolved(ctx, name, slot(name), null, value);
    }
    /** The write once the name and its declared slot (or -1) are known; key is the name as bytes when it was given
     * as a string. */
    private void writeResolved(B1RequestContext ctx, String name, int i, B1Bytes key, Object value) {
        if (i >= 0 && visible(ctx,i)) { assign(ctx, slots, i, value); return; }
        if (i < 0) {
            int d = dynamic(name);
            if (d >= 0) { assign(ctx, dynValues, d, value); return; }
            if (key != null) {
                createDynamic(ctx, name, key, value, true);
                return;
            }
        }
        property(ctx,name).setProperty(ctx,value);
    }
    private static void assign(B1RequestContext ctx, Object[] table, int i, Object value) {
        Object old = table[i];
        if (old instanceof B1Ref ref) { ref.setProperty(ctx, value); return; }
        table[i] = retain(value);
        release(ctx, old);
    }

    /** Scalars (null, int, string, bool, float) are not counted: one type test exits for the common ones. */
    public static Object retain(Object value) {
        if (value instanceof B1Array a) { a.retain(); return value; }
        if (value == null || value instanceof Long || value instanceof B1Bytes) return value;
        return retainSlow(value);
    }
    private static Object retainSlow(Object value) {
        if (value instanceof B1Object obj) obj.count++;
        else if (value instanceof B1Ref ref) ref.bind();
        else if (value instanceof B1Closure c) c.retain();
        return value;
    }

    public static void release(B1RequestContext ctx, Object value) {
        if (value == null || value instanceof Long || value instanceof B1Bytes || value instanceof Boolean) return;
        releaseSlow(ctx, value);
    }
    private static void releaseSlow(B1RequestContext ctx, Object value) {
        if (value instanceof B1Array a) a.release(ctx);
        else if (value instanceof B1Object obj) obj.drop(ctx);
        else if (value instanceof B1Ref ref) ref.unbind(ctx);
        else if (value instanceof B1Closure c) c.release(ctx);
        else if (!(value instanceof Double || value instanceof String)) releaseOther(ctx, value);
    }
    private static void releaseOther(B1RequestContext ctx, Object value) {
        if (value instanceof B1Generator.Cursor it) release(ctx,it.generator);
        else if (value instanceof B1Array.Cursor it) it.array.release(ctx);
        else if (value instanceof B1Array.RefCursor it) it.source.unbind(ctx);
        else if (value instanceof java.util.Map<?, ?> map) releaseElements(ctx, map.values());
        else if (value instanceof List<?> list) releaseElements(ctx, list);
    }

    private void drop(B1RequestContext ctx) {
        if (--count < 0) throw new IllegalStateException("negative PHP refcount");
        if (count == 0) {
            if (inGcRoots) ctx.cycles.dropped(ctx,this,0);
            disposeIfUnheld(ctx);
        } else if (!inGcRoots) ctx.cycles.dropped(ctx,this,count);
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
    public void gcEdges(java.util.function.Consumer<Object> visit) {
        for (Object v : slots) visit.accept(v);
        for (int i = 0; i < dynCount; i++) visit.accept(dynValues[i]);
    }
    void clear(B1RequestContext ctx) {
        var values = new java.util.ArrayList<Object>(slots.length + dynCount);
        for (int i = 0; i < slots.length; i++) { values.add(slots[i]); slots[i] = null; }
        for (int i = 0; i < dynCount; i++) { values.add(dynValues[i]); dynValues[i] = null; }
        dynCount = 0;
        dynIndex = null;
        for (Object v : values) if (v instanceof B1Ref ref) ref.detachProperty();
        releaseElements(ctx,values);
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
