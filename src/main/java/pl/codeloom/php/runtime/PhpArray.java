package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.strings.TruffleString;

import java.util.Arrays;

/**
 * PHP's array: an ordered map from int (Long) or string (TruffleString) keys to values, with value semantics.
 *
 * <p>Storage is packed (keys 0..size-1 in order, no holes) until a string key, a non-sequential int key or a
 * removal from the middle forces the hash layout: insertion-ordered slots plus chained buckets, as in php-src.
 *
 * <p>Value semantics use copy-on-write keyed on {@link #holders}: the number of slots (variables, elements,
 * properties, references) that hold this array. It may overestimate (heap slots only ever add), never
 * underestimate; a write through a slot copies first when the array is shared. Keys passed in must already be
 * normalized ({@link ArrayKey}).
 */
public final class PhpArray implements TruffleObject {
    private static final int MIN_CAPACITY = 8;

    private int holders;

    /** Packed values, or null in hash layout. */
    private Object[] packed;
    private int size;

    /**
     * Hash layout: slot keys (null = deleted), their hashes (compared before the keys), slot values, bucket heads
     * and chains, and the slots used including deleted ones.
     */
    private Object[] keys;
    private int[] hashes;
    private Object[] values;
    private int[] buckets;
    private int[] chain;
    private int used;

    /** Next key for an append: one more than the largest int key ever used, as in php-src. */
    private long nextIndex;

    /** Internal pointer for current()/next()/reset(): a position, or -1 past the end. */
    private int pointer;

    public PhpArray() {
        this.packed = new Object[MIN_CAPACITY];
    }

    private PhpArray(int capacity) {
        this.packed = new Object[Math.max(capacity, 1)];
    }

    public static PhpArray ofCapacity(int capacity) {
        return new PhpArray(capacity);
    }

    /** A packed list of the given values; holders of nested arrays are counted. */
    public static PhpArray list(Object... items) {
        PhpArray array = new PhpArray(items.length);
        for (Object item : items) {
            array.append(item);
        }
        return array;
    }

    // ---- copy-on-write ----

    public void addHolder() {
        holders++;
    }

    public void removeHolder() {
        if (holders > 0) {
            holders--;
        }
    }

    public boolean isShared() {
        return holders > 1;
    }

    /** A shallow copy with no holders; nested arrays gain one holder each (the copy now also holds them). */
    @TruffleBoundary
    public PhpArray copy() {
        PhpArray copy = new PhpArray(1);
        copy.size = size;
        copy.nextIndex = nextIndex;
        copy.pointer = pointer;
        if (packed != null) {
            copy.packed = Arrays.copyOf(packed, Math.max(packed.length, MIN_CAPACITY));
            for (int i = 0; i < size; i++) {
                if (packed[i] instanceof PhpArray nested) {
                    nested.addHolder();
                }
            }
        } else {
            copy.packed = null;
            copy.keys = keys.clone();
            copy.hashes = hashes.clone();
            copy.values = values.clone();
            copy.buckets = buckets.clone();
            copy.chain = chain.clone();
            copy.used = used;
            for (int i = 0; i < used; i++) {
                if (keys[i] != null && values[i] instanceof PhpArray nested) {
                    nested.addHolder();
                }
            }
        }
        return copy;
    }

    // ---- reads ----

    public int size() {
        return size;
    }

    public boolean isPacked() {
        return packed != null;
    }

    /** The value (possibly a PhpRef) under a normalized key, or null when absent. */
    public Object getRaw(Object key) {
        if (packed != null) {
            if (key instanceof Long index) {
                long i = index;
                return i >= 0 && i < size ? packed[(int) i] : null;
            }
            return null;
        }
        return hashGet(key);
    }

    /** The dereferenced value under a normalized key, or null when absent. */
    public Object get(Object key) {
        return PhpRef.deref(getRaw(key));
    }

    public boolean containsKey(Object key) {
        return getRaw(key) != null;
    }

