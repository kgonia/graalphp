package pl.codeloom.php.b1.runtime;

import java.util.Arrays;
import java.util.HashMap;

/** An insertion-ordered PHP array. Packed arrays store neither keys nor hash buckets. */
public final class B1Array implements B1GcNode {
    static final String OCCUPIED = "Cannot add element to the array as the next element is already occupied";
    private Object[] values;
    private Object[] keys;
    private HashMap<Object, Integer> index;
    private int size;
    private long next;
    private int cursor;
    private boolean hasIntegerKey;
    boolean inGcRoots;
    private int owners = 1;
    private boolean frozen;

    /** Condy roots and their children have process lifetime and can be read by concurrent requests. */
    public B1Array freeze() {
        if (frozen || size == 0) { frozen = true; return this; }
        // Validate the literal graph before changing any of its arrays. Snapshots of live data
        // use snapshot() instead, so the source request keeps ordinary writable arrays.
        var graph = new java.util.LinkedHashSet<B1Array>();
        var pending = new java.util.ArrayDeque<B1Array>();
        pending.add(this);
        while (!pending.isEmpty()) {
            var array = pending.remove();
            if (array.frozen || !graph.add(array)) continue;
            for (int i = 0; i < array.size; i++) {
                if (array.values[i] instanceof B1Array nested) pending.add(nested);
                else B1Snapshot.requireScalar(array.values[i]);
            }
        }
        for (var array : graph) array.frozen = true;
        return this;
    }

    public B1Array(int capacity) { values = new Object[capacity]; }
    public int size() { return size; }
    public Object keyAt(int i) { return keys == null ? (long)i : keys[i]; }
    public Object valueAt(int i) { return values[i] instanceof B1Ref ref ? ref.get() : values[i]; }
    Object rawAt(int i) { return values[i]; }

    public static Object key(Object k) {
        if (k == null) return B1Bytes.ofUtf8("");
        if (k instanceof Long) return k;
        if (k instanceof B1Bytes s) return s.arrayKey();
        if (k instanceof String s) return B1Bytes.ofUtf8(s).arrayKey();
        if (k instanceof Boolean b) return b ? 1L : 0L;
        if (k instanceof Number n) return n.longValue();
        throw new IllegalArgumentException("Illegal offset type");
    }

    private int position(Object key) {
        if (index == null) return key instanceof Long n && n >= 0 && n < size ? n.intValue() : -1;
        Integer pos = index.get(key);
        return pos == null ? -1 : pos;
    }
    public boolean contains(Object k) { return position(key(k)) >= 0; }
    public Object get(long k) {
        if (index == null) return k >= 0 && k < size ? valueAt((int)k) : null;
        return get((Object)k);
    }
    public Object get(Object k) {
        int pos = position(key(k));
        return pos < 0 ? null : valueAt(pos);
    }
    public B1Array append(B1RequestContext ctx, Object value) {
        if (frozen) throw new IllegalStateException("Write to frozen literal");
        long k = appendKey(ctx);
        if (index == null && next == size) {
            grow();
            values[size++] = B1Object.retain(value);
            hasIntegerKey = true;
            next++;
            return this;
        }
        return put(ctx, k, value);
    }
    long appendKey(B1RequestContext ctx) {
        if (next == Long.MAX_VALUE && contains(next))
            throw ctx.error("Error", OCCUPIED);
        return next;
    }
    private void grow() {
        if (size != values.length) return;
        values = Arrays.copyOf(values, Math.max(4, size + (size >> 1)));
        if (keys != null) keys = Arrays.copyOf(keys, values.length);
    }
    public B1Array put(B1RequestContext ctx, Object k, Object value) {
        if (frozen) throw new IllegalStateException("Write to frozen literal");
        Object canonical = ctx == null ? key(k) : ctx.arrayKey(k);
        int pos = position(canonical);
        if (pos < 0) {
            grow();
            if (keys == null && !(canonical instanceof Long n && n == size)) {
                keys = new Object[values.length];
                index = new HashMap<>();
                for (int i = 0; i < size; i++) index.put(keys[i] = (long)i, i);
            }
            if (keys != null) index.put(keys[size] = canonical, size);
            if (canonical instanceof Long n && (!hasIntegerKey || n >= next)) {
                next = n == Long.MAX_VALUE ? n : n + 1;
                hasIntegerKey = true;
            }
            pos = size++;
        }
        Object old = values[pos];
        if (old instanceof B1Ref ref) { ref.set(ctx,value); return this; }
        values[pos] = B1Object.retain(value);
        B1Object.release(ctx, old);
        return this;
    }

