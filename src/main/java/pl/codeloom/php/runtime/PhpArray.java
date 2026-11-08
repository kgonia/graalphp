package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.strings.TruffleString;

import java.util.Arrays;

/**
 * PHP's array: an ordered map from int (Long) or string (TruffleString) keys to values, with value semantics.
 *
 * <p>The elements live in one store, and the store's class is the storage strategy (as in TruffleRuby, where raw
 * Java arrays are stores): a {@code long[]} while the array is packed (keys 0..size-1 in order, no holes) with
 * only ints, unboxed; an {@code Object[]} while it is packed with other values; a {@link Hash} once a string key,
 * a non-sequential int key or a removal from the middle forces it. Nodes specialize on the store's class, so a
 * site compiles only the strategies it has met. An int element of a {@code long[]} needs no checks for nested
 * arrays (holders) or references on its way in or out.
 *
 * <p>Value semantics use copy-on-write keyed on {@link #holders}: the number of slots (variables, elements,
 * properties, references) that hold this array. It may overestimate (heap slots only ever add), never
 * underestimate; a write through a slot copies first when the array is shared. Keys passed in must already be
 * normalized ({@link ArrayKey}).
 *
 * <p>An array held by language-level data (a constant array in a shared AST, a class's default) is
 * {@link #freeze frozen}, like php-src's immutable arrays in opcache: its holders aren't counted and it is always
 * shared, so every write through a slot copies it first and nothing ever writes to it. Contexts on several
 * threads can then share it without a race on the count.
 */
public final class PhpArray implements TruffleObject {
    private static final int MIN_CAPACITY = 8;
    /** The store of a new empty array: an array that never gets an element allocates none. */
    private static final long[] NO_LONGS = new long[0];

    /**
     * The holders, or {@link #FROZEN} once frozen (see {@link #freeze}): a frozen array is never written and its
     * holders aren't counted. One field for both keeps the checks inlined at every holder update to one read and
     * one compare.
     */
    private int holders;
    private static final int FROZEN = Integer.MIN_VALUE;
    /**
     * An array that held arrays or objects and lost its last holder ({@link #release}): its elements' counts were
     * given up. Above 1 as an unsigned number, so it is shared.
     */
    private static final int RELEASED = -1;
    /**
     * Whether an element may be an array or an object, whose counts this array holds: set when one is stored, so an
     * array of scalars isn't walked when it loses its last holder.
     */
    private boolean holdsCounted;
    /**
     * Whether an element may be a reference: set when one is stored, so the arrays that never had one skip the
     * scan when they gain their first holder or lose their last (see {@link PhpRef}).
     */
    private boolean mayHoldRefs;
    /**
     * How many foreach loops by reference walk this array or an array it was copied from ({@link RefIteration}),
     * as php-src counts the hash iterators on a table: its copies and rehashes are reported there, as php-src's
     * iterators are copied and moved. At {@link #ITERATED_MAX} it stays there (a few needless reports, never a
     * missed one).
     */
    private byte iterations;
    private static final byte ITERATED_MAX = Byte.MAX_VALUE;

    /** The elements: a long[] or an Object[] (packed; the capacity is its length), or a {@link Hash}. */
    private Object store;
    private int size;

    /** Next key for an append: one more than the largest int key ever used, as in php-src. */
    private long nextIndex;

    /** Internal pointer for current()/next()/reset(): a position, or -1 past the end. */
    private int pointer;

    public PhpArray() {
        this.store = NO_LONGS;
    }

    private PhpArray(int capacity) {
        this.store = new Object[Math.max(capacity, 1)];
    }

    public static PhpArray ofCapacity(int capacity) {
        return new PhpArray(capacity);
    }

    /** An empty array with room for capacity ints, unboxed (the int strategy) until another value comes. */
    public static PhpArray ofLongCapacity(int capacity) {
        PhpArray array = new PhpArray();
        array.store = new long[Math.max(capacity, 1)];
        return array;
    }

    /** A packed list of the given values; holders of nested arrays are counted. */
    public static PhpArray list(Object... items) {
        PhpArray array = new PhpArray(items.length);
        for (Object item : items) {
            array.append(item);
        }
        return array;
    }

    /** A packed list of strings (algorithm names, extension names...). */
    @TruffleBoundary
    public static PhpArray ofStrings(String... items) {
        PhpArray array = new PhpArray(items.length);
        for (String item : items) {
            array.append(Str.of(item));
        }
        return array;
    }

    // ---- copy-on-write ----

    public void addHolder() {
        int count = holders;
        if (count >= 0) {
            holders = count + 1;
            if (count == 0 && mayHoldRefs) {
                countReferences(true);
            }
        } else if (count == RELEASED) {
            regain();
        }
    }

    public void removeHolder() {
        int count = holders;
        if (count > 0) {
            holders = count - 1;
            if (count == 1 && (holdsCounted || mayHoldRefs)) {
                // one out-of-line call for both walks
                lastHolderGone();
            }
        }
    }

    @TruffleBoundary
    private void lastHolderGone() {
        if (holdsCounted) {
            release();
        } else {
            countReferences(false);
        }
    }

    /**
     * removeHolder for the array a function returns, which its epilogue gives up while the value goes on to the
     * caller: at its last holder it stays alive, its elements' counts held (php-src's return keeps a count), so the
     * caller's store doesn't count them again.
     */
    public void removeReturnedHolder() {
        int count = holders;
        if (count > 0) {
            holders = count - 1;
            if (count == 1 && mayHoldRefs) {
                returnedReferences();
            }
        }
    }

    /** Its references lose this binding but keep their values' counts, as the elements do (out of line). */
    @TruffleBoundary
    private void returnedReferences() {
        for (int p = firstPosition(); p >= 0; p = nextPosition(p)) {
            if (rawAt(p) instanceof PhpRef ref) {
                ref.unbindKeeping();
            }
        }
    }