    /**
     * The hash-layout slot holding key, or -1 (also for packed arrays). With {@link #isKeyAt} and {@link #rawAt}
     * a lookup site can remember where a key was and confirm it next time with one identity check: arrays built
     * the same way keep a key at the same slot.
     */
    @TruffleBoundary
    public int slotOf(Object key) {
        return packed != null ? -1 : find(key, hash(key));
    }

    /** Whether slot holds this very key instance (a cheap check of a remembered slotOf result). */
    public boolean isKeyAt(int slot, Object key) {
        return packed == null && slot >= 0 && slot < used && keys[slot] == key;
    }

    /**
     * Out of line: inlined, the bucket-chain walk with its key comparisons was the largest part of many compiled
     * PHP functions, one copy per element read.
     */
    @TruffleBoundary
    private Object hashGet(Object key) {
        int slot = find(key, hash(key));
        return slot < 0 ? null : values[slot];
    }

    // ---- writes (callers have made this array exclusive) ----

    /** Stores under a normalized key, keeping references: a PhpRef slot receives the value. */
    public void put(Object key, Object value) {
        if (packed != null && key instanceof Long index) {
            long i = index;
            if (i >= 0 && i < size) {
                Object old = packed[(int) i];
                if (old instanceof PhpRef ref) {
                    ref.set(value);
                    return;
                }
                countIn(value);
                packed[(int) i] = value;
                countOut(old, value);
                return;
            }
            if (i == size && i >= nextIndex) {
                countIn(value);
                appendPacked(value);
                return;
            }
        }
        putSlow(key, value);
    }

    /** Stores a PhpRef (or replaces whatever is there) under a normalized key: `$a[k] = &$x`. */
    @TruffleBoundary
    public void putRef(Object key, PhpRef ref) {
        if (packed != null && key instanceof Long index && index >= 0 && index < size) {
            countOut(packed[(int) (long) index], null);
            packed[(int) (long) index] = ref;
            return;
        }
        if (packed != null && key instanceof Long index && index == size && index >= nextIndex) {
            appendPacked(ref);
            return;
        }
        toHash();
        int h = hash(key);
        int slot = find(key, h);
        if (slot >= 0) {
            countOut(values[slot], null);
            values[slot] = ref;
        } else {
            insert(key, h, ref);
        }
    }

    public void append(Object value) {
        if (packed != null && nextIndex == size) {
            countIn(value);
            appendPacked(value);
            return;
        }
        appendSlow(value);
    }

