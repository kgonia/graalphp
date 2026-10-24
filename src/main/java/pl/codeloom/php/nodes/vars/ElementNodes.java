package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/**
 * Array element access as DSL nodes: one specialization per container type and array storage, chosen by guards
 * that are checked at every access. A site compiles only the cases it has met, so a loop over an int-strategy
 * array holds no calls or field writes for other storages and can keep the array's fields in registers.
 */
public final class ElementNodes {
    private ElementNodes() {
    }

    /** Normalizes an array key ({@link ArrayKey}); a site that always sees one string instance does it once. */
    @ReportPolymorphism
    public abstract static class ArrayKeyNode extends Node {
        public abstract Object execute(Object key);

        @Specialization
        protected static long intKey(long key) {
            return key;
        }

        @Specialization(guards = "key == cachedKey", limit = "1")
        protected static Object constantString(TruffleString key, @Cached("key") TruffleString cachedKey,
                                               @Cached("normalize(key)") Object normalized) {
            return normalized;
        }

        @Specialization(replaces = "constantString")
        protected static Object string(TruffleString key) {
            return ArrayKey.ofString(key);
        }

        @Fallback
        protected static Object other(Object key) {
            return ArrayKey.of(key);
        }

        protected static Object normalize(TruffleString key) {
            return ArrayKey.ofString(key);
        }
    }

    /**
     * A string-key lookup that remembers the slot where the key was found (PhpArray.slotOf) and confirms it next
     * time with one identity check before hashing: arrays built the same way keep a key at the same slot.
     */
    public static final class SlotCacheNode extends Node {
        private int slotGuess = -1;

        public static SlotCacheNode create() {
            return new SlotCacheNode();
        }

        /** The dereferenced element under a normalized key, or null when absent. */
        public Object lookup(PhpArray array, Object key) {
            if (!(key instanceof TruffleString)) {
                return array.get(key);
            }
            int slot = slotGuess;
            if (!array.isKeyAt(slot, key)) {
                slot = array.slotOf(key);
                if (slot < 0) {
                    return null;
                }
                slotGuess = slot;
            }
            return PhpRef.deref(array.rawAt(slot));
        }
    }

    /**
     * `$container[key]` read: the dereferenced element, a string's byte or an ArrayAccess object's offsetGet();
     * PhpNull.NULL when there is none. The key comes as written: arrays normalize it, the others take it as is.
     */
    @ReportPolymorphism
    public abstract static class ElementReadNode extends Node {
        public abstract Object execute(Object container, Object key);

        public abstract long executeLong(Object container, Object key) throws UnexpectedResultException;

        @Specialization(guards = "array.holdsLongAt(index)")
        protected static long longElement(PhpArray array, long index) {
            return array.longAt(index);
        }

        @Specialization
        protected static Object intKey(PhpArray array, long index) {
            Object element = array.get(index);
            return element == null ? PhpNull.NULL : element;
        }

        @Specialization
        protected static Object otherKey(PhpArray array, Object key, @Cached ArrayKeyNode toKey,
                                         @Cached SlotCacheNode slots) {
            Object element = slots.lookup(array, toKey.execute(key));
            return element == null ? PhpNull.NULL : element;
        }

        @Specialization
        protected static TruffleString stringOffset(TruffleString string, Object key,
                                                    @Cached TruffleString.ReadByteNode readByte) {
            long offset = Convert.toLong(key);
            int length = string.byteLength(Str.ENC);
            if (offset < 0) {
                offset += length;
            }
            if (offset < 0 || offset >= length) {
                return Str.EMPTY;
            }
            return Str.singleByte(readByte.execute(string, (int) offset, Str.ENC));
        }

        @Specialization(guards = "object.isArrayAccess()")
        protected Object arrayAccess(PhpObject object, Object key) {
            return PhpRef.deref(object.callMethod(this, "offsetget", key));
        }

        @Fallback
        protected static Object other(Object container, Object key) {
            return PhpNull.NULL;
        }
    }

    /** `$array[key] = value` into an array the caller has made writable; the key comes as written. */
    @ReportPolymorphism
    public abstract static class ElementWriteNode extends Node {
        public abstract void execute(PhpArray array, Object key, Object value);

        public abstract void executeLong(PhpArray array, Object key, long value);

        @Specialization(guards = "array.holdsLongAt(index)")
        protected static void longElement(PhpArray array, long index, long value) {
            array.setLongAt(index, value);
        }

        @Specialization
        protected static void intKey(PhpArray array, long index, Object value) {
            array.put(index, value);
        }

        @Specialization
        protected static void otherKey(PhpArray array, Object key, Object value, @Cached ArrayKeyNode toKey) {
            array.put(toKey.execute(key), value);
        }
    }
}
