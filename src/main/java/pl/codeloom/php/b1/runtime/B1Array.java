package pl.codeloom.php.b1.runtime;

import java.util.Arrays;

/** An insertion-ordered PHP array. Packed arrays (keys 0..size-1 in order) store neither keys nor hash slots;
 * hashed arrays index their dense entry list with an open-addressing table of entry positions. A hashed array
 * built from keys known to be distinct (get_object_vars) starts unindexed (keys, no hashes or table): a foreach
 * reads it by position, and the first lookup or structural change builds the index. */
public final class B1Array implements B1GcNode {
    static final String OCCUPIED = "Cannot add element to the array as the next element is already occupied";
    /** What {@link #getOrMissing} returns for an absent key; a present key can hold null. */
    public static final Object MISSING = new Object();
    private static final Object[] EMPTY = {};
    private Object[] values;
    /** Canonical keys (Long or B1Bytes) of hashed arrays, null while packed. */
    private Object[] keys;
    /** The entries' key hashes, parallel to keys; null while packed or unindexed. */
    private int[] hashes;
    /** Linear-probing slots holding entry position + 1; 0 is empty. Power-of-two length. Null while packed or
     * unindexed (keys != null). */
    private int[] table;
    private int size;
    private long next;
    private int cursor;
    private boolean hasIntegerKey;
    boolean inGcRoots;
    int gcSlot = -1;
    private int owners = 1;
    private boolean frozen;

    /** Condy roots and their children have process lifetime and can be read by concurrent requests. */
    public B1Array freeze() {
        if (frozen || size == 0) { settle(); frozen = true; return this; }
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
        for (var array : graph) { array.settle(); array.frozen = true; }
        return this;
    }
    /** Before an array is frozen: a frozen array is read by concurrent requests and never changes again, so it
     * must not still build its index lazily (an empty unindexed array becomes an empty packed one). */
    private void settle() {
        if (keys == null || table != null) return;
        if (size == 0) keys = null;
        else index();
    }

    public B1Array(int capacity) { values = capacity == 0 ? EMPTY : new Object[capacity]; }
    /** An empty array sized for string keys: the hash layout is allocated once. */
    static B1Array hashed(int capacity) {
        B1Array array = new B1Array(Math.max(capacity, 4));
        array.keys = new Object[array.values.length];
        array.hashes = new int[array.values.length];
        array.table = new int[tableSize(array.values.length)];
        return array;
    }
    /** An empty unindexed array for {@code capacity} distinct canonical keys added with {@link #appendDistinct}. */
    static B1Array unindexed(int capacity) {
        B1Array array = new B1Array(Math.max(capacity, 1));
        array.keys = new Object[array.values.length];
        return array;
    }
    /** Adds an entry whose canonical key no entry has (the caller guarantees it); the array stays unindexed. */
    void appendDistinct(Object canonical, Object value) {
        if (table != null || keys == null) throw new IllegalStateException("appendDistinct on an indexed array");
        grow();
        keys[size] = canonical;
        values[size] = B1Object.retain(value);
        if (canonical instanceof Long n && (!hasIntegerKey || n >= next)) {
            next = n == Long.MAX_VALUE ? n : n + 1;
            hasIntegerKey = true;
        }
        size++;
    }
    /** Builds the hashes and the table of an unindexed array. */
    private void index() {
        if (frozen) throw new IllegalStateException("index of frozen array");
        hashes = new int[keys.length];
        for (int i = 0; i < size; i++) hashes[i] = hash(keys[i]);
        rehash(values.length);
    }
    private void indexIfNeeded() { if (keys != null && table == null) index(); }
    public int size() { return size; }
    public Object keyAt(int i) { return keys == null ? (Long)(long)i : keys[i]; }
    public Object valueAt(int i) { Object v = values[i]; return v instanceof B1Ref ref ? ref.get() : v; }
    Object rawAt(int i) { return values[i]; }
    boolean frozen() { return frozen; }

    public static Object key(Object k) {
        if (k instanceof Long) return k;
        if (k instanceof B1Bytes s) return s.arrayKey();
        if (k == null) return B1Bytes.EMPTY;
        if (k instanceof String s) return B1Bytes.ofUtf8(s).arrayKey();
        if (k instanceof Boolean b) return b ? 1L : 0L;
        if (k instanceof Number n) return n.longValue();
        throw new IllegalArgumentException("Illegal offset type");
    }