    /**
     * The last holder of an array of arrays or objects went (tla-cow.md, F4): php-src frees it, and with it the
     * counts it held. Its nested arrays lose this holder, its objects their count, its references their binding.
     * It may still be on its way somewhere (a returned array): while released it is shared (written only as a copy),
     * and a holder taking it counts its elements again ({@link #regain}).
     */
    @TruffleBoundary
    private void release() {
        holders = RELEASED;
        releaseElements(0);
    }

    /** Nested arrays walked by recursion up to this depth, deeper from an explicit stack (releaseDeep). */
    private static final int RELEASE_RECURSION = 128;

    /**
     * Depth first, in element order, as php-src's recursive zend_array_destroy: a nested array whose last holder
     * this was is walked in place (recursion allocates nothing; past RELEASE_RECURSION levels, unserialize()'s
     * thousands, the explicit stack of releaseDeep).
     */
    private void releaseElements(int depth) {
        // by layout, without rawAt's dispatch per element; longs and doubles hold nothing counted
        Object elements = store;
        if (elements instanceof Object[] packed) {
            for (int i = 0; i < size; i++) {
                releaseElement(packed[i], depth);
            }
        } else if (elements instanceof Hash hash) {
            Object[] entries = hash.entries;
            for (int i = 0; i < hash.used; i++) {
                if (entries[2 * i] != null) {
                    releaseElement(entries[2 * i + 1], depth);
                }
            }
        }
    }

    private static void releaseElement(Object raw, int depth) {
        if (raw instanceof PhpArray nested) {
            if (nested.holders == 1 && nested.holdsCounted) {
                // its last holder goes: released in place (removeHolder's lastHolderGone)
                nested.holders = RELEASED;
                if (depth < RELEASE_RECURSION) {
                    nested.releaseElements(depth + 1);
                } else {
                    nested.releaseDeep();
                }
            } else {
                nested.removeHolder();
            }
        } else if (raw instanceof PhpObject object) {
            object.release();
        } else if (raw instanceof PhpClosure closureValue) {
            closureValue.release();
        } else if (raw instanceof PhpRef ref) {
            ref.unbind();
        }
    }

    /** releaseElements from an explicit stack, for nesting past RELEASE_RECURSION. */
    private void releaseDeep() {
        java.util.ArrayDeque<PhpArray> arrays = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<int[]> positions = new java.util.ArrayDeque<>();
        arrays.push(this);
        positions.push(new int[]{firstPosition()});
        while (!arrays.isEmpty()) {
            PhpArray array = arrays.peek();
            int[] position = positions.peek();
            int p = position[0];
            if (p < 0) {
                arrays.pop();
                positions.pop();
                continue;
            }
            position[0] = array.nextPosition(p);
            Object raw = array.rawAt(p);
            if (raw instanceof PhpArray nested) {
                if (nested.holders == 1 && nested.holdsCounted) {
                    // its last holder goes: released in place (removeHolder's lastHolderGone)
                    nested.holders = RELEASED;
                    arrays.push(nested);
                    positions.push(new int[]{nested.firstPosition()});
                } else {
                    nested.removeHolder();
                }
            } else if (raw instanceof PhpObject object) {
                object.release();
            } else if (raw instanceof PhpClosure closureValue) {
                closureValue.release();
            } else if (raw instanceof PhpRef ref) {
                ref.unbind();
            }
        }
    }

    @TruffleBoundary
    private void regain() {
        holders = 1;
        countElements(true);
    }

    private void countElements(boolean in) {
        for (int p = firstPosition(); p >= 0; p = nextPosition(p)) {
            Object raw = rawAt(p);
            if (raw instanceof PhpArray nested) {
                if (in) {
                    nested.addHolder();
                } else {
                    nested.removeHolder();
                }
            } else if (raw instanceof PhpObject object) {
                if (in) {
                    object.addRef();
                } else {
                    object.release();
                }
            } else if (raw instanceof PhpClosure closureValue) {
                if (in) {
                    closureValue.addRef();
                } else {
                    closureValue.release();
                }
            } else if (raw instanceof PhpRef ref) {
                if (in) {
                    ref.bind();
                } else {
                    ref.unbind();
                }
            }
        }
    }

    /**
     * The references among the elements count this array while it has holders: bound when it gains its first,
     * released when it loses its last (php-src's refcount of a reference counts the array slots holding it).
     */
    @TruffleBoundary
    private void countReferences(boolean bind) {
        for (int p = firstPosition(); p >= 0; p = nextPosition(p)) {
            if (rawAt(p) instanceof PhpRef ref) {
                if (bind) {
                    ref.bind();
                } else {
                    ref.unbind();
                }
            }
        }
    }

    /** More than one holder, or frozen (FROZEN is above 1 as an unsigned number): one compare. */
    public boolean isShared() {
        return Integer.compareUnsigned(holders, 1) > 0;
    }

    /**
     * A temporary: no holder, its elements' counts still held (a literal or a returned array on its way somewhere).
     * Released at its temporary's end unless something took it ({@link #releaseTemporary}).
     */
    public boolean isTemporary() {
        return holders == 0 && (holdsCounted || mayHoldRefs);
    }

    /**
     * The temporary's end (php-src frees a TMP): an array nothing took gives its elements' counts up, as at its
     * last holder; its references, which no holder bound, let their values go.
     */
    public void releaseTemporary() {
        // inline: most temporaries hold nothing counted, or were taken
        if (holders == 0 && (holdsCounted || mayHoldRefs)) {
            releaseTemporarySlow();
        }
    }

    @TruffleBoundary
    private void releaseTemporarySlow() {
        if (holdsCounted) {
            release();
        }
        if (mayHoldRefs) {
            for (int p = firstPosition(); p >= 0; p = nextPosition(p)) {
                if (rawAt(p) instanceof PhpRef ref) {
                    ref.releaseUnbound();
                }
            }
        }
    }

