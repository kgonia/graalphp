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
import pl.codeloom.php.runtime.PhpClosure;
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

        /**
         * A key that can't be an int ("" or longer than an int's digits, or starting with neither a digit nor a
         * minus) is itself, decided inline; the rest is {@link ArrayKey#ofString}'s call.
         */
        @Specialization(replaces = "constantString")
        protected static Object string(TruffleString key, @Cached TruffleString.ReadByteNode read) {
            int length = key.byteLength(Str.ENC);
            if (length == 0 || length > 20) {
                return key;
            }
            int first = read.execute(key, 0, Str.ENC);
            if (first > '9' || first < '0' && first != '-') {
                return key;
            }
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
     * A string-key access that finds the key's slot with no call where it can, in one of three forms a site settles
     * on before it is compiled:
     *
     * <ul>
     * <li><b>the remembered slot</b> (PhpArray.slotOf, then one identity check before anything else): arrays built
     * the same way keep a key at the same slot. Every site starts with it: larger inline lookups at every site grew
     * the graphs of PHP functions enough to stop their calls being inlined.
     * <li><b>the probe</b> (PhpArray.slotByString: the key's hash into the index, then the identity of the key it
     * leads to), for a site whose remembered slot keeps missing: the key varies ($row[$column]), the
     * arrays differ in layout, or the key is absent (isset() of an optional key). Such a site made a call at every
     * access. The probe answers for an interned key, present or absent, as php-src's zend_hash_find_known_hash
     * does; an equal key of another instance is the call's.
     * <li><b>the call alone</b>, in a {@link LargeCode large} function outside any loop: the inline check and its
     * miss's call were about 550 bytes of machine code a site, the call alone 240, and WP_Query::get_posts has 460
     * such reads, which run once per call of it; its code was 9% under HotSpot's install limit.
     * </ul>
     *
     * <p>The key comes as written and is normalized ({@link ArrayKeyNode}) only when no slot was found: a key found
     * by identity is a stored key, so it is normalized already.
     *
     * <p>A site becomes a probe only while it runs in the interpreter, and its compiled code is not invalidated for
     * it: either form is complete, and invalidating a function once per site would be a deoptimization cycle (Graal
     * gives a function up after fifteen recompilations).
     *
     * <p>A read's lookup (warns: ElementReadNode's) raises "Undefined array key" for a missing string key in a
     * call, so the warning adds nothing to the inline code.
     */
    public static final class SlotCacheNode extends Node {
        /** For the uncached bytecode interpreter: shared, so it remembers nothing. */
        private static final SlotCacheNode UNCACHED = new SlotCacheNode(false, false);
        private static final SlotCacheNode UNCACHED_READ = new SlotCacheNode(false, true);
        private static final byte UNDECIDED = 0;
        private static final byte INLINE = 1;
        private static final byte CALL = 2;
        private static final byte PROBE = 3;
        /** {@link #doubt}: a miss adds this, a hit takes one off, so a site missing once in eight stays as it is. */
        private static final int MISS = 8;
        /** {@link #doubt} at which the site becomes a probe: fifteen misses more than its hits pay for. */
        private static final int PROBES_AT = 120;

        private final boolean remembers;
        private final boolean warns;
        private int slotGuess = -1;
        /** How compiled code finds the slot (decided on first use; {@link #INLINE} may become {@link #PROBE}). */
        @CompilationFinal private byte form = UNDECIDED;
        /** How badly the remembered slot does, counted in the interpreter only. */
        private byte doubt;
        /** The key's hash for the probe; null until the site is one. */
        @Child private TruffleString.HashCodeNode hashCode;

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
         * The dereferenced element under a key as written, PhpNull.NULL when absent (after the warning, for a
         * read's).
         */
        public Object lookup(PhpArray array, Object rawKey, ArrayKeyNode toKey) {
            int slot = PhpArray.UNSURE;
            if (remembers && rawKey instanceof TruffleString string) {
                byte f = form();
                if (f == INLINE) {
                    // the key's check loads the value with it (rawIfKeyAt)
                    Object raw = array.rawIfKeyAt(slotGuess, string);
                    if (CompilerDirectives.inInterpreter()) {
                        judge(raw != null);
                    }
                    if (raw != null) {
                        return PhpRef.deref(raw);
                    }
                } else if (f == PROBE) {
                    slot = probe(array, string);
                    if (slot >= 0) {
                        return PhpRef.deref(array.hashValueAt(slot));
                    }
                }
            }
            Object key = toKey.execute(rawKey);
            if (!(key instanceof TruffleString)) {
                Object element = array.get(key);
                return element != null ? element : warns ? missing(key) : PhpNull.NULL;
            }
            if (slot == PhpArray.ABSENT) {
                return warns ? missing(key) : PhpNull.NULL;
            }
            return find(array, key);
        }

        /** Whether the array has an element under a key as written (array_key_exists: a null element counts). */
        public boolean has(PhpArray array, Object rawKey, ArrayKeyNode toKey) {
            int slot = PhpArray.UNSURE;
            if (remembers && rawKey instanceof TruffleString string) {
                slot = inlineSlot(array, string);
                if (slot >= 0) {
                    return true;
                }
            }
            Object key = toKey.execute(rawKey);
            if (slot == PhpArray.ABSENT && key instanceof TruffleString) {
                return false;
            }
            return holds(array, key);
        }

        /** `$array[key] = value` under a key as written, into an array the caller has made writable. */
        public void store(PhpArray array, Object rawKey, Object value, ArrayKeyNode toKey) {
            if (remembers && rawKey instanceof TruffleString string) {
                int slot = inlineSlot(array, string);
                if (slot >= 0) {
                    array.replaceAt(slot, value);
                    return;
                }
            }
            Object key = toKey.execute(rawKey);
            if (key instanceof TruffleString string) {
                put(array, string, value);
            } else {
                array.put(key, value);
            }
        }

        /**
         * The slot of a string key as written, found without a call: a slot, {@link PhpArray#UNSURE} when only a
         * full lookup can tell, or (a probe's) {@link PhpArray#ABSENT}, which holds if the key is a normalized one.
         */
        private int inlineSlot(PhpArray array, TruffleString key) {
            byte f = form();
            if (f == INLINE) {
                int guess = slotGuess;
                boolean hit = array.isKeyAt(guess, key);
                if (CompilerDirectives.inInterpreter()) {
                    judge(hit);
                }
                return hit ? guess : PhpArray.UNSURE;
            }
            return f == PROBE ? probe(array, key) : PhpArray.UNSURE;
        }

        private byte form() {
            byte f = form;
            if (f == UNDECIDED) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                form = f = decideForm();
            }
            return f;
        }

        private int probe(PhpArray array, TruffleString key) {
            TruffleString.HashCodeNode hasher = hashCode;
            return hasher == null ? PhpArray.UNSURE : array.slotByString(key, hasher.execute(key, Str.ENC));
        }

        /** In the interpreter: a site whose remembered slot keeps missing becomes a probe, with no invalidation. */
        private void judge(boolean hit) {
            int d = doubt;
            if (hit) {
                if (d > 0) {
                    doubt = (byte) (d - 1);
                }
            } else if (d + MISS < PROBES_AT) {
                doubt = (byte) (d + MISS);
            } else {
                hashCode = insert(TruffleString.HashCodeNode.create());
                form = PROBE;
            }
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

        @TruffleBoundary
        private boolean holds(PhpArray array, Object key) {
            if (!(key instanceof TruffleString)) {
                return array.containsKey(key);
            }
            int slot = array.slotOf(key);
            if (slot >= 0 && remembers) {
                slotGuess = slot;
            }
            return slot >= 0;
        }

        @TruffleBoundary
        private void put(PhpArray array, TruffleString key, Object value) {
            int slot = array.putString(key, value);
            if (remembers) {
                slotGuess = slot;
            }
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
     * It reports no polymorphism: its specializations are the container's storage and the key's kind, a guard
     * each, which a copy per caller would save. WP_Hook::add_filter's `isset($this->callbacks[$priority])` meets an
     * empty array, then a hash, so add_filter and add_action were split per caller (5,300 copies on WooCommerce,
     * 97% never compiled, wiki/bytecode-dsl.md "Which splits pay"), as ArrayKeyNode's second key did on the tree.
     */
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
            return slots.lookup(array, key, toKey);
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
            if (offset < 0 ? offset < -length : offset >= length) {
                missing.enter(node);
                return uninitializedOffset(node, offset);
            }
            return Str.singleByte(readByte.execute(string, (int) (offset < 0 ? offset + length : offset), Str.ENC));
        }

        @Specialization(guards = "object.isArrayAccess()")
        public static Object arrayAccess(PhpObject object, Object key, @Bind Node node) {
            return pl.codeloom.php.runtime.Refcount.borrowed(PhpRef.deref(object.callMethod(node, "offsetget", key)));
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
     * It reports no polymorphism, as ElementReadNode: WP_Hook::add_filter's `isset($this->callbacks[$priority])` is
     * this node's read.
     */
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
            return slots.lookup(array, key, toKey);
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
            return pl.codeloom.php.runtime.Refcount.borrowed(PhpRef.deref(object.callMethod(node, "offsetget", key)));
        }

        /** A scalar or null reads as null (php-src's warning); an object that isn't ArrayAccess is an error. */
        @Fallback
        public static Object other(Object container, Object key) {
            if (container instanceof PhpObject || container instanceof PhpClosure) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Cannot use object of type " + Operators.zvalTypeName(container) + " as array");
            }
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
        protected static void otherKey(PhpArray array, Object key, Object value, @Cached ArrayKeyNode toKey,
                                       @Cached SlotCacheNode slots) {
            slots.store(array, key, value, toKey);
        }
    }

}