    @TruffleBoundary
    private void appendSlow(Object value) {
        if (nextIndex == Long.MAX_VALUE && containsKey(Long.MAX_VALUE)) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Cannot add element to the array as the next element is already occupied");
        }
        countIn(value);
        toHash();
        long key = nextIndex;
        insert(key, hash(key), value);
    }

    /** Growth stays inline: out of line, a boundary made short-lived arrays escape (slugs +27%, kses +16%). */
    private void appendPacked(Object value) {
        if (size == packed.length) {
            packed = Arrays.copyOf(packed, size * 2);
        }
        packed[size++] = value;
        nextIndex = size;
    }

    @TruffleBoundary
    private void putSlow(Object key, Object value) {
        toHash();
        int h = hash(key);
        int slot = find(key, h);
        if (slot >= 0) {
            Object old = values[slot];
            if (old instanceof PhpRef ref) {
                ref.set(value);
                return;
            }
            countIn(value);
            values[slot] = value;
            countOut(old, value);
        } else {
            countIn(value);
            insert(key, h, value);
        }
    }

    @TruffleBoundary
    public void remove(Object key) {
        if (packed != null) {
            if (!(key instanceof Long index) || index < 0 || index >= size) {
                return;
            }
            if (index == size - 1) {
                countOut(packed[size - 1], null);
                packed[--size] = null;
                if (pointer >= size) {
                    pointer = -1;
                }
                return;
            }
            toHash();
        }
        int h = hash(key);
        int bucket = h & (buckets.length - 1);
        int previous = -1;
        for (int slot = buckets[bucket]; slot >= 0; slot = chain[slot]) {
            if (keyEquals(keys[slot], key)) {
                if (previous < 0) {
                    buckets[bucket] = chain[slot];
                } else {
                    chain[previous] = chain[slot];
                }
                countOut(values[slot], null);
                keys[slot] = null;
                values[slot] = null;
                size--;
                if (pointer == slot) {
                    pointer = nextPosition(slot);
                }
                return;
            }
            previous = slot;
        }
    }

    /**
     * The array under a key for a nested write (`$a[k][...] = v`): made exclusive and stored back if shared,
     * created when the key is absent or holds null. Returns null when the element exists but isn't an array
     * (the caller decides between string offsets and errors).
     */
    @TruffleBoundary
    public PhpArray arrayForWrite(Object key) {
        Object raw = getRaw(key);
        if (raw instanceof PhpRef ref) {
            Object inner = ref.get();
            if (inner == null || inner == PhpNull.NULL) {
                PhpArray created = new PhpArray();
                ref.set(created);
                return created;
            }
            return ref.arrayForWrite();
        }
        if (raw == null || raw == PhpNull.NULL) {
            PhpArray created = new PhpArray();
            put(key, created);
            return created;
        }
        if (raw instanceof PhpArray nested) {
            if (nested.isShared()) {
                PhpArray copy = nested.copy();
                put(key, copy);
                return copy;
            }
            return nested;
        }
        return null;
    }

    /** The element's reference, creating it (with null) if absent: `&$a[k]`. */
    @TruffleBoundary
    public PhpRef refAt(Object key) {
        Object raw = getRaw(key);
        if (raw instanceof PhpRef ref) {
            return ref;
        }
        PhpRef ref = new PhpRef(raw == null ? PhpNull.NULL : raw);
        putRef(key, ref);
        return ref;
    }

    /** `$a[] = &$x` and `&$a[]`: a new reference element at the next index. */
    @TruffleBoundary
    public PhpRef appendRef() {
        PhpRef ref = new PhpRef(PhpNull.NULL);
        long key = nextIndex;
        putRef(key, ref);
        if (nextIndex <= key) {
            nextIndex = key + 1;
        }
        return ref;
    }

    public long nextIndex() {
        return nextIndex;
    }

    /** array_pop's extra step (unset doesn't do this): the popped last integer key is reused by the next append. */
    public void rewindNextIndex(Object poppedKey) {
        if (poppedKey instanceof Long index && index == nextIndex - 1) {
            nextIndex--;
        }
    }

    private static void countIn(Object value) {
        if (value instanceof PhpArray array) {
            array.addHolder();
        }
    }

    private static void countOut(Object old, Object replacement) {
        if (old instanceof PhpArray array && old != replacement) {
            array.removeHolder();
        }
    }

    // ---- iteration: positions are packed indexes or hash slots; -1 is the end ----

    public int firstPosition() {
        if (packed != null) {
            return size > 0 ? 0 : -1;
        }
        return nextUsed(0);
    }

    public int nextPosition(int position) {
        if (packed != null) {
            return position + 1 < size ? position + 1 : -1;
        }
        return nextUsed(position + 1);
    }

    @TruffleBoundary
    public int lastPosition() {
        if (packed != null) {
            return size - 1;
        }
        for (int i = used - 1; i >= 0; i--) {
            if (keys[i] != null) {
                return i;
            }
        }
        return -1;
    }

    @TruffleBoundary
    public int previousPosition(int position) {
        if (packed != null) {
            return position - 1;
        }
        for (int i = position - 1; i >= 0; i--) {
            if (keys[i] != null) {
                return i;
            }
        }
        return -1;
    }

    private int nextUsed(int from) {
        for (int i = from; i < used; i++) {
            if (keys[i] != null) {
                return i;
            }
        }
        return -1;
    }

    /** True if the position still holds an element (it may have been removed during iteration). */
    public boolean isValidPosition(int position) {
        if (position < 0) {
            return false;
        }
        return packed != null ? position < size : position < used && keys[position] != null;
    }

    public Object keyAt(int position) {
        return packed != null ? (Object) (long) position : keys[position];
    }

    public Object rawAt(int position) {
        return packed != null ? packed[position] : values[position];
    }

    public Object valueAt(int position) {
        return PhpRef.deref(rawAt(position));
    }

    /** Makes the element at a position a reference and returns it (foreach by reference). */
    @TruffleBoundary
    public PhpRef refAtPosition(int position) {
        Object raw = rawAt(position);
        if (raw instanceof PhpRef ref) {
            return ref;
        }
        PhpRef ref = new PhpRef(raw);
        if (packed != null) {
            packed[position] = ref;
        } else {
            values[position] = ref;
        }
        return ref;
    }

    public int pointer() {
        return pointer;
    }

    public void setPointer(int position) {
        pointer = position;
    }

    // ---- hash layout ----

    @TruffleBoundary
    private void toHash() {
        if (packed == null) {
            return;
        }
        Object[] old = packed;
        int count = size;
        int capacity = Integer.highestOneBit(Math.max(MIN_CAPACITY, count * 2) - 1) << 1;
        keys = new Object[capacity];
        hashes = new int[capacity];
        values = new Object[capacity];
        chain = new int[capacity];
        buckets = new int[capacity];
        Arrays.fill(buckets, -1);
        used = 0;
        packed = null;
        size = 0;
        for (int i = 0; i < count; i++) {
            Long key = (long) i;
            insert(key, hash(key), old[i]);
        }
    }

    private void insert(Object key, int h, Object value) {
        if (used == keys.length) {
            grow();
        }
        int slot = used++;
        keys[slot] = key;
        hashes[slot] = h;
        values[slot] = value;
        int bucket = h & (buckets.length - 1);
        chain[slot] = buckets[bucket];
        buckets[bucket] = slot;
        size++;
        if (key instanceof Long index && index >= nextIndex) {
            nextIndex = index == Long.MAX_VALUE ? Long.MAX_VALUE : index + 1;
        }
    }

    /** Doubles capacity, or just compacts when many slots are deleted; keeps order and fixes the pointer. */
    private void grow() {
        int capacity = size * 2 >= keys.length ? keys.length * 2 : keys.length;
        Object[] oldKeys = keys;
        int[] oldHashes = hashes;
        Object[] oldValues = values;
        int oldUsed = used;
        int oldPointer = pointer;
        keys = new Object[capacity];
        hashes = new int[capacity];
        values = new Object[capacity];
        chain = new int[capacity];
        buckets = new int[capacity];
        Arrays.fill(buckets, -1);
        used = 0;
        size = 0;
        long savedNext = nextIndex;
        pointer = -1;
        for (int i = 0; i < oldUsed; i++) {
            if (oldKeys[i] != null) {
                if (i == oldPointer) {
                    pointer = used;
                }
                insert(oldKeys[i], oldHashes[i], oldValues[i]);
            }
        }
        nextIndex = savedNext;
    }

    private int find(Object key, int h) {
        for (int slot = buckets[h & (buckets.length - 1)]; slot >= 0; slot = chain[slot]) {
            if (hashes[slot] == h && keyEquals(keys[slot], key)) {
                return slot;
            }
        }
        return -1;
    }

    private static int hash(Object key) {
        if (key instanceof Long index) {
            long v = index;
            v ^= v >>> 33;
            v *= 0xff51afd7ed558ccdL;
            v ^= v >>> 33;
            return (int) v;
        }
        // TruffleString caches its hash code in the string, so repeated lookups with one key don't rehash
        return key.hashCode();
    }

    private static boolean keyEquals(Object stored, Object key) {
        if (stored instanceof Long a) {
            return key instanceof Long b && a.longValue() == b.longValue();
        }
        if (stored instanceof TruffleString a && key instanceof TruffleString b) {
            return a == b || a.equals(b);
        }
        return false;
    }

    @Override
    public String toString() {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        StringBuilder out = new StringBuilder("array(");
        for (int p = firstPosition(); p >= 0; p = nextPosition(p)) {
            out.append(keyAt(p)).append("=>").append(valueAt(p)).append(", ");
        }
        return out.append(")").toString();
    }
}