    /** The holders, for truffle_refcount (debugging): negative when frozen or released. */
    public int holderCount() {
        return holders;
    }

    /** Released (its last holder went: its elements gave their counts up), for the refcount census. */
    boolean isReleased() {
        return holders == RELEASED;
    }

    /** Frozen (shared, its holders uncounted), for the refcount census. */
    boolean isFrozen() {
        return holders == FROZEN;
    }

    /**
     * Makes this array and the arrays nested in it immutable: language-level data shared by every context (and
     * thread). Call it before the array is published, while one thread holds it.
     */
    @TruffleBoundary
    public void freeze() {
        if (holders == FROZEN) {
            return;
        }
        holders = FROZEN;
        for (int p = firstPosition(); p >= 0; p = nextPosition(p)) {
            if (rawAt(p) instanceof PhpArray nested) {
                nested.freeze();
            }
        }
    }

    /** A shallow copy with no holders; nested arrays gain one holder each (the copy now also holds them). */
    @TruffleBoundary
    public PhpArray copy() {
        PhpArray copy = new PhpArray();
        copy.size = size;
        copy.nextIndex = nextIndex;
        copy.pointer = pointer;
        copy.mayHoldRefs = mayHoldRefs;
        copy.holdsCounted = holdsCounted;
        if (store instanceof long[] longs) {
            copy.store = longs.length == 0 ? NO_LONGS : longs.clone();
        } else if (store instanceof Object[] packed) {
            Object[] values = Arrays.copyOf(packed, Math.max(packed.length, MIN_CAPACITY));
            for (int i = 0; i < size; i++) {
                copyElement(values, i);
            }
            copy.store = values;
        } else {
            Hash hash = ((Hash) store).copy();
            for (int i = 0; i < hash.used; i++) {
                if (hash.key(i) != null) {
                    copyElement(hash.entries, 2 * i + 1);
                }
            }
            copy.store = hash;
        }
        if (iterations != 0) {
            RefIteration.copied(this, copy);
        }
        return copy;
    }

    public boolean isIterated() {
        return iterations != 0;
    }

    /** One more loop walks this array (or reports its copies). */
    void track() {
        if (iterations < ITERATED_MAX) {
            iterations++;
        }
    }

    /** A loop no longer walks this array. */
    void untrack() {
        if (iterations > 0 && iterations < ITERATED_MAX) {
            iterations--;
        }
    }

    /** An element of a fresh copy: a reference only this array holds becomes a value, a nested array gains one. */
    private void copyElement(Object[] values, int i) {
        if (values[i] instanceof PhpRef ref) {
            values[i] = ref.copyElement(this, holders > 0);
        }
        if (values[i] instanceof PhpArray nested) {
            nested.addHolder();
        } else if (values[i] instanceof PhpObject object) {
            object.addRef();
        } else if (values[i] instanceof PhpClosure closureValue) {
            closureValue.addRef();
        }
    }

    // ---- reads ----

    public int size() {
        return size;
    }

    public boolean isPacked() {
        return !(store instanceof Hash);
    }

    /** The value (possibly a PhpRef) under a normalized key, or null when absent. */
    public Object getRaw(Object key) {
        Object elements = store;
        if (elements instanceof Hash hash) {
            return hashGet(hash, key);
        }
        if (key instanceof Long index) {
            long i = index;
            if (i < 0 || i >= size) {
                return null;
            }
            return elements instanceof long[] longs ? (Object) longs[(int) i] : objectStore(elements)[(int) i];
        }
        return null;
    }

    /** The dereferenced value under a normalized key, or null when absent. */
    public Object get(Object key) {
        return PhpRef.deref(getRaw(key));
    }

    public boolean containsKey(Object key) {
        return getRaw(key) != null;
    }

    /**
     * The hash slot holding key, or -1 (also for packed arrays). With {@link #isKeyAt} and {@link #rawAt} a lookup
     * site can remember where a key was and confirm it next time with one identity check: arrays built the same
     * way keep a key at the same slot.
     */
    @TruffleBoundary
    public int slotOf(Object key) {
        return store instanceof Hash hash ? hash.find(key, hash(key)) : -1;
    }

    /**
     * The value (possibly a PhpRef) under an int key of a hashed array, or null: inline, for sites that meet
     * hashes with int keys (priorities, sparse indexes). Int keys compare without calls, so the walk stays small.
     */
    public Object hashGetLong(long index) {
        Hash hash = hashStore(store);
        Object[] entries = hash.entries;
        if (hash.buckets == null) {
            for (int slot = 0; slot < hash.used; slot++) {
                if (entries[2 * slot] instanceof Long key && key == index) {
                    return entries[2 * slot + 1];
                }
            }
            return null;
        }
        for (int slot = hash.buckets[Long.hashCode(index) & (hash.buckets.length - 1)]; slot >= 0;
             slot = hash.chain[slot]) {
            if (entries[2 * slot] instanceof Long key && key == index) {
                return entries[2 * slot + 1];
            }
        }
        return null;
    }

    /**
     * The value (possibly a PhpRef) at a hash slot that holds this very key instance, else null: a cheap check of a
     * remembered slotOf result. The slots past the used ones hold no key, so the entries' bounds are the only
     * range check, which covers the key and the value, and a live slot's value is never null.
     */
    public Object rawIfKeyAt(int slot, Object key) {
        if (store instanceof Hash hash) {
            Object[] entries = hash.entries;
            int at = 2 * slot;
            // one unsigned compare: the slot not remembered yet (-1) gives -1, past any length
            if (Integer.compareUnsigned(at + 1, entries.length) < 0 && entries[at] == key) {
                return entries[at + 1];
            }
        }
        return null;
    }

