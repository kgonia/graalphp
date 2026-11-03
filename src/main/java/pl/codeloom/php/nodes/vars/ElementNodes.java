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
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.nodes.LargeCode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Operators;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.SourceLocation;
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
     *
     * <p>A read's lookup (warns: ElementReadNode's) raises "Undefined array key" for a missing string key in the
     * call a miss makes anyway, so the warning adds nothing to the inline code.
     */
    public static final class SlotCacheNode extends Node {
        /** For the uncached bytecode interpreter: shared, so it remembers nothing. */
        private static final SlotCacheNode UNCACHED = new SlotCacheNode(false, false);
        private static final SlotCacheNode UNCACHED_READ = new SlotCacheNode(false, true);
        private static final byte UNDECIDED = 0;
        private static final byte INLINE = 1;
        private static final byte CALL = 2;

        private final boolean remembers;
        private final boolean warns;
        private int slotGuess = -1;
        /** Whether compiled code checks the remembered slot inline or only calls (decided on first use). */
        @CompilationFinal private byte form = UNDECIDED;

        private SlotCacheNode(boolean remembers, boolean warns) {
            this.remembers = remembers;
            this.warns = warns;
        }

        public static SlotCacheNode create() {
            return new SlotCacheNode(true, false);
        }

        public static SlotCacheNode getUncached() {
            return UNCACHED;
        }

        /** A read's (FETCH_DIM_R): a missing key warns. */
        public static SlotCacheNode createRead() {
            return new SlotCacheNode(true, true);
        }

        public static SlotCacheNode getUncachedRead() {
            return UNCACHED_READ;
        }

        @Override
        public boolean isAdoptable() {
            return remembers;
        }

        /**
         * The dereferenced element under a normalized key, PhpNull.NULL when absent (after the warning, for a
         * read's). Only the remembered slot's check is inline; a miss (another slot, an absent key, a packed array)
         * is one call, which remembers the slot.
         */
        public Object lookup(PhpArray array, Object key) {
            if (!(key instanceof TruffleString)) {
                Object element = array.get(key);
                return element != null ? element : warns ? missing(key) : PhpNull.NULL;
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
                return warns ? missing(key) : PhpNull.NULL;
            }
            if (remembers) {
                slotGuess = slot;
            }
            return PhpRef.deref(array.hashValueAt(slot));
        }

        /** A missing key of a read: null after the warning. */
        @TruffleBoundary
        private Object missing(Object key) {
            return ElementReadNode.undefinedKey(this, key);
        }
    }

    /**
     * `$container[key]` read as an operand (FETCH_DIM_R, zend_fetch_dimension_address_read): the dereferenced
     * element, a string's byte or an ArrayAccess object's offsetGet(). What php-src warns about it does, on branches
     * a site that hasn't met them compiles as deoptimizations: a missing key reads null after "Undefined array key";
     * an offset out of a string's range reads "" after "Uninitialized string offset"; another scalar reads null
     * after "Trying to access array offset on"; an object that isn't an ArrayAccess is an Error. isset() and `??`
     * read with {@link ElementIfSetNode}. The key comes as written: arrays normalize it, the others take it as is.
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
        public static Object intKey(PhpArray array, long index, @Bind Node node,
                                    @Shared("missing") @Cached InlinedBranchProfile missing) {
            Object element = array.get(index);
            if (element == null) {
                missing.enter(node);
                return undefinedKey(node, index);
            }
            return element;
        }

        @Specialization(guards = "!array.isPacked()")
        public static Object hashIntKey(PhpArray array, long index, @Bind Node node,
                                        @Shared("missing") @Cached InlinedBranchProfile missing) {
            Object element = PhpRef.deref(array.hashGetLong(index));
            if (element == null) {
                missing.enter(node);
                return undefinedKey(node, index);
            }
            return element;
        }

        /** A missing key warns in the lookup's call (SlotCacheNode.createRead): no inline code for it. */
        @Specialization
        public static Object otherKey(PhpArray array, Object key, @Cached ArrayKeyNode toKey,
                                      @Cached(value = "readSlots()", uncached = "uncachedReadSlots()")
                                      SlotCacheNode slots) {
            return slots.lookup(array, toKey.execute(key));
        }

        public static SlotCacheNode readSlots() {
            return SlotCacheNode.createRead();
        }

        public static SlotCacheNode uncachedReadSlots() {
            return SlotCacheNode.getUncachedRead();
        }

        @Specialization
        public static TruffleString stringOffset(TruffleString string, Object key, @Bind Node node,
                                                 @Cached TruffleString.ReadByteNode readByte,
                                                 @Shared("missing") @Cached InlinedBranchProfile missing) {
            long offset = key instanceof Long l ? l : stringOffsetOf(node, key);
            int length = string.byteLength(Str.ENC);
            if (offset < 0 ? -offset > length : offset >= length) {
                missing.enter(node);
                return uninitializedOffset(node, offset);
            }
            return Str.singleByte(readByte.execute(string, (int) (offset < 0 ? offset + length : offset), Str.ENC));
        }

        @Specialization(guards = "object.isArrayAccess()")
        public static Object arrayAccess(PhpObject object, Object key, @Bind Node node) {
            return PhpRef.deref(object.callMethod(node, "offsetget", key));
        }

        @Fallback
        public static Object other(Object container, Object key, @Bind Node node) {
            return notAnArray(node, container);
        }

        /** php-src's zend_undefined_offset and zend_undefined_index: the warning, then null. */
        @TruffleBoundary
        public static Object undefinedKey(Node node, Object normalized) {
            String key = normalized instanceof Long l ? l.toString()
                    : "\"" + Str.java((TruffleString) normalized) + "\"";
            warn(node, "Undefined array key " + key);
            return PhpNull.NULL;
        }

        @TruffleBoundary
        private static TruffleString uninitializedOffset(Node node, long offset) {
            warn(node, "Uninitialized string offset " + offset);
            return Str.EMPTY;
        }

        /**
         * A string offset that isn't an int (zend_fetch_dimension_address_read): a numeric string's int, with
         * "Illegal string offset" for trailing data; a float, null or bool cast after "String offset cast occurred";
         * another string or type is a TypeError.
         */
        @TruffleBoundary
        private static long stringOffsetOf(Node node, Object key) {
            if (key instanceof TruffleString s) {
                byte[] bytes = Str.readBytes(s);
                if (Convert.parseNumber(bytes, true) instanceof Long l) {
                    if (Convert.parseNumber(bytes, false) == null) {
                        warn(node, "Illegal string offset \"" + Str.java(s) + "\"");
                    }
                    return l;
                }
                throw illegalOffset(key);
            }
            if (key instanceof Double || key instanceof Boolean || key == PhpNull.NULL) {
                warn(node, "String offset cast occurred");
                return Convert.toLong(key);
            }
            throw illegalOffset(key);
        }

        private static PhpError illegalOffset(Object key) {
            return new PhpError("TypeError", "Cannot access offset of type " + Operators.zvalTypeName(key)
                    + " on string");
        }

        /** An element read of a value that is no container: null after php-src's warning; an object's Error. */
        @TruffleBoundary
        private static Object notAnArray(Node node, Object container) {
            if (container instanceof PhpObject) {
                throw new PhpError("Cannot use object of type " + Operators.zvalTypeName(container) + " as array");
            }
            warn(node, "Trying to access array offset on " + Args.valueName(container));
            return PhpNull.NULL;
        }

        private static void warn(Node node, String message) {
            ErrorFunctions.raise(PhpContext.get(node), ErrorFunctions.E_WARNING, Str.of(message),
                    SourceLocation.of(node));
        }
    }

    /**
     * `$container[key]` as isset(), `??` and the containers of writes and unsets read it (BP_VAR_IS, BP_VAR_W,
     * BP_VAR_UNSET): as ElementReadNode, quietly; PhpNull.NULL when there is no element, also out of a string.
     */
    @ReportPolymorphism
    @GenerateUncached
    public abstract static class ElementIfSetNode extends Node {
        public abstract Object execute(Object container, Object key);

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
        public static Object otherKey(PhpArray array, Object key, @Cached ArrayKeyNode toKey,
                                      @Cached SlotCacheNode slots) {
            return slots.lookup(array, toKey.execute(key));
        }

        @Specialization
        public static TruffleString stringOffset(TruffleString string, Object key,
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
        public static Object arrayAccess(PhpObject object, Object key, @Bind Node node) {
            return PhpRef.deref(object.callMethod(node, "offsetget", key));
        }

        @Fallback
        public static Object other(Object container, Object key) {
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