    public int gcCount() { return frozen ? -1 : owners; }
    public void gcEdges(java.util.function.Consumer<Object> visit) {
        for (int i = 0; i < size; i++) visit.accept(values[i]);
    }
    void retain() { if (!frozen) owners++; }
    void release(B1RequestContext ctx) {
        if (frozen) return;
        int remaining = --owners;
        if (ctx != null && inGcRoots != (remaining > 0)) ctx.cycles.dropped(ctx,this,remaining);
        if (remaining == 0) clear(ctx);
    }
    void clear(B1RequestContext ctx) {
        Throwable pending = null;
        for (int i = 0; i < size; i++) {
            Object item = values[i];
            values[i] = null;
            try { B1Object.release(ctx, item); }
            catch (Throwable ex) { pending = B1Thrown.chain(ctx, ex, pending); }
        }
        size = 0;
        B1Thrown.propagate(pending);
    }
    B1Array snapshot(java.util.IdentityHashMap<B1Array,B1Array> copies) {
        if (frozen) return this;
        B1Array existing = copies.get(this);
        if (existing != null) return existing;
        B1Array copy = copyShape();
        copies.put(this,copy);
        for (int i = 0; i < size; i++) copy.values[i] = B1Snapshot.pure(values[i],copies);
        copy.frozen = true;
        return copy;
    }
    B1Array writable() {
        if (!frozen && owners == 1) return this;
        B1Array copy = copyShape();
        for (int i = 0; i < size; i++) copy.values[i] = B1Object.retain(values[i] instanceof B1Ref ref && !ref.shared() ? ref.get() : values[i]);
        return copy;
    }

    private B1Array copyShape() {
        B1Array copy = new B1Array(size);
        copy.size = size;
        copy.next = next;
        copy.cursor = cursor;
        copy.hasIntegerKey = hasIntegerKey;
        if (keys != null) {
            copy.keys = Arrays.copyOf(keys, size);
            copy.index = new HashMap<>(index);
        }
        return copy;
    }

    B1Ref reference(B1RequestContext ctx, Object key) {
        Object canonical = key(key);
        int pos = position(canonical);
        if (pos < 0) { put(ctx,canonical,null); pos = size - 1; }
        if (values[pos] instanceof B1Ref ref) return ref;
        B1Ref ref = new B1Ref();
        ref.set(ctx,values[pos]);
        B1Object.release(ctx,values[pos]);
        values[pos] = ref;
        return ref;
    }

    Object current() { return cursor < size ? valueAt(cursor) : false; }
    Object advance() { if (cursor < size) cursor++; return current(); }
    Object pop(B1RequestContext ctx) {
        if (size == 0) return null;
        Object key = keyAt(size-1);
        Object result = B1Object.retain(valueAt(size-1));
        if (key instanceof Long n && (n == next-1 || n == Long.MAX_VALUE)) next = n;
        remove(ctx,key); cursor = 0;
        return result;
    }
    void sortKeys(B1RequestContext ctx,Object flags) {
        if (keys == null) { cursor = 0; return; }
        Object[] sorted = Arrays.copyOf(keys,size);
        Arrays.sort(sorted,(a,b) -> Long.compare((long)ctx.castLong(a),(long)ctx.castLong(b)));
        Object[] ordered = new Object[values.length];
        for (int i = 0; i < size; i++) ordered[i] = values[index.get(sorted[i])];
        values = ordered;
        for (int i = 0; i < size; i++) index.put(keys[i] = sorted[i],i);
        cursor = 0;
    }

    public void remove(B1RequestContext ctx, Object key) {
        if (frozen) throw new IllegalStateException("Write to frozen array");
        int pos = position(key(key));
        if (pos < 0) return;
        if (keys == null) {
            keys = new Object[values.length];
            index = new HashMap<>();
            for (int i = 0; i < size; i++) index.put(keys[i] = (long)i,i);
        }
        Object old = values[pos];
        index.remove(keys[pos]);
        int moved = --size - pos;
        System.arraycopy(keys,pos+1,keys,pos,moved);
        System.arraycopy(values,pos+1,values,pos,moved);
        keys[size] = values[size] = null;
        for (int i = pos; i < size; i++) index.put(keys[i],i);
        B1Object.release(ctx,old);
    }

    static final class Cursor {
        final B1Array array;
        int pos;
        Cursor(B1Array array) { this.array = array; array.retain(); }
    }

    boolean packed() { return index == null; }

    int sliceStart(long offset) {
        return (int)(offset < 0 ? Math.max(0L,size + offset) : Math.min(size,offset));
    }
    int sliceLength(int start, long length) {
        return (int)(length < 0 ? Math.max(0L,size + length - start) : Math.min(length,size - start));
    }
    public B1Array slice(B1RequestContext ctx, long offset, long length, boolean preserve) {
        int start = sliceStart(offset);
        int count = sliceLength(start,length);
        B1Array slice = new B1Array(count);
        for (int i = start; i < start + count; i++) {
            if (!preserve && (keys == null || keys[i] instanceof Long)) slice.append(ctx, values[i]);
            else slice.put(ctx, keyAt(i), values[i]);
        }
        return slice;
    }
}