    /**
     * Out of line: inlined, the bucket-chain walk with its key comparisons was the largest part of many compiled
     * PHP functions, one copy per element read.
     */
    @TruffleBoundary
    private static Object hashGet(Hash hash, Object key) {
        int slot = hash.find(key, hash(key));
        return slot < 0 ? null : hash.value(slot);
    }

    // ---- writes (callers have made this array exclusive) ----

    /** Stores under a normalized key, keeping references: a PhpRef slot receives the value. */
    public void put(Object key, Object value) {
        if (!(store instanceof Hash) && key instanceof Long index) {
            long i = index;
            boolean inPlace = i >= 0 && i < size;
            if (inPlace || (i == size && i >= nextIndex)) {
                if (store instanceof long[] longs) {
                    if (value instanceof Long number) {
                        if (inPlace) {
                            longs[(int) i] = number;
                        } else {
                            pushLong(number);
                        }
                        return;
                    }
                    toObjects();
                }
                if (inPlace) {
                    Object[] packed = (Object[]) store;
                    Object old = packed[(int) i];
                    if (old instanceof PhpRef ref) {
                        ref.set(value);
                        return;
                    }
                    countIn(value);
                    packed[(int) i] = value;
                    countOut(old, value);
                } else {
                    countIn(value);
                    appendPacked(value);
                }
                return;
            }
        }
        putSlow(key, value);
    }

    /**
     * The keys and dereferenced values in order, as key, value, key, value...: a snapshot for a built-in that calls
     * back for each element (array_map, array_filter), which walks its argument as it was, as php-src walks the
     * argument's own table, which the callback's writes don't change. Out of line, so the compiled loop between
     * this and the result's {@link #ofEntries build} tests no layout.
     */
    @TruffleBoundary
    public Object[] entries() {
        Object[] entries = new Object[size * 2];
        int i = 0;
        for (int p = firstPosition(); p >= 0; p = nextPosition(p)) {
            entries[i++] = keyAt(p);
            entries[i++] = valueAt(p);
        }
        return entries;
    }

    /**
     * A new array of the keys of entries (as {@link #entries} gives them) with the given values, or with their own
     * values where kept says so (values null).
     */
    @TruffleBoundary
    public static PhpArray ofEntries(Object[] entries, Object[] values, boolean[] kept) {
        PhpArray result = new PhpArray();
        for (int i = 0; i < entries.length / 2; i++) {
            if (values != null) {
                result.putNew(entries[2 * i], values[i]);
            } else if (kept[i]) {
                result.putNew(entries[2 * i], entries[2 * i + 1]);
            }
        }
        return result;
    }

    /**
     * put() of a key the array doesn't hold yet, for an array being built (array_map's and array_filter's result):
     * the next index appends, any other key inserts out of line. put()'s in-place cases (a reference element, the
     * old value's holder) don't arise, so its inline code isn't copied into every builder loop.
     */
    public void putNew(Object key, Object value) {
        if (key instanceof Long index && index == size && nextIndex == size) {
            append(value);
            return;
        }
        putSlow(key, value);
    }

    /** Whether the array is packed with only ints, stored unboxed (the int strategy). */
    public boolean holdsLongs() {
        return store instanceof long[];
    }

    /** Whether index holds an unboxed int (the int strategy); {@link #longAt} then reads it. */
    public boolean holdsLongAt(long index) {
        return store instanceof long[] && index >= 0 && index < size;
    }

    public long longAt(long index) {
        return longStore(store)[(int) index];
    }

    /** Replaces the int at an index that {@link #holdsLongAt}. */
    public void setLongAt(long index, long value) {
        longStore(store)[(int) index] = value;
    }

    /** put() of an int, which stays unboxed while the array holds only ints. */
    public void putLong(Object key, long value) {
        if (store instanceof long[] longs && key instanceof Long index) {
            long i = index;
            if (i >= 0 && i < size) {
                longs[(int) i] = value;
                return;
            }
            if (i == size && i >= nextIndex) {
                pushLong(value);
                return;
            }
        }
        put(key, value);
    }

    /** append() of an int, which stays unboxed while the array holds only ints. */
    public void appendLong(long value) {
        if (store instanceof long[] && nextIndex == size) {
            pushLong(value);
            return;
        }
        append(value);
    }

    /**
     * Stores the value in the element's slot whatever the slot holds, a reference included (which put() writes
     * through): unserialize() reading a key a second time replaces the element in place, as php-src does.
     */
    @TruffleBoundary
    public void replace(Object key, Object value) {
        if (!(getRaw(key) instanceof PhpRef)) {
            put(key, value);
            return;
        }
        // a reference element: the store holds objects, packed or hashed
        Object old;
        countIn(value);
        if (store instanceof Object[] packed) {
            int index = (int) (long) (Long) key;
            old = packed[index];
            packed[index] = value;
        } else {
            Hash hash = (Hash) store;
            int slot = hash.find(key, hash(key));
            old = hash.value(slot);
            hash.setValue(slot, value);
        }
        countOut(old, value);
    }

    /** Stores a PhpRef (or replaces whatever is there) under a normalized key: `$a[k] = &$x`. */
    @TruffleBoundary
    public void putRef(Object key, PhpRef ref) {
        mayHoldRefs = true;
        if (holders > 0) {
            ref.bind();
        }
        if (store instanceof long[]) {
            toObjects();
        }
        if (store instanceof Object[] packed && key instanceof Long index && index >= 0 && index < size) {
            Object old = packed[(int) (long) index];
            packed[(int) (long) index] = ref;
            // after the store, as php-src: a destructor the release runs sees the array as it is now
            countOut(old, null);
            return;
        }
        if (store instanceof Object[] && key instanceof Long index && index == size && index >= nextIndex) {
            appendPacked(ref);
            return;
        }
        Hash hash = toHash();
        int h = hash(key);
        int slot = hash.find(key, h);
        if (slot >= 0) {
            Object old = hash.value(slot);
            hash.setValue(slot, ref);
            countOut(old, null);
        } else {
            insert(hash, key, h, ref);
        }
    }

