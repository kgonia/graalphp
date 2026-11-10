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
import com.oracle.truffle.api.nodes.Node;
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
     * the same way keep a key at the same slot. Every read's and test's site starts with it.
     * <li><b>the probe</b> (PhpArray.slotAtHome: the key's hash to its home in the index, then the identity of the
     * key there), for a site whose remembered slot keeps missing: the key varies ($row[$column]), the arrays differ
     * in layout, or the key is absent (isset() of an optional key). Such a site made a call at every access. The
     * probe answers for an interned key that is first at its home and for an absent one whose home is free; a key
     * displaced from its home and an equal key of another instance are the call's.
     * <li><b>the call alone</b>, in a {@link LargeCode large} function: the inline check and its
     * miss's call were about 550 bytes of machine code a site, the call alone 240, and WP_Query::get_posts has 460
     * such reads, which run once per call of it; its code was 9% under HotSpot's install limit.
     * </ul>
     *
     * <p>What a site holds inline is bounded by its machine code, and WordPress's render is what measures that, no
     * kernel does (wiki/ceiling.md). The probe as the index's whole walk (php-src's zend_hash_find_known_hash) was
     * 2.3-2.8 KB a site: the front page rendered 6% slower with it, after twice the warm-up and with a quarter
     * more compiled code. Its first step alone keeps the kernels' gain but for a write under a varying key (5.3
     * to 7.5 ns), at about 0.2 KB a site over the remembered slot; a write's inline store is about 0.6 KB over its
     * call. Both are a function's rich sites, {@link LargeCode#RICH} of them: a site takes one when it has shown, in
     * the interpreter, that it needs it (the remembered slot kept missing; a write kept overwriting), so the sites
     * that run most take them first. The others stay as they were.
     *
     * <p>A write's site ({@link #store}) starts as the call: a site that builds an array inserts, which no inline
     * form does. One that overwrites earns the inline store, and from there may become a probe as any site.
     *
     * <p>The key comes as written and is normalized ({@link ArrayKeyNode}) only when no slot was found: a key found
     * by identity is a stored key, so it is normalized already.
     *
     * <p>A site changes its form only while it runs in the interpreter, and its compiled code is not invalidated
     * for it: every form is complete, and invalidating a function once per site would be a deoptimization cycle
     * (Graal gives a function up after fifteen recompilations).
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
        /** A write's site before it earned the inline store: compiled as {@link #CALL}. */
        private static final byte EARNING = 4;
        /** {@link #score}: a miss adds this, a hit takes one off, so a site missing once in eight stays as it is. */
        private static final int MISS = 8;
        /** {@link #score} at which the site becomes a probe: fifteen misses more than its hits pay for. */
        private static final int PROBES_AT = 120;
        /** {@link #score} at which a write's site earned the inline store. */
        private static final int EARNS_AT = 16;
        /** {@link #score} of a site that asked for a rich form and got none: it asks no more. */
        private static final byte SETTLED = -1;

        private final boolean remembers;
        private final boolean warns;
        private int slotGuess = -1;
        /**
         * How compiled code finds the slot, decided on first use. In the interpreter {@link #EARNING} becomes
         * {@link #INLINE} or {@link #CALL}, and {@link #INLINE} may become {@link #PROBE}.
         */
        @CompilationFinal private byte form = UNDECIDED;
        /**
         * Counted in the interpreter only. {@link #INLINE}: how badly the remembered slot does ({@link #judge}).
         * {@link #EARNING}: the overwrites more than the inserts ({@link #earn}).
         */
        private byte score;
        /** The key's hash for the probe; null until the site is one. */
        @Child private TruffleString.HashCodeNode hashCode;
        /**
         * The key a site became a probe with and its hash, null once the interpreter met another key there: a
         * site with one key (a literal) has the hash as a constant. Compiled code that holds a stale one still
         * compares the key.
         */
        @CompilationFinal private TruffleString hashedKey;
        @CompilationFinal private int keyHash;

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
                byte f = form(false);
                if (f == INLINE) {
                    // the key's check loads the value with it (rawIfKeyAt)
                    Object raw = array.rawIfKeyAt(slotGuess, string);
                    if (CompilerDirectives.inInterpreter()) {
                        judge(raw != null, string);
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

        /**
         * Whether the array has an element under a key as written (array_key_exists: a null element counts). Each
         * form answers on its own branch, as in {@link #lookup}: a slot handed on as an int, tested once more
         * after the forms met, made the compiler copy what follows per answer (4.4 calls a site, 1.1 KB for 150
         * bytes; wiki/ceiling.md).
         */
        public boolean has(PhpArray array, Object rawKey, ArrayKeyNode toKey) {
            int slot = PhpArray.UNSURE;
            if (remembers && rawKey instanceof TruffleString string) {
                byte f = form(false);
                if (f == INLINE) {
                    boolean hit = array.isKeyAt(slotGuess, string);
                    if (CompilerDirectives.inInterpreter()) {
                        judge(hit, string);
                    }
                    if (hit) {
                        return true;
                    }
                } else if (f == PROBE) {
                    slot = probe(array, string);
                    if (slot >= 0) {
                        return true;
                    }
                }
            }
            Object key = toKey.execute(rawKey);
            if (slot == PhpArray.ABSENT && key instanceof TruffleString) {
                return false;
            }
            return holds(array, key);
        }

        /**
         * `$array[key] = value` under a key as written, into an array the caller has made writable. Inline is the
         * store of a plain value over a plain one (PhpArray.replacePlainAt) at a site that earned it; an insert, a
         * counted value and a reference's slot are put()'s call.
         */
        public void store(PhpArray array, Object rawKey, Object value, ArrayKeyNode toKey) {
            if (remembers && rawKey instanceof TruffleString string) {
                byte f = form(true);
                if (f == INLINE) {
                    int guess = slotGuess;
                    if (CompilerDirectives.inInterpreter()) {
                        judge(array.isKeyAt(guess, string), string);
                    }
                    if (array.replacePlainIfKeyAt(guess, string, value)) {
                        return;
                    }
                } else if (f == PROBE) {
                    int slot = probe(array, string);
                    if (slot >= 0 && array.replacePlainAt(slot, value)) {
                        return;
                    }
                }
            }
            Object key = toKey.execute(rawKey);
            if (key instanceof TruffleString string) {
                put(array, string, value, CompilerDirectives.inInterpreter());
            } else {
                array.put(key, value);
            }
        }

        private byte form(boolean writes) {
            byte f = form;
            if (f == UNDECIDED) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                form = f = decideForm(writes);
            }
            return f;
        }

        private int probe(PhpArray array, TruffleString key) {
            TruffleString.HashCodeNode hasher = hashCode;
            if (hasher == null) {
                return PhpArray.UNSURE;
            }
            TruffleString hashed = hashedKey;
            if (CompilerDirectives.inInterpreter() && hashed != null && hashed != key) {
                hashedKey = hashed = null;
            }
            // a literal key is a constant here, as hashed is: the hash folds
            int h = hashed != null && hashed == key ? keyHash : hasher.execute(key, Str.ENC);
            return array.slotAtHome(key, h);
        }

        /**
         * In the interpreter: a site whose remembered slot keeps missing becomes a probe if its function has a
         * rich site left, and stays as it is if not; with no invalidation.
         */
        private void judge(boolean hit, TruffleString key) {
            int s = score;
            if (s == SETTLED) {
                return;
            }
            if (hit) {
                if (s > 0) {
                    score = (byte) (s - 1);
                }
            } else if (s + MISS < PROBES_AT) {
                score = (byte) (s + MISS);
            } else if (takeRich()) {
                hashCode = insert(TruffleString.HashCodeNode.create());
                hashedKey = key;
                keyHash = key.hashCode();
                form = PROBE;
            } else {
                score = SETTLED;
            }
        }

        /**
         * In the interpreter, a write's site that is the call so far ({@link #EARNING}): after {@link #EARNS_AT}
         * overwrites more than inserts it takes the inline store, if its function has a rich site left, and is the
         * call for good if not.
         */
        private void earn(boolean overwrote) {
            int s = score;
            if (!overwrote) {
                if (s > 0) {
                    score = (byte) (s - 1);
                }
            } else if (s + 1 < EARNS_AT) {
                score = (byte) (s + 1);
            } else {
                score = 0;
                form = takeRich() ? INLINE : CALL;
            }
        }

        private boolean takeRich() {
            return !(getRootNode() instanceof LargeCode code) || code.takeRich();
        }

        /**
         * The call alone in a large function (in its loops too: the bytecode interpreter's loops are no nodes a
         * site could find itself in); elsewhere a read's and a test's site starts with the remembered slot, a
         * write's with the call it has to earn more than.
         */
        private byte decideForm(boolean writes) {
            if (getRootNode() instanceof LargeCode code && code.isLarge()) {
                return CALL;
            }
            return writes ? EARNING : INLINE;
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

        /** learns: in the interpreter, where a site that is {@link #EARNING} counts what its writes did. */
        @TruffleBoundary
        private void put(PhpArray array, TruffleString key, Object value, boolean learns) {
            int before = array.size();
            int slot = array.putString(key, value);
            if (remembers) {
                slotGuess = slot;
                if (learns && form == EARNING) {
                    earn(array.size() == before);
                }
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