    private static int hash(long n) {
        int h = (int)(n ^ (n >>> 32)) * 0x9E3779B9;
        return h ^ (h >>> 16);
    }
    private static int hash(Object canonical) {
        if (canonical instanceof Long n) return hash(n.longValue());
        int h = canonical.hashCode() * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    /** The entry position of a canonical key, or -1. */
    private int position(Object key) {
        if (key instanceof Long n) return position(n.longValue());
        if (table == null) {
            if (keys == null) return -1;
            index();
        }
        int h = hash(key), mask = table.length - 1;
        for (int s = h & mask;; s = (s + 1) & mask) {
            int e = table[s];
            if (e == 0) return -1;
            int i = e - 1;
            if (hashes[i] == h && key.equals(keys[i])) return i;
        }
    }
    private int position(long k) {
        if (table == null) {
            if (keys == null) return k >= 0 && k < size ? (int)k : -1;
            index();
        }
        int h = hash(k), mask = table.length - 1;
        for (int s = h & mask;; s = (s + 1) & mask) {
            int e = table[s];
            if (e == 0) return -1;
            int i = e - 1;
            if (hashes[i] == h && keys[i] instanceof Long n && n == k) return i;
        }
    }

    public boolean contains(Object k) { return position(key(k)) >= 0; }
    public boolean contains(long k) { return position(k) >= 0; }
    public Object get(long k) {
        int pos = position(k);
        return pos < 0 ? null : valueAt(pos);
    }
    public Object get(Object k) {
        int pos = position(key(k));
        return pos < 0 ? null : valueAt(pos);
    }
    /** One probe for isset/dim/array_key_exists: the value of a canonical key, or {@link #MISSING}. */
    public Object getOrMissing(Object canonical) {
        int pos = position(canonical);
        return pos < 0 ? MISSING : valueAt(pos);
    }

    public B1Array append(B1RequestContext ctx, Object value) {
        if (frozen) throw new IllegalStateException("Write to frozen literal");
        if (keys == null && next == size) {
            grow();
            values[size++] = B1Object.retain(value);
            hasIntegerKey = true;
            next++;
            return this;
        }
        return put(ctx, appendKey(ctx), value);
    }
    long appendKey(B1RequestContext ctx) {
        if (next == Long.MAX_VALUE && contains(next))
            throw ctx.error("Error", OCCUPIED);
        return next;
    }
    private void grow() {
        if (size != values.length) return;
        int capacity = Math.max(4, size + (size >> 1));
        values = Arrays.copyOf(values, capacity);
        if (keys != null) {
            keys = Arrays.copyOf(keys, capacity);
            if (table != null) {
                hashes = Arrays.copyOf(hashes, capacity);
                if (table.length < capacity * 2) rehash(capacity);
            }
        }
    }
    private static int tableSize(int capacity) {
        int n = Integer.highestOneBit(Math.max(4, capacity) * 2 - 1) << 1;
        return Math.max(8, n);
    }
    private void rehash(int capacity) {
        int[] slots = new int[tableSize(capacity)];
        int mask = slots.length - 1;
        for (int i = 0; i < size; i++) {
            int s = hashes[i] & mask;
            while (slots[s] != 0) s = (s + 1) & mask;
            slots[s] = i + 1;
        }
        table = slots;
    }
    private void link(int i) {
        int mask = table.length - 1, s = hashes[i] & mask;
        while (table[s] != 0) s = (s + 1) & mask;
        table[s] = i + 1;
    }
    /** Converts a packed array to the hashed layout. */
    private void hashed() {
        int capacity = Math.max(values.length, 4);
        if (values.length < capacity) values = Arrays.copyOf(values, capacity);
        keys = new Object[capacity];
        hashes = new int[capacity];
        for (int i = 0; i < size; i++) { keys[i] = (long)i; hashes[i] = hash((long)i); }
        rehash(capacity);
    }

    public B1Array put(B1RequestContext ctx, Object k, Object value) {
        if (frozen) throw new IllegalStateException("Write to frozen literal");
        Object canonical = ctx == null ? key(k) : ctx.arrayKey(k);
        int pos = position(canonical);
        if (pos < 0) pos = insert(canonical);
        Object old = values[pos];
        if (old instanceof B1Ref ref) { ref.set(ctx,value); return this; }
        values[pos] = B1Object.retain(value);
        B1Object.release(ctx, old);
        return this;
    }
    /** put() with an integer key, without boxing it on packed arrays. */
    public B1Array put(B1RequestContext ctx, long k, Object value) {
        if (frozen) throw new IllegalStateException("Write to frozen literal");
        int pos = position(k);
        if (pos < 0) {
            if (keys == null && k == size && next <= k) return append(ctx, value);
            pos = insert(k);
        }
        Object old = values[pos];
        if (old instanceof B1Ref ref) { ref.set(ctx,value); return this; }
        values[pos] = B1Object.retain(value);
        B1Object.release(ctx, old);
        return this;
    }
    /** Adds an absent canonical key with a null value; returns its position. */
    private int insert(Object canonical) {
        grow();
        boolean integer = canonical instanceof Long;
        long n = integer ? (Long)canonical : 0;
        if (keys == null && !(integer && n == size)) hashed();
        if (keys != null) {
            if (table == null) index();
            keys[size] = canonical;
            hashes[size] = hash(canonical);
            link(size);
        }
        if (integer && (!hasIntegerKey || n >= next)) {
            next = n == Long.MAX_VALUE ? n : n + 1;
            hasIntegerKey = true;
        }
        return size++;
    }

    /** ZEND_ADD_ARRAY_ELEMENT with a key: a duplicate key replaces the earlier element, reference or not,
     * so neither a reference in a reference nor a write through the earlier alias is made. */
    public B1Array putInit(B1RequestContext ctx, Object k, Object value) {
        Object canonical = ctx.arrayKey(k);
        int pos = position(canonical);
        if (pos < 0) return put(ctx, canonical, value);
        Object old = values[pos];
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
        if (remaining != 0 && inGcRoots) return;
        released(ctx, remaining);
    }
    private void released(B1RequestContext ctx, int remaining) {
        if (remaining == 0) {
            if (inGcRoots && ctx != null) ctx.cycles.dropped(ctx,this,0);
            clear(ctx);
        } else if (!inGcRoots && ctx != null) ctx.cycles.dropped(ctx,this,remaining);
    }
    /** The array has one owner and is not a shared literal: a write needs no separation. */
    boolean unique() { return !frozen && owners == 1; }
    void clear(B1RequestContext ctx) {
        Throwable pending = null;
        for (int i = 0; i < size; i++) {
            Object item = values[i];
            values[i] = null;
            if (item == null || item instanceof Long || item instanceof B1Bytes) continue;
            try { B1Object.release(ctx, item); }
            catch (Throwable ex) { pending = B1Thrown.chain(ctx, ex, pending); }
        }
        size = 0;
        // Unreachable from PHP now (its last owner left, or the collector found it garbage): drop the hash layout.
        keys = null; hashes = null; table = null;
        B1Thrown.propagate(pending);
    }
    B1Array snapshot(java.util.IdentityHashMap<B1Array,B1Array> copies) {
        if (frozen) return this;
        B1Array existing = copies.get(this);
        if (existing != null) return existing;
        B1Array copy = copyShape();
        copies.put(this,copy);
        for (int i = 0; i < size; i++) copy.values[i] = B1Snapshot.pure(values[i],copies);
        copy.settle();
        copy.frozen = true;
        return copy;
    }
    /** define()'s copy_constant_array: references become their values, so a constant holds no live alias. The
     * copy is immutable like the constant (frozen); a write to a variable holding it separates. */
    B1Array constantCopy(B1RequestContext ctx, java.util.Set<B1Array> path) {
        if (frozen) return this;
        if (!path.add(this)) throw ctx.error("ValueError","define(): Argument #2 ($value) cannot be a recursive array");
        B1Array copy = copyShape();
        for (int i = 0; i < size; i++) {
            Object value = values[i] instanceof B1Ref ref ? ref.get() : values[i];
            if (value instanceof B1Array nested) value = nested.constantCopy(ctx,path);
            else if (value instanceof B1Object || value instanceof B1Closure) throw ctx.unsupported("objects in constants");
            copy.values[i] = value;
        }
        path.remove(this);
        copy.settle();
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
        B1Array copy = new B1Array(0);
        copy.values = size == 0 ? EMPTY : new Object[size];
        copy.size = size;
        copy.next = next;
        copy.cursor = cursor;
        copy.hasIntegerKey = hasIntegerKey;
        if (keys != null && table == null) copy.keys = Arrays.copyOf(keys, size);
        else if (keys != null) {
            copy.keys = Arrays.copyOf(keys, size);
            copy.hashes = Arrays.copyOf(hashes, size);
            if (table.length >= tableSize(size)) copy.table = table.clone();
            else copy.rehash(size);
            if (copy.table.length > tableSize(Math.max(size, 4)) * 2) copy.rehash(size);
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
    Object currentKey() { return cursor < size ? keyAt(cursor) : null; }
    Object rewind() { cursor = 0; return current(); }
    Object advance() { if (cursor < size) cursor++; return current(); }
    Object pop(B1RequestContext ctx) {
        if (size == 0) return null;
        int last = size - 1;
        Object result = B1Object.retain(valueAt(last));
        if (keys == null) {
            // zend_hash_packed_del_val on the tail: array_pop then resets the next free key.
            if (last == next - 1) next = last;
        } else if (keys[last] instanceof Long n && (n == next-1 || n == Long.MAX_VALUE)) next = n;
        removeAt(ctx,last); cursor = 0;
        return result;
    }
    void sortKeys(B1RequestContext ctx,Object flags) {
        if (keys == null) { cursor = 0; return; }
        indexIfNeeded();
        Integer[] order = new Integer[size];
        for (int i = 0; i < size; i++) order[i] = i;
        Object[] current = keys;
        Arrays.sort(order,(a,b) -> Long.compare((long)ctx.castLong(current[a]),(long)ctx.castLong(current[b])));
        Object[] orderedValues = new Object[values.length], orderedKeys = new Object[keys.length];
        int[] orderedHashes = new int[hashes.length];
        for (int i = 0; i < size; i++) {
            orderedValues[i] = values[order[i]];
            orderedKeys[i] = keys[order[i]];
            orderedHashes[i] = hashes[order[i]];
        }
        values = orderedValues; keys = orderedKeys; hashes = orderedHashes;
        rehash(values.length);
        cursor = 0;
    }

    public void remove(B1RequestContext ctx, Object key) {
        if (frozen) throw new IllegalStateException("Write to frozen array");
        int pos = position(key(key));
        if (pos >= 0) removeAt(ctx, pos);
    }
    private void removeAt(B1RequestContext ctx, int pos) {
        if (keys != null && table == null) { removeUnindexed(ctx, pos); return; }
        Object old = values[pos];
        int last = size - 1;
        if (keys == null) {
            if (pos != last) hashed();
            else {
                // A packed array stays packed when its tail goes; unset keeps the next free key.
                values[last] = null;
                size = last;
                B1Object.release(ctx,old);
                return;
            }
        }
        if (pos == last) unlinkTail();
        else {
            int moved = last - pos;
            System.arraycopy(keys,pos+1,keys,pos,moved);
            System.arraycopy(hashes,pos+1,hashes,pos,moved);
            System.arraycopy(values,pos+1,values,pos,moved);
            keys[last] = values[last] = null;
            size = last;
            rehash(values.length);
            B1Object.release(ctx,old);
            return;
        }
        keys[last] = values[last] = null;
        size = last;
        B1Object.release(ctx,old);
    }
    /** An unindexed array has no slots to unlink: the remaining keys stay distinct, so it stays unindexed. */
    private void removeUnindexed(B1RequestContext ctx, int pos) {
        Object old = values[pos];
        int last = size - 1;
        System.arraycopy(keys,pos+1,keys,pos,last - pos);
        System.arraycopy(values,pos+1,values,pos,last - pos);
        keys[last] = values[last] = null;
        size = last;
        B1Object.release(ctx,old);
    }
    /** Backward-shift deletion of the last entry's slot. */
    private void unlinkTail() {
        int mask = table.length - 1, target = size;
        int s = hashes[size - 1] & mask;
        while (table[s] != target) s = (s + 1) & mask;
        int hole = s;
        for (int j = (hole + 1) & mask; table[j] != 0; j = (j + 1) & mask) {
            int home = hashes[table[j] - 1] & mask;
            // Move the entry back if its home slot is not cyclically within (hole, j].
            if (((j - home) & mask) >= ((j - hole) & mask)) { table[hole] = table[j]; hole = j; }
        }
        table[hole] = 0;
    }

    /** array_unshift on a uniquely owned array: prepends in place and renumbers the integer keys from 0. */
    long unshift(B1RequestContext ctx, Object value) {
        if (!unique()) throw new IllegalStateException("unshift of a shared array");
        indexIfNeeded();
        if (keys == null) {
            grow();
            System.arraycopy(values,0,values,1,size);
            values[0] = B1Object.retain(value);
            size++;
            next = size;
            hasIntegerKey = true;
            cursor = 0;
            return size;
        }
        grow();
        System.arraycopy(values,0,values,1,size);
        System.arraycopy(keys,0,keys,1,size);
        System.arraycopy(hashes,0,hashes,1,size);
        values[0] = B1Object.retain(value);
        size++;
        long n = 0;
        boolean packed = true;
        for (int i = 0; i < size; i++) {
            if (i == 0 || keys[i] instanceof Long) { keys[i] = n; hashes[i] = hash(n); n++; }
            if (!(keys[i] instanceof Long l && l == i)) packed = false;
        }
        next = n;
        hasIntegerKey = true;
        cursor = 0;
        if (packed) { keys = null; hashes = null; table = null; }
        else rehash(values.length);
        return size;
    }

    static final class Cursor {
        final B1Array array;
        int pos;
        Cursor(B1Array array) { this.array = array; array.retain(); }
    }

    /** FE_RESET_RW's iterator: a counted binding of the iterated place. Each step re-reads the array the
     * place holds; a structural change behind the cursor is refused rather than iterated inexactly. */
    static final class RefCursor {
        final B1Ref source;
        int pos;
        Object lastKey;
        RefCursor(B1Ref source) { this.source = source; source.bind(); }
    }

    boolean packed() { return keys == null; }

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