    public void append(Object value) {
        if (!(store instanceof Hash) && nextIndex == size) {
            if (store instanceof long[]) {
                if (value instanceof Long number) {
                    pushLong(number);
                    return;
                }
                toObjects();
            }
            countIn(value);
            appendPacked(value);
            return;
        }
        appendSlow(value);
    }

    @TruffleBoundary
    private void appendSlow(Object value) {
        if (nextIndex == Long.MAX_VALUE && containsKey(Long.MAX_VALUE)) {
            throw new PhpError("Cannot add element to the array as the next element is already occupied");
        }
        countIn(value);
        Hash hash = toHash();
        long key = nextIndex;
        insert(hash, key, hash(key), value);
    }

    /** Growth stays inline: out of line, a boundary made short-lived arrays escape (slugs +27%, kses +16%). */
    private void appendPacked(Object value) {
        Object[] packed = (Object[]) store;
        if (size == packed.length) {
            packed = Arrays.copyOf(packed, size * 2);
            store = packed;
        }
        packed[size++] = value;
        nextIndex = size;
    }

    private void pushLong(long value) {
        long[] longs = (long[]) store;
        if (size == longs.length) {
            longs = Arrays.copyOf(longs, Math.max(MIN_CAPACITY, size * 2));
            store = longs;
        }
        longs[size++] = value;
        nextIndex = size;
    }

    /** Leaves the int strategy for Object[]; inline when empty, the usual case (an array built from scratch). */
    private void toObjects() {
        if (size == 0) {
            store = new Object[Math.max(MIN_CAPACITY, ((long[]) store).length)];
            return;
        }
        boxLongs();
    }

    @TruffleBoundary
    private void boxLongs() {
        long[] longs = (long[]) store;
        Object[] boxed = new Object[Math.max(MIN_CAPACITY, longs.length)];
        for (int i = 0; i < size; i++) {
            boxed[i] = longs[i];
        }
        store = boxed;
    }

    @TruffleBoundary
    private void putSlow(Object key, Object value) {
        Hash hash = toHash();
        int h = hash(key);
        int slot = hash.find(key, h);
        if (slot >= 0) {
            Object old = hash.value(slot);
            if (old instanceof PhpRef ref) {
                ref.set(value);
                return;
            }
            countIn(value);
            hash.setValue(slot, value);
            countOut(old, value);
        } else {
            countIn(value);
            insert(hash, key, h, value);
        }
    }

