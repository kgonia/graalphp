package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.nodes.LargeCode;
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

    /**
     * Normalizes an array key ({@link ArrayKey}); a site that always sees one string instance does it once. It
     * reports no polymorphism: a site meeting a second key (add_filter's $wp_filter[$hook_name], called from ~2000
     * sites with ~2000 hook names) made its function split per caller, 63% of a WordPress server's split decisions,
     * for copies that fold one key in functions run once per request.
     */
    @GenerateUncached
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
     * time with one identity check before hashing: arrays built the same way keep a key at the same slot. Kept to
     * that one check inline: every element access site carries it, and larger inline lookups (a scan, string
     * equality) grew the graphs of PHP functions enough to stop their calls being inlined.
     *
     * <p>In a {@link LargeCode large} function, a site outside any loop is only the call: the inline check and its
     * miss's call were about 550 bytes of machine code a site, the call alone 240, and WP_Query::get_posts has
     * 460 such reads, which run once per call of it; its code was 9% under HotSpot's install limit.
     */
    public static final class SlotCacheNode extends Node {
        /** For the uncached bytecode interpreter: shared, so it remembers nothing. */
        private static final SlotCacheNode UNCACHED = new SlotCacheNode(false);
        private static final byte UNDECIDED = 0;
        private static final byte INLINE = 1;
        private static final byte CALL = 2;

        private final boolean remembers;
        private int slotGuess = -1;
        /** Whether compiled code checks the remembered slot inline or only calls (decided on first use). */
        @CompilationFinal private byte form = UNDECIDED;

        private SlotCacheNode(boolean remembers) {
            this.remembers = remembers;
        }

        public static SlotCacheNode create() {
            return new SlotCacheNode(true);
        }

        public static SlotCacheNode getUncached() {
            return UNCACHED;
        }

        @Override
        public boolean isAdoptable() {
            return remembers;
        }

        /**
         * The dereferenced element under a normalized key, PhpNull.NULL when absent. Only the remembered slot's
         * check is inline; a miss (another slot, an absent key, a packed array) is one call, which remembers the
         * slot.
         */
        public Object lookup(PhpArray array, Object key) {
            if (!(key instanceof TruffleString)) {
                Object element = array.get(key);
                return element == null ? PhpNull.NULL : element;
            }
            if (remembers) {
                if (form == UNDECIDED) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    form = decideForm();
                }
                if (form == INLINE) {
                    Object raw = array.rawIfKeyAt(slotGuess, key);
                    if (raw != null) {
                        return PhpRef.deref(raw);
                    }
                }
            }
            return find(array, key);
        }

        /**
         * The call alone in a large function outside any loop, else the inline check. ponytail: the bytecode
         * interpreter's loops are no nodes, so in a large bytecode function every site is the call, in loops too.
         */
        private byte decideForm() {
            RootNode root = getRootNode();
            if (!(root instanceof LargeCode code) || !code.isLarge()) {
                return INLINE;
            }
            for (Node node = getParent(); node != null && node != root; node = node.getParent()) {
                if (node instanceof RepeatingNode || node instanceof LoopNode) {
                    return INLINE;
                }
            }
            return CALL;
        }

        @TruffleBoundary
        private Object find(PhpArray array, Object key) {
            int slot = array.slotOf(key);
            if (slot < 0) {
                return PhpNull.NULL;
            }
            if (remembers) {
                slotGuess = slot;
            }
            return PhpRef.deref(array.hashValueAt(slot));
        }
    }

    /**
     * `$container[key]` read: the dereferenced element, a string's byte or an ArrayAccess object's offsetGet();
     * PhpNull.NULL when there is none. The key comes as written: arrays normalize it, the others take it as is.
     */
    @ReportPolymorphism
    @GenerateUncached
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class ElementReadNode extends Node {
        public abstract Object execute(Object container, Object key);

        public abstract long executeLong(Object container, Object key) throws UnexpectedResultException;

        @Specialization(guards = "array.holdsLongAt(index)")
        public static long longElement(PhpArray array, long index) {
            return array.longAt(index);
        }

        @Specialization(guards = "array.isPacked()")
        public static Object intKey(PhpArray array, long index) {
            Object element = array.get(index);
            return element == null ? PhpNull.NULL : element;
        }

        @Specialization(guards = "!array.isPacked()")
        public static Object hashIntKey(PhpArray array, long index) {
            Object element = PhpRef.deref(array.hashGetLong(index));
            return element == null ? PhpNull.NULL : element;
        }

        @Specialization
        public static Object otherKey(PhpArray array, Object key, @Shared @Cached ArrayKeyNode toKey,
                                         @Shared @Cached SlotCacheNode slots) {
            return slots.lookup(array, toKey.execute(key));
        }

        @Specialization
        public static TruffleString stringOffset(TruffleString string, Object key,
                                                    @Shared @Cached TruffleString.ReadByteNode readByte) {
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
        public static Object arrayAccess(PhpObject object, Object key, @Bind Node node) {
            return PhpRef.deref(object.callMethod(node, "offsetget", key));
        }

        /**
         * With otherKey's and stringOffset's caches, unused: a split bytecode copy must not take an array or a string
         * here (PhpBytecodeRootNode#isCloningAllowed).
         */
        @Fallback
        public static Object other(Object container, Object key, @Shared @Cached ArrayKeyNode toKey,
                                   @Shared @Cached SlotCacheNode slots,
                                   @Shared @Cached TruffleString.ReadByteNode readByte) {
            return PhpNull.NULL;
        }
    }

    /** `$array[key] = value` into an array the caller has made writable; the key comes as written. */
    @ReportPolymorphism
    @GenerateUncached
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