    @TruffleBoundary
    public void remove(Object key) {
        if (!(store instanceof Hash)) {
            if (!(key instanceof Long index) || index < 0 || index >= size) {
                return;
            }
            if (index == size - 1) {
                if (store instanceof long[]) {
                    size--;
                } else {
                    Object[] packed = (Object[]) store;
                    Object old = packed[size - 1];
                    packed[--size] = null;
                    if (pointer >= size) {
                        pointer = -1;
                    }
                    // once removed, as php-src's zend_hash_del: a destructor the release runs doesn't see it
                    countOut(old, null);
                    return;
                }
                if (pointer >= size) {
                    pointer = -1;
                }
                return;
            }
        }
        Hash hash = toHash();
        int slot = hash.unlink(key, hash(key));
        if (slot < 0) {
            return;
        }
        Object old = hash.value(slot);
        hash.clear(slot);
        size--;
        if (pointer == slot) {
            pointer = nextPosition(slot);
        }
        // once removed, as php-src's zend_hash_del: a destructor the release runs doesn't see it
        countOut(old, null);
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

    /** array_multisort's rebuilt hash: the next append's key is the element count, whatever the keys. */
    public void setNextIndex(long index) {
        nextIndex = index;
    }

    /**
     * array_pop(): removes and returns the last element (dereferenced), PhpNull when empty; the internal pointer
     * goes back to the start and a popped trailing int key is reused by the next append. Packed arrays pop inline.
     */
    public Object pop() {
        if (!(store instanceof Hash)) {
            if (size == 0) {
                return PhpNull.NULL;
            }
            int last = --size;
            Object value;
            if (store instanceof long[] longs) {
                value = longs[last];
            } else {
                Object[] packed = (Object[]) store;
                Object raw = packed[last];
                packed[last] = null;
                if (raw instanceof PhpObject object) {
                    // moved out to the caller (php-src's array_pop copies it, then deletes the slot): no event
                    if (holders >= 0) {
                        object.disown();
                    }
                } else if (raw instanceof PhpClosure closureValue) {
                    if (holders >= 0) {
                        closureValue.disown();
                    }
                } else if (raw instanceof PhpArray nested) {
                    // the result: it keeps its elements on its way to the caller
                    if (holders >= 0) {
                        nested.removeReturnedHolder();
                    }
                } else {
                    countOut(raw, null);
                }
                value = PhpRef.deref(raw);
            }
            if (last == nextIndex - 1) {
                nextIndex--;
            }
            pointer = size > 0 ? 0 : -1;
            return value;
        }
        return popHashed();
    }

    /**
     * array_unshift() of one value: it becomes the first element, int keys are renumbered (string keys kept) and
     * the internal pointer goes back to the start. A packed list no foreach by reference walks shifts its elements
     * inline.
     */
    public void prepend(Object value) {
        if (!(store instanceof Hash) && nextIndex == size && iterations == 0) {
            if (store instanceof long[] longs) {
                if (value instanceof Long number) {
                    if (size == longs.length) {
                        longs = Arrays.copyOf(longs, Math.max(MIN_CAPACITY, size * 2));
                        store = longs;
                    }
                    System.arraycopy(longs, 0, longs, 1, size);
                    longs[0] = number;
                    size++;
                    nextIndex = size;
                    pointer = 0;
                    return;
                }
                toObjects();
            }
            countIn(value);
            Object[] packed = (Object[]) store;
            if (size == packed.length) {
                packed = Arrays.copyOf(packed, Math.max(MIN_CAPACITY, size * 2));
                store = packed;
            }
            System.arraycopy(packed, 0, packed, 1, size);
            packed[0] = value;
            size++;
            nextIndex = size;
            pointer = 0;
            return;
        }
        prependAll(new Object[] {value == this ? copy() : value});
    }

    /**
     * array_unshift(): the values become the first elements, int keys are renumbered (string keys kept), the
     * elements keep their references and the internal pointer goes back to the start. As php-src swaps the new
     * table into the same array and advances its iterators (zend_hash_iterators_advance), a foreach by reference
     * over this array moves with its element.
     */
    @TruffleBoundary
    public void prependAll(Object[] values) {
        PhpArray rebuilt = new PhpArray();
        for (Object value : values) {
            rebuilt.append(value);
        }
        for (int p = firstPosition(); p >= 0; p = nextPosition(p)) {
            Object key = keyAt(p);
            Object raw = rawAt(p);
            if (key instanceof Long) {
                rebuilt.append(raw);
            } else {
                rebuilt.put(key, raw);
            }
            if (raw instanceof PhpArray nested) {
                // ponytail: rebuilt counted it again; the old store's count goes with the old store
                nested.removeHolder();
            } else if (raw instanceof PhpObject object) {
                object.disown();
            } else if (raw instanceof PhpClosure closureValue) {
                closureValue.disown();
            }
        }
        store = rebuilt.store;
        size = rebuilt.size;
        nextIndex = rebuilt.nextIndex;
        pointer = firstPosition();
        if (iterations != 0) {
            RefIteration.advanced(this, values.length);
        }
    }

    @TruffleBoundary
    private Object popHashed() {
        int p = lastPosition();
        if (p < 0) {
            return PhpNull.NULL;
        }
        Object value = valueAt(p);
        Object key = keyAt(p);
        if (value instanceof PhpArray nested) {
            nested.addHolder();
        } else if (value instanceof PhpObject object) {
            object.addRef();
        } else if (value instanceof PhpClosure closureValue) {
            closureValue.addRef();
        }
        remove(key);
        rewindNextIndex(key);
        if (value instanceof PhpArray nested) {
            // on its way to the caller as the result: it keeps its elements (php-src's retval holds the array)
            nested.removeReturnedHolder();
        } else if (value instanceof PhpObject object) {
            object.disown();
        } else if (value instanceof PhpClosure closureValue) {
            closureValue.disown();
        }
        setPointer(firstPosition());
        return value;
    }

    /** array_pop's extra step (unset doesn't do this): the popped last integer key is reused by the next append. */
    public void rewindNextIndex(Object poppedKey) {
        if (poppedKey instanceof Long index && index == nextIndex - 1) {
            nextIndex--;
        }
    }

    /** An element enters its slot: a nested array gains this array as a holder, an object its count. */
    private void countIn(Object value) {
        if (value instanceof PhpArray array) {
            holdsCounted = true;
            if (holders >= 0) {
                array.addHolder();
            }
        } else if (value instanceof PhpObject object) {
            holdsCounted = true;
            if (holders >= 0) {
                object.addRef();
            }
        } else if (value instanceof PhpClosure closureValue) {
            holdsCounted = true;
            if (holders >= 0) {
                closureValue.addRef();
            }
        }
    }

    /**
     * An element leaves its slot: a nested array loses this holder, an object its count, a reference this array's
     * binding. A released array ({@link #RELEASED}) gave its elements' counts up already.
     */
    private void countOut(Object old, Object replacement) {
        if (old instanceof PhpObject object) {
            if (holders >= 0) {
                object.release();
            }
            return;
        }
        if (old instanceof PhpClosure closureValue) {
            if (holders >= 0) {
                closureValue.release();
            }
            return;
        }
        if (old instanceof PhpArray array) {
            // also when it is the replacement: countIn added the replacement's holder
            if (holders >= 0) {
                array.removeHolder();
            }
        } else if (old instanceof PhpRef ref && old != replacement && holders > 0) {
            ref.unbind();
        }
    }

    // ---- iteration: positions are packed indexes or hash slots; -1 is the end ----

    public int firstPosition() {
        if (store instanceof Hash hash) {
            return hash.nextUsed(0);
        }
        return size > 0 ? 0 : -1;
    }

    public int nextPosition(int position) {
        if (store instanceof Hash hash) {
            return hash.nextUsed(position + 1);
        }
        return position + 1 < size ? position + 1 : -1;
    }

    @TruffleBoundary
    public int lastPosition() {
        if (!(store instanceof Hash hash)) {
            return size - 1;
        }
        for (int i = hash.used - 1; i >= 0; i--) {
            if (hash.key(i) != null) {
                return i;
            }
        }
        return -1;
    }

    @TruffleBoundary
    public int previousPosition(int position) {
        if (!(store instanceof Hash hash)) {
            return position - 1;
        }
        for (int i = position - 1; i >= 0; i--) {
            if (hash.key(i) != null) {
                return i;
            }
        }
        return -1;
    }

    /** The first position at or after the given one holding an element, or -1 (php-src's _zend_hash_get_valid_pos). */
    public int positionFrom(int position) {
        if (store instanceof Hash hash) {
            return position >= hash.used ? -1 : hash.nextUsed(position);
        }
        return position < size ? position : -1;
    }

    /** True if the position still holds an element (it may have been removed during iteration). */
    public boolean isValidPosition(int position) {
        if (position < 0) {
            return false;
        }
        if (store instanceof Hash hash) {
            return position < hash.used && hash.key(position) != null;
        }
        return position < size;
    }

    public Object keyAt(int position) {
        return store instanceof Hash hash ? hash.key(position) : (Object) (long) position;
    }

    /**
     * The element at the position as a copy of this array holds it (zend_hash_copy's Z_TRY_ADDREF): a reference
     * held elsewhere too stays one, else its value.
     */
    public Object copiedAt(int position) {
        Object raw = rawAt(position);
        return raw instanceof PhpRef ref ? ref.copyElement(this, holders > 0) : raw;
    }

    /** put, or putRef for a reference copiedAt kept. */
    public void putCopied(Object key, Object element) {
        if (element instanceof PhpRef ref) {
            putRef(key, ref);
        } else {
            put(key, element);
        }
    }

    /** append, or a reference copiedAt kept at the next index. */
    public void appendCopied(Object element) {
        if (element instanceof PhpRef ref) {
            long key = nextIndex;
            putRef(key, ref);
            if (nextIndex <= key) {
                nextIndex = key + 1;
            }
        } else {
            append(element);
        }
    }

    public Object rawAt(int position) {
        Object elements = store;
        if (elements instanceof long[] longs) {
            return longs[position];
        }
        if (elements instanceof Object[] packed) {
            return packed[position];
        }
        return hashStore(elements).value(position);
    }

    public Object valueAt(int position) {
        return PhpRef.deref(rawAt(position));
    }

    // ---- per-strategy iteration, for sites that specialize on the store (foreach); callers check it first ----

    /*
     * The store as the layout the caller has tested, through an instanceof, not a cast. A cast compiles to a guard
     * (ClassCastException, then the element access's NullCheckException) that Graal's speculative guard movement
     * may hoist out of a loop, with what it depends on, past the tests that made it hold (an element read's slot
     * check, a layout test). There it failed for a value the code would never have cast, and the function was
     * recompiled, once per such guard: WP_Theme_JSON::sanitize, for array_slice's loop inside a foreach. An
     * instanceof is control flow, which anchors what depends on it; its else is unreachable, and
     * transferToInterpreter (without invalidation) is no speculation to hoist.
     */

    private static long[] longStore(Object store) {
        if (store instanceof long[] longs) {
            return longs;
        }
        throw notThisLayout();
    }

    private static Object[] objectStore(Object store) {
        if (store instanceof Object[] objects) {
            return objects;
        }
        throw notThisLayout();
    }

    private static Hash hashStore(Object store) {
        if (store instanceof Hash hash) {
            return hash;
        }
        throw notThisLayout();
    }

    private static IllegalStateException notThisLayout() {
        CompilerDirectives.transferToInterpreter();
        return new IllegalStateException("an array accessed by a layout it doesn't have");
    }

    /** Whether the array is packed with an Object[] (not the int strategy, not hashed). */
    public boolean holdsObjects() {
        return store instanceof Object[];
    }

    /** The raw element at a position of an Object[]-packed array. */
    public Object packedAt(int position) {
        return objectStore(store)[position];
    }

    /** The position after p in a packed array, or -1 at the end. */
    public int nextPackedPosition(int position) {
        return position + 1 < size ? position + 1 : -1;
    }

    /** The position after p in a hashed array, or -1 at the end. */
    public int nextHashPosition(int position) {
        return hashStore(store).nextUsed(position + 1);
    }

    /** The key at a position of a hashed array. */
    public Object hashKeyAt(int position) {
        return hashStore(store).key(position);
    }

    /** The raw value at a position of a hashed array. */
    public Object hashValueAt(int position) {
        return hashStore(store).value(position);
    }

    /** Makes the element at a position a reference and returns it (foreach by reference). */
    @TruffleBoundary
    public PhpRef refAtPosition(int position) {
        Object raw = rawAt(position);
        if (raw instanceof PhpRef ref) {
            return ref;
        }
        PhpRef ref = new PhpRef(raw);
        mayHoldRefs = true;
        if (holders > 0) {
            ref.bind();
        }
        if (raw instanceof PhpArray array) {
            array.removeHolder();
        } else if (raw instanceof PhpObject object && holders >= 0) {
            // the slot's count moves to the reference, which counted it
            object.disown();
        } else if (raw instanceof PhpClosure closureValue && holders >= 0) {
            closureValue.disown();
        }
        if (store instanceof long[]) {
            toObjects();
        }
        if (store instanceof Object[] packed) {
            packed[position] = ref;
        } else {
            ((Hash) store).setValue(position, ref);
        }
        return ref;
    }

    public int pointer() {
        return pointer;
    }

    public void setPointer(int position) {
        pointer = position;
    }

    // ---- small hashes inline, for nodes that specialize on them ----

    /** An empty array with a hash store sized for count distinct keys: a literal's, filled by {@link #addEntry}. */
    public static PhpArray ofHashCapacity(int count) {
        PhpArray array = new PhpArray();
        array.store = new Hash(Math.max(MIN_CAPACITY, Integer.highestOneBit(Math.max(count, 1) - 1) << 1));
        return array;
    }

    /** Adds a key the array doesn't hold to an array made by {@link #ofHashCapacity}, with hashOf(key) as h. */
    public void addEntry(Object key, int h, Object value) {
        countIn(value);
        ((Hash) store).add(key, h, value);
        size++;
        if (key instanceof Long index && index >= nextIndex) {
            nextIndex = index == Long.MAX_VALUE ? Long.MAX_VALUE : index + 1;
        }
    }

    /** The hash of a normalized key, as the hash store keeps it. */
    public static int hashOf(Object key) {
        return hash(key);
    }

    // ---- the hash strategy ----

    /** The store as a Hash, converting a packed one: its index i becomes slot i, so positions stay valid. */
    @TruffleBoundary
    private Hash toHash() {
        if (store instanceof Hash hash) {
            return hash;
        }
        int count = size;
        Hash hash = new Hash(Integer.highestOneBit(Math.max(MIN_CAPACITY, count * 2) - 1) << 1);
        if (store instanceof long[] longs) {
            // the ints go into the hash's slots boxed once, without an Object[] in between (none when empty)
            for (int i = 0; i < count; i++) {
                long key = i;
                hash.add(key, hash(key), longs[i]);
            }
        } else {
            Object[] old = (Object[]) store;
            for (int i = 0; i < count; i++) {
                long key = i;
                hash.add(key, hash(key), old[i]);
            }
        }
        store = hash;
        return hash;
    }

    private void insert(Hash hash, Object key, int h, Object value) {
        Hash target = hash;
        if (target.used == target.capacity()) {
            target = grow(target);
        }
        target.add(key, h, value);
        size++;
        if (key instanceof Long index && index >= nextIndex) {
            nextIndex = index == Long.MAX_VALUE ? Long.MAX_VALUE : index + 1;
        }
    }

    /** Doubles capacity, or just compacts when many slots are deleted; keeps order and fixes the pointer. */
    private Hash grow(Hash old) {
        Hash grown = new Hash(size * 2 >= old.capacity() ? old.capacity() * 2 : old.capacity());
        int oldPointer = pointer;
        pointer = -1;
        for (int i = 0; i < old.used; i++) {
            if (old.key(i) != null) {
                if (i == oldPointer) {
                    pointer = grown.used;
                }
                grown.add(old.key(i), old.hashes[i], old.value(i));
            }
        }
        store = grown;
        if (iterations != 0) {
            RefIteration.rehashed(this, old.keyArray(), old.used);
        }
        return grown;
    }

    /** php-src's hash of an int key is the int itself; TruffleString caches its own hash code. */
    private static int hash(Object key) {
        if (key instanceof Long index) {
            return Long.hashCode(index);
        }
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

    /**
     * The hash strategy's store, php-src's layout: slots in insertion order (keys, their hashes, values; a deleted
     * slot's key is null) and, once there are more than {@link #MIN_CAPACITY} slots, an index of bucket heads with
     * a chain per slot. Below that a lookup scans the hashes, as TruffleRuby's packed hashes do: most hashed PHP
     * arrays are small records, which then also copy as three arrays instead of five.
     */
    private static final class Hash {
        /**
         * Slot i's key at 2i and its value at 2i + 1: one array, so a lookup that checks a slot's key reads its
         * value under the same bounds test and with no other load (a remembered slot's check, at every string-key
         * read).
         */
        final Object[] entries;
        final int[] hashes;
        /** Bucket heads and chains, or null while the store is small. */
        final int[] buckets;
        final int[] chain;
        /** The slots used, including deleted ones. */
        int used;

        Hash(int capacity) {
            entries = new Object[2 * capacity];
            hashes = new int[capacity];
            if (capacity > MIN_CAPACITY) {
                buckets = new int[capacity];
                Arrays.fill(buckets, -1);
                chain = new int[capacity];
            } else {
                buckets = null;
                chain = null;
            }
        }

        private Hash(Hash original) {
            entries = original.entries.clone();
            hashes = original.hashes.clone();
            buckets = original.buckets == null ? null : original.buckets.clone();
            chain = original.chain == null ? null : original.chain.clone();
            used = original.used;
        }

        Hash copy() {
            return new Hash(this);
        }

        int capacity() {
            return hashes.length;
        }

        Object key(int slot) {
            return entries[2 * slot];
        }

        Object value(int slot) {
            return entries[2 * slot + 1];
        }

        void setValue(int slot, Object value) {
            entries[2 * slot + 1] = value;
        }

        /** Empties a slot (a removal): its key and its value. */
        void clear(int slot) {
            entries[2 * slot] = null;
            entries[2 * slot + 1] = null;
        }

        /** The keys of the slots used, as RefIteration.rehashed reads them. */
        Object[] keyArray() {
            Object[] keys = new Object[used];
            for (int i = 0; i < used; i++) {
                keys[i] = key(i);
            }
            return keys;
        }

        /** Appends a slot; the caller has checked there is room. */
        void add(Object key, int h, Object value) {
            int slot = used++;
            entries[2 * slot] = key;
            entries[2 * slot + 1] = value;
            hashes[slot] = h;
            if (buckets != null) {
                int bucket = h & (buckets.length - 1);
                chain[slot] = buckets[bucket];
                buckets[bucket] = slot;
            }
        }

        int find(Object key, int h) {
            if (buckets == null) {
                for (int slot = 0; slot < used; slot++) {
                    if (hashes[slot] == h && keyEquals(key(slot), key)) {
                        return slot;
                    }
                }
                return -1;
            }
            for (int slot = buckets[h & (buckets.length - 1)]; slot >= 0; slot = chain[slot]) {
                if (hashes[slot] == h && keyEquals(key(slot), key)) {
                    return slot;
                }
            }
            return -1;
        }

        /** Finds key and takes its slot out of the index (the caller clears the slot); -1 if absent. */
        int unlink(Object key, int h) {
            if (buckets == null) {
                return find(key, h);
            }
            int bucket = h & (buckets.length - 1);
            int previous = -1;
            for (int slot = buckets[bucket]; slot >= 0; slot = chain[slot]) {
                if (hashes[slot] == h && keyEquals(key(slot), key)) {
                    if (previous < 0) {
                        buckets[bucket] = chain[slot];
                    } else {
                        chain[previous] = chain[slot];
                    }
                    return slot;
                }
                previous = slot;
            }
            return -1;
        }

        int nextUsed(int from) {
            for (int i = from; i < used; i++) {
                if (entries[2 * i] != null) {
                    return i;
                }
            }
            return -1;
        }
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder out = new StringBuilder("array(");
        for (int p = firstPosition(); p >= 0; p = nextPosition(p)) {
            out.append(keyAt(p)).append("=>").append(valueAt(p)).append(", ");
        }
        return out.append(")").toString();
    }
}
