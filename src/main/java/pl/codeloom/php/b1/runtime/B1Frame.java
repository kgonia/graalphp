package pl.codeloom.php.b1.runtime;

/** Logical PHP frame. Generated code names CVs and live ranges by stable index. */
public final class B1Frame {
    B1Frame caller;
    public String file, function;
    public int line;
    boolean staticCall;
    String scope;
    private String[] names;
    private final B1Ref[] locals;
    private final Object[] temps;
    private static final int[] NO_SENDS = {};
    private int[] outgoing = NO_SENDS;
    private final boolean top, eval;
    boolean strictTypes, internalCall;
    private long globalVersion = -1;
    private java.util.LinkedHashMap<String, B1Ref> dynamic;
    private Object[] extras;
    private B1Callable discardedCall;
    private boolean discardReturn;

    public void discardCallResult(B1Callable target) { discardedCall = target; }
    /** A callee's entry takes the caller's discard request: true when it was made for that callee. */
    boolean takeDiscard(B1Callable fn) {
        B1Callable target = discardedCall;
        if (target == null) return false;
        discardedCall = null;
        return target == fn;
    }
    /** A frame-based caller registered sends that its callee's entry releases (bound()). */
    boolean hasOutgoing() { return outgoing.length != 0; }
    /** The frame of a leaf call, built when something first needed it: what its entry recorded so far. */
    void materialized(int line, int arity, Object a0, Object a1, Object a2) {
        this.line = line;
        passed = sent = arity;
        m0 = a0; m1 = a1; m2 = a2;
    }
    /** A leaf call's positional argument becomes its CV (flags of B1RequestContext.enterLeaf: 1 moved). */
    public static Object takeArgument(B1RequestContext ctx, Object value, int flags) {
        if (value instanceof B1Ref ref) {
            Object plain = B1Object.retain(ref.get());
            if ((flags & 1) != 0) ref.unbind(ctx);
            return plain;
        }
        if ((flags & 1) == 0) B1Object.retain(value);
        return value;
    }
    /** The unused result of a call whose caller discards it; a failing release becomes the pending throw. */
    public static Throwable discarded(B1RequestContext ctx, Object value) {
        try { B1Object.release(ctx, value); return null; }
        catch (Throwable ex) { return ex; }
    }
    /** The end of a leaf call that never needed its frame, after its CVs: endLeave without a frame. */
    public static Object leafResult(B1RequestContext ctx, Throwable pending, Object result) {
        if (pending == null) return result;
        try { B1Object.release(ctx, result); } catch (Throwable ex) { pending = B1Thrown.chain(ctx, ex, pending); }
        B1Thrown.propagate(pending);
        return result;
    }

    B1Frame(B1Frame caller, String file, String function, String[] names, int ntemps, boolean top, boolean eval) {
        this.caller = caller;
        this.file = file;
        this.function = function;
        this.names = names;
        this.top = top;
        this.eval = eval;
        fast = ntemps < 0;
        locals = fast ? null : new B1Ref[names.length];
        temps = fast ? null : new Object[ntemps];
    }

    /** A pooled fast frame: the request reuses it at the same depth of its fast-call stack. */
    static B1Frame pooled() { return new B1Frame(null, null, null, null, -1, false, false); }
    /** Re-enters a pooled fast frame. Every field a fast call reads was reset when it last left ({@link #retire}). */
    B1Frame reenter(B1Frame caller, String file, String function, B1Callable fn) {
        this.caller = caller;
        this.file = file;
        this.function = function;
        this.names = fn.locals;
        strictTypes = fn.strictTypes;
        staticCall = fn.isStatic;
        scope = fn.scope;
        return this;
    }
    /** A fast frame left its call: drop what it borrowed or owned so the pool retains no PHP value. */
    void retire() {
        caller = null;
        line = 0;
        m0 = m1 = m2 = m3 = null;
        mirrors = null;
        extras = null;
        result = null;
        closure = nextClosure = null;
        discardedCall = null;
        discardReturn = false;
        internalCall = false;
        if (outgoing.length != 0) outgoing = NO_SENDS;
        // A finally region parks the pending throw and its exits on the frame (saveFlight, suspend).
        pending = null;
        suspended = null;
        file = function = scope = null;
        names = null;
    }

    // ------------------------------------------------------------------ fast frames
    // A fast frame's CVs and temporaries live in the generated method's JVM locals. A CV local holds UNDEF, a
    // plain value it owns, or (once the CV is a reference or a written place) a B1Ref binding it owns. The frame
    // keeps the call metadata: caller, line, scope, the passed arguments' current values (mirrors, for traces and
    // func_get_args) and extra arguments.
    /** An undefined CV in a fast frame's JVM local. Never a PHP value. */
    public static final Object UNDEF = new Object();
    final boolean fast;
    private boolean moved;
    // m0..m3: the first parameters' trace mirrors (public: generated code stores them directly).
    private Object[] mirrors;
    private int sent;

    /** Binds the call: consumes a mover's ownership, records extras. Parameters follow with {@link #param}. */
    /** Returns true when the call is positional within the fixed arguments: the generated code then binds each
     * parameter from its argument with {@link #take}. Otherwise it calls {@link #param}. */
    public boolean bindFast(B1RequestContext ctx, B1Callable fn, Object[] args, Object first, Object second, Object third) {
        boolean move = ctx.moved == fn;
        if (move) ctx.moved = null;
        moved = move;
        B1Frame from = caller;
        if (from != null && from.discardedCall != null) {
            discardReturn = from.discardedCall == fn;
            from.discardedCall = null;
        }
        if (args == null && !(first instanceof B1Callable.ShortNamed)) {
            // Up to the last passed argument: a named call can skip a leading parameter (OMITTED, then a value).
            int count = third != B1Callable.OMITTED ? 3 : second != B1Callable.OMITTED ? 2 : first != B1Callable.OMITTED ? 1 : 0;
            int arity = fn.arity() - (fn.variadic ? 1 : 0);
            sent = count;
            if (count <= arity && fn.arity() <= 4) { passed = count; return true; }
        }
        bindSlow(ctx, fn, args, first, second, third);
        return false;
    }
    /** A passed positional argument becomes the CV's own value (moved, or counted again). */
    public Object take(B1RequestContext ctx, Object value) {
        if (value instanceof B1Ref ref) {
            // A value forwarded as a binding: the parameter holds the referenced value.
            Object plain = B1Object.retain(ref.get());
            if (moved) ref.unbind(ctx);
            return plain;
        }
        if (!moved) B1Object.retain(value);
        return value;
    }
    /** A by-reference parameter shares the passed binding. */
    public Object takeRef(Object value) {
        if (!moved) B1Object.retain(value);
        return value;
    }
    public Object m0, m1, m2, m3;
    private void bindSlow(B1RequestContext ctx, B1Callable fn, Object[] args, Object first, Object second, Object third) {
        B1Callable.ShortNamed named = first instanceof B1Callable.ShortNamed values ? values : null;
        if (named != null) moved = false;
        int count = named != null ? named.count() : args != null ? args.length : third != B1Callable.OMITTED ? 3
                : second != B1Callable.OMITTED ? 2 : first != B1Callable.OMITTED ? 1 : 0;
        passed = Math.min(count,fn.arity() - (fn.variadic ? 1 : 0));
        sent = count;
        if (count > passed) {
            extras = args == null ? new Object[count - passed] : java.util.Arrays.copyOfRange(args, passed, count);
            for (int i = 0; i < extras.length; i++) {
                if (args == null) extras[i] = switch (passed + i) { case 0 -> first; case 1 -> second; default -> third; };
                if (!moved) B1Object.retain(extras[i]);
            }
        }
        if (fn.arity() > 4) mirrors = new Object[fn.arity() - 4];
    }
    /** The owned CV value of parameter i, or UNDEF when it was not passed. */
    public Object param(B1RequestContext ctx, B1Callable fn, int i, Object[] args, Object first, Object second, Object third) {
        if (i >= passed) return UNDEF;
        Object value = first instanceof B1Callable.ShortNamed named ? named.at(i) : args != null ? args[i]
                : i == 0 ? first : i == 1 ? second : third;
        if (value == B1Callable.OMITTED) return UNDEF;
        if (value instanceof B1Ref ref) {
            if (!moved) ref.bind();
            if (!fn.byRef(i)) {
                // A value forwarded as a binding: the parameter holds the referenced value.
                Object plain = B1Object.retain(ref.get());
                ref.unbind(ctx);
                mirror(i, plain);
                return plain;
            }
        } else if (!moved) B1Object.retain(value);
        mirror(i, value);
        return value;
    }
    /** After the parameters own their values: an old-mode caller's outgoing temporaries are released. */
    public void bound(B1RequestContext ctx) {
        if (!moved && caller != null && caller.outgoing.length != 0) for (int i = 0; i < sent; i++) takeSend(ctx, i);
    }
    /** Records the current CV of parameter i for traces and func_get_args. */
    public void mirror(int i, Object local) {
        switch (i) {
            case 0 -> m0 = local;
            case 1 -> m1 = local;
            case 2 -> m2 = local;
            case 3 -> m3 = local;
            default -> mirrors[i - 4] = local;
        }
    }
    private Object mirrored(int i) {
        Object v = switch (i) { case 0 -> m0; case 1 -> m1; case 2 -> m2; case 3 -> m3; default -> mirrors[i - 4]; };
        if (v instanceof B1Ref ref) return ref.get();
        return v == UNDEF ? null : v;
    }
    /** The variadic parameter's array of the extra arguments, as an owned CV. */
    public Object variadicFast(B1RequestContext ctx, int id) {
        // One spare slot: array_unshift($args, ...) (apply_filters) then prepends without regrowing.
        B1Array args = new B1Array(extras == null ? 0 : extras.length + 1);
        if (extras != null) for (Object extra : extras) args.append(ctx,extra);
        mirror(id, args);
        return args;
    }
    /** ZEND_RECV's checks on a fast CV. Returns the CV, replaced when a scalar parameter was coerced. */
    public Object checkFast(B1RequestContext ctx, Object local, int id, int required, int total, String type) {
        if (local == UNDEF) throw ctx.error("ArgumentCountError", "Too few arguments to function " + function
                + "(), " + passed + " passed" + callerLocation() + (total == required ? " and exactly " : " and at least ") + required + " expected");
        if (type == null || type.equals("mixed")) return local;
        Object value = local instanceof B1Ref ref ? ref.get() : local;
        Object coerced = scalar(ctx,value,type,caller != null && !caller.internalCall && caller.strictTypes);
        if (coerced == null) throw ctx.error("TypeError",function + "(): Argument #" + (id+1)
                + " ($" + names[id] + ") must be of type " + type + ", " + ctx.typeName(value) + " given"
                + (caller == null || caller.internalCall ? "" : ", called" + callerLocation()));
        if (coerced == value) return local;
        Object next = store(ctx, local, coerced);
        mirror(id, next);
        drop(ctx, local, next);
        return next;
    }

    /** A closure's captured zval for its CV: a binding is shared, a value counted again. */
    public Object captured(int i) {
        Object value = closure.capture(i);
        if (value instanceof B1Ref ref) { ref.bind(); return ref; }
        return B1Object.retain(value);
    }
    /** A temporary takes its value: a binding counts as a binding, a borrowed value is counted. */
    public static Object temp(Object value, boolean owned) {
        if (value instanceof B1Ref ref) ref.bind();
        else if (!owned) B1Object.retain(value);
        return value;
    }
    /** =& : the place becomes a reference held by the CV too. */
    public static B1Ref bindRef(B1RequestContext ctx, B1Ref place) {
        place.markReference(ctx);
        place.bind();
        return place;
    }
    /** global $x: the CV binds the request's global slot. */
    public static Object globalFast(B1RequestContext ctx, Object local, B1Frame frame, int id, int symbol) {
        B1Ref global = ctx.globalAt(frame.names[id], symbol);
        global.markReference(ctx);
        if (local != global) global.bind();
        return global;
    }
    /** catch (T $e): the CV takes the thrown zval. */
    public static Object caught(B1RequestContext ctx, Object local, B1Thrown thrown) {
        B1Object exception = thrown.exception;
        if (!(local instanceof B1Ref ref)) return exception;
        try { ref.set(ctx, exception); } finally { B1Object.release(ctx, exception); }
        return ref;
    }
    /** ZEND_ASSIGN used as a value, after the CV changed: drops the old CV; on failure the result is released. */
    public static Object assigned(B1RequestContext ctx, Object old, Object next, Object result) {
        try { drop(ctx, old, next); }
        catch (Throwable ex) {
            Throwable pending = ex;
            try { B1Object.release(ctx, result); } catch (Throwable more) { pending = B1Thrown.chain(ctx, more, pending); }
            B1Thrown.propagate(pending);
        }
        return result;
    }
    /** The NEW result leaves its construction region. */
    public static Object constructedFast(B1RequestContext ctx, B1Object self, Object ctorResult) {
        self.constructing = false;
        B1Object.release(ctx, ctorResult);
        return self;
    }

    /** Read of a fast CV: its value, or null with the undefined-variable warning. */
    // Hot helpers called from generated code stay under C2's MaxInlineSize (35 bytecode bytes) with their rare
    // cases outlined: a large generated method exhausts its inlining budget, and a helper that is not inlined
    // then costs a call and forces its operands into boxes.
    public static Object read(B1RequestContext ctx, Object local, B1Frame frame, int id) {
        if (local == UNDEF || local instanceof B1Ref) return readSlow(ctx, local, frame, id);
        return local;
    }
    private static Object readSlow(B1RequestContext ctx, Object local, B1Frame frame, int id) {
        if (local instanceof B1Ref ref) return ref.defined() ? ref.get() : undefined(ctx, frame, id);
        return undefined(ctx, frame, id);
    }
    private static Object undefined(B1RequestContext ctx, B1Frame frame, int id) {
        // A leaf call passes no frame: the diagnostic builds it, and it is then the current one.
        ctx.diagnostic("Warning", "Undefined variable $" + (frame != null ? frame : ctx.frame()).names[id]);
        return null;
    }
    /** isset/?? read of a fast CV. */
    public static Object quiet(Object local) {
        if (local instanceof B1Ref ref) return ref.get();
        return local == UNDEF ? null : local;
    }
    /** The new CV after writing a borrowed value. The old CV is dropped afterwards with {@link #drop}. */
    public static Object store(B1RequestContext ctx, Object local, Object value) {
        if (local instanceof B1Ref ref) return storeRef(ctx, ref, value);
        return B1Object.retain(value);
    }
    private static Object storeRef(B1RequestContext ctx, B1Ref ref, Object value) { ref.set(ctx, value); return ref; }
    /** Three fixed arguments as an argument array (a fast function entered inside a fiber runs on frames). */
    public static Object[] arguments(Object a, Object b, Object c) { return new Object[]{a, b, c}; }
    /** store() for ZEND_ASSIGN used as a value: the caller counted the result first; a failed write releases it. */
    public static Object assignStore(B1RequestContext ctx, Object local, Object value) {
        try { return store(ctx, local, value); }
        catch (Throwable ex) {
            Throwable pending = ex;
            try { B1Object.release(ctx, value); } catch (Throwable more) { pending = B1Thrown.chain(ctx, more, pending); }
            B1Thrown.propagate(pending);
            throw ex;
        }
    }
    /** Releases the previous CV once the new one is in place (a destructor can run). */
    public static void drop(B1RequestContext ctx, Object old, Object next) {
        if (old != next && old != UNDEF) B1Object.release(ctx, old);
    }
    /** The CV as a place: wraps a plain or undefined CV in its own binding, which the CV then holds. */
    public static B1Ref box(Object local) {
        if (local instanceof B1Ref ref) return ref;
        B1Ref ref = new B1Ref();
        if (local != UNDEF) ref.adopt(local);
        return ref;
    }
    /** FETCH_DIM_RW / ASSIGN_OP container: an undefined CV warns, then exists. */
    public static B1Ref boxRW(B1RequestContext ctx, B1Ref ref, B1Frame frame, int id) {
        if (!ref.defined()) ctx.diagnostic("Warning", "Undefined variable $" + frame.names[id]);
        return ref;
    }
    /** A by-reference send or capture of a boxed CV: the CV becomes a reference. */
    public static B1Ref reference(B1RequestContext ctx, B1Ref place) {
        place.markReference(ctx);
        return place;
    }
    /** Owned values (CVs and temporaries): release, or chain into the pending exception while unwinding. */
    public static void releaseOwned(B1RequestContext ctx, Object value) {
        if (value != null && value != UNDEF) B1Object.release(ctx, value);
    }
    public static Throwable unwindOwned(B1RequestContext ctx, Object value, Throwable pending) {
        if (value == null || value == UNDEF || value instanceof Long || value instanceof B1Bytes) return pending;
        return unwindSlow(ctx, value, pending);
    }
    private static Throwable unwindSlow(B1RequestContext ctx, Object value, Throwable pending) {
        try { B1Object.release(ctx, value); return pending; }
        catch (Throwable ex) { return B1Thrown.chain(ctx, ex, pending); }
    }
    /** FE_RESET_R into a fast iterator temporary. */
    public static Object startEachFast(B1RequestContext ctx, Object subject) {
        // An array iterator is the retained array itself; the generated code keeps the position.
        if (subject instanceof B1Array a) { a.retain(); return a; }
        if (subject instanceof B1Generator gen) return new B1Generator.Cursor(ctx,gen);
        if (subject instanceof B1Object obj) {
            if (ctx.instanceOf(obj,"traversable")) throw ctx.unsupported("foreach over Traversable objects");
            return obj.publicProperties(ctx);
        }
        ctx.diagnostic("Warning", "foreach() argument must be of type array|object, " + ctx.typeName(subject) + " given");
        return null;
    }
    /** FE_FETCH_R: the next (borrowed) value, or UNDEF at the end. */
    public static Object nextValue(B1RequestContext ctx, Object iterator) {
        if (iterator instanceof B1Array.Cursor cursor) {
            if (cursor.pos == cursor.array.size()) return UNDEF;
            return cursor.array.valueAt(cursor.pos++);
        }
        if (iterator instanceof B1Generator.Cursor it) return it.advance(ctx) ? it.generator.value() : UNDEF;
        return UNDEF;
    }
    public static Object currentKey(Object iterator) {
        if (iterator instanceof B1Array.Cursor cursor) return cursor.array.keyAt(cursor.pos - 1);
        return ((B1Generator.Cursor)iterator).generator.key();
    }
    /** The leave of a fast frame, first half: the unused result is released, then the frame is unlinked.
     * The generated code then releases its CVs and temporaries, and calls {@link #endLeave}. */
    public Throwable beginLeave(B1RequestContext ctx, Throwable pending, Object returned) {
        result = returned;
        if (discardReturn && pending == null) {
            result = null;
            try { B1Object.release(ctx, returned); } catch (Throwable ex) { pending = ex; }
        }
        ctx.pop(this);
        return pending;
    }
    /** Leave with the function's (up to three) CVs, in order. */
    public Object leave0(B1RequestContext ctx, Throwable pending, Object returned) {
        return endLeave(ctx, beginLeave(ctx, pending, returned));
    }
    public Object leave1(B1RequestContext ctx, Throwable pending, Object returned, Object a) {
        pending = beginLeave(ctx, pending, returned);
        return endLeave(ctx, unwindOwned(ctx, a, pending));
    }
    public Object leave2(B1RequestContext ctx, Throwable pending, Object returned, Object a, Object b) {
        pending = beginLeave(ctx, pending, returned);
        pending = unwindOwned(ctx, a, pending);
        return endLeave(ctx, unwindOwned(ctx, b, pending));
    }
    public Object leave3(B1RequestContext ctx, Throwable pending, Object returned, Object a, Object b, Object c) {
        pending = beginLeave(ctx, pending, returned);
        pending = unwindOwned(ctx, a, pending);
        pending = unwindOwned(ctx, b, pending);
        return endLeave(ctx, unwindOwned(ctx, c, pending));
    }
    public Object endLeave(B1RequestContext ctx, Throwable pending) {
        if (pending == null && extras == null) {
            Object returned = result;
            ctx.release(this);
            return returned;
        }
        return endLeaveSlow(ctx, pending);
    }
    private Object endLeaveSlow(B1RequestContext ctx, Throwable pending) {
        if (extras != null) for (Object extra : extras) {
            try { B1Object.release(ctx, extra); } catch (Throwable ex) { pending = B1Thrown.chain(ctx, ex, pending); }
        }
        Object returned = result;
        ctx.release(this);
        if (pending != null) {
            try { B1Object.release(ctx, returned); } catch (Throwable ex) { pending = B1Thrown.chain(ctx, ex, pending); }
        }
        B1Thrown.propagate(pending);
        return returned;
    }

    public B1Generator generator;

    void gcEdges(java.util.function.Consumer<Object> visit) {
        if (fast) {
            if (extras != null) for (var extra : extras) visit.accept(extra);
            return;
        }
        if (!top) for (var local : locals) visit.accept(local);
        for (var temp : temps) {
            if (temp instanceof B1Array.Cursor cursor) visit.accept(cursor.array);
            else if (temp instanceof B1Array.RefCursor cursor) visit.accept(cursor.source);
            else if (temp instanceof B1Generator.Cursor cursor) visit.accept(cursor.generator);
            else visit.accept(temp);
        }
        if (extras != null) for (var extra : extras) visit.accept(extra);
        if (dynamic != null) dynamic.values().forEach(visit);
        visit.accept(result);
        if (pending instanceof B1Thrown thrown) visit.accept(thrown.exception);
        if (suspended != null) for (var exit : suspended) {
            visit.accept(exit.result);
            if (exit.pending instanceof B1Thrown thrown) visit.accept(thrown.exception);
        }
    }

    public Throwable pending;
    public Object result;
    public int pc;
    public B1Closure nextClosure;
    public B1Closure closure;
    private java.util.ArrayDeque<Exit> suspended;
    private record Exit(int continuation, Throwable pending, Object result) {}
    public void suspend(int continuation) {
        if (suspended == null) suspended = new java.util.ArrayDeque<>();
        suspended.push(new Exit(continuation, pending, result));
        pending = null;
        result = null;
    }
    public int resume() {
        Exit exit = suspended.pop();
        pending = exit.pending;
        result = exit.result;
        return exit.continuation;
    }
    public void cancel(B1RequestContext ctx) {
        Exit old = suspended.pop();
        if (pending != null) pending = B1Thrown.chain(ctx, pending, old.pending);
        else if (old.pending instanceof B1Thrown thrown) B1Object.release(ctx, thrown.exception);
        try { B1Object.release(ctx, old.result); }
        catch (Throwable next) { B1Thrown.propagate(B1Thrown.chain(ctx, next, pending)); }
    }
    void attachEval(B1RequestContext ctx, B1Frame source) {
        for (int i = 0; i < names.length; i++) locals[i] = source.symbol(ctx,names[i]);
    }
    private B1Ref symbol(B1RequestContext ctx, String name) {
        for (int i = 0; i < names.length; i++) if (name.equals(names[i])) return slot(ctx,i);
        if (eval) return caller.symbol(ctx,name);
        if (top) return ctx.global(name);
        if (dynamic == null) dynamic = new java.util.LinkedHashMap<>();
        return dynamic.computeIfAbsent(name, ignored -> new B1Ref());
    }
    public Object quiet(B1RequestContext ctx, int id) { return slot(ctx, id).get(); }
    public B1Ref reference(B1RequestContext ctx, int id) {
        B1Ref ref = slot(ctx, id);
        ref.markReference(ctx);
        return ref;
    }
    public void bindCapture(B1RequestContext ctx, int id, int capture) {
        Object value = closure.capture(capture);
        if (value instanceof B1Ref ref) { ref.bind(); locals[id] = ref; }
        else write(ctx, id, value);
    }
    public void startEach(B1RequestContext ctx, int iterator, Object subject) {
        if (subject instanceof B1Generator gen) temps[iterator] = new B1Generator.Cursor(ctx,gen);
        else if (subject instanceof B1Array a) temps[iterator] = new B1Array.Cursor(a);
        else if (subject instanceof B1Object obj) {
            if (ctx.instanceOf(obj,"traversable")) throw ctx.unsupported("foreach over Traversable objects");
            B1Array properties = obj.publicProperties(ctx);
            temps[iterator] = new B1Array.Cursor(properties);
            properties.release(ctx);
        } else ctx.diagnostic("Warning", "foreach() argument must be of type array|object, " + ctx.typeName(subject) + " given");
    }
    /** FE_RESET_RW: the place becomes a reference held by the iterator; its array is separated per step. */
    public void startEachRef(B1RequestContext ctx, int iterator, B1Ref source) {
        source.requireReferenceable(ctx);
        Object subject = source.get();
        if (subject instanceof B1Array) {
            source.markReference(ctx);
            temps[iterator] = new B1Array.RefCursor(source);
        } else if (subject instanceof B1Object) throw ctx.unsupported("foreach by reference over objects");
        else ctx.diagnostic("Warning", "foreach() argument must be of type array|object, " + ctx.typeName(subject) + " given");
    }
    private boolean nextEachRef(B1RequestContext ctx, B1Array.RefCursor it, int key, int value) {
        if (!(it.source.get() instanceof B1Array)) return false;
        B1Array array = it.source.writable(ctx);
        if (it.pos > 0 && (it.pos > array.size() || !it.lastKey.equals(array.keyAt(it.pos - 1))))
            throw ctx.unsupported("structural changes behind a by-reference foreach");
        if (it.pos == array.size()) return false;
        Object k = array.keyAt(it.pos++);
        it.lastKey = k;
        bind(ctx, value, array.reference(ctx, k));
        if (key >= 0) write(ctx, key, k);
        return true;
    }
    public boolean nextEach(B1RequestContext ctx, int iterator, int key, int value) {
        if (temps[iterator] instanceof B1Array.RefCursor it) return nextEachRef(ctx, it, key, value);
        if (temps[iterator] instanceof B1Generator.Cursor it) {
            if (!it.advance(ctx)) return false;
            write(ctx,value,it.generator.value());
            if (key >= 0) write(ctx,key,it.generator.key());
            return true;
        }
        B1Array.Cursor cursor = (B1Array.Cursor) temps[iterator];
        if (cursor == null || cursor.pos == cursor.array.size()) return false;
        int pos = cursor.pos++;
        write(ctx, value, cursor.array.valueAt(pos));
        if (key >= 0) write(ctx, key, cursor.array.keyAt(pos));
        return true;
    }
    public B1Ref place(B1RequestContext ctx,int id) { return slot(ctx,id); }
    /** FETCH_DIM_RW / ASSIGN_OP container: an undefined CV warns, then exists. */
    public B1Ref placeRW(B1RequestContext ctx,int id) {
        B1Ref ref = slot(ctx,id);
        if (!ref.defined()) ctx.diagnostic("Warning", "Undefined variable $" + names[id]);
        return ref;
    }
    public void bind(B1RequestContext ctx,int id,B1Ref next) {
        next.markReference(ctx);
        next.bind();
        B1Ref old = replaceBinding(ctx,names[id],next);
        if (old != null) old.unbind(ctx);
    }

    private B1Ref replaceBinding(B1RequestContext ctx,String name,B1Ref next) {
        if (eval) return caller.replaceBinding(ctx,name,next);
        if (top) return ctx.bindGlobal(name,next);
        for (int i = 0; i < names.length; i++) if (name.equals(names[i])) {
            B1Ref old = locals[i]; locals[i] = next; return old;
        }
        if (dynamic == null) dynamic = new java.util.LinkedHashMap<>();
        return dynamic.put(name,next);
    }

    public void bindGlobal(B1RequestContext ctx,int id) {
        B1Ref global = ctx.global(names[id]);
        global.markReference(ctx);
        if (top && !eval) return;
        if (eval) { bind(ctx,id,global); return; }
        if (locals[id] == global) return;
        B1Ref old = locals[id]; global.bind(); locals[id] = global;
        if (old != null) old.unbind(ctx);
    }
    /** ZEND_BIND_STATIC: the CV becomes a reference to the request's static slot. True when it was just created. */
    public boolean bindStatic(B1RequestContext ctx, int id, B1Callable fn) {
        java.util.Map<String, B1Ref> map = ctx.staticVariables(fn);
        B1Ref slot = map.get(names[id]);
        boolean created = slot == null;
        if (created) map.put(names[id], slot = new B1Ref());
        slot.markReference(ctx);
        if (locals[id] == slot) return created;
        B1Ref old = locals[id]; slot.bind(); locals[id] = slot;
        if (old != null) old.unbind(ctx);
        return created;
    }
    public void unset(B1RequestContext ctx, int id) {
        if (eval) { caller.unsetSymbol(ctx,names[id]); locals[id] = caller.symbol(ctx,names[id]); return; }
        B1Ref old = top ? ctx.removeGlobal(names[id]) : locals[id];
        locals[id] = null;
        if (old != null) old.unbind(ctx);
    }
    private void unsetSymbol(B1RequestContext ctx,String name) {
        for (int i = 0; i < names.length; i++) if (names[i].equals(name)) { unset(ctx,i); return; }
        B1Ref old = top ? ctx.removeGlobal(name) : dynamic == null ? null : dynamic.remove(name);
        if (old != null) old.unbind(ctx);
    }

    public Object increment(B1RequestContext ctx, int id, boolean up, boolean prefix) {
        Object old = read(ctx, id);
        Object value = ctx.add(old, up ? 1L : -1L);
        write(ctx, id, value);
        return prefix ? value : old;
    }

    private B1Ref slot(B1RequestContext ctx, int id) {
        if (eval) return caller.symbol(ctx,names[id]);
        if (top) {
            refreshGlobalCache(ctx);
            if (locals[id] == null) locals[id] = ctx.global(names[id]);
            return locals[id];
        }
        if (locals[id] == null) locals[id] = new B1Ref();
        return locals[id];
    }
    private void refreshGlobalCache(B1RequestContext ctx) {
        if (globalVersion != ctx.globalVersion) {
            java.util.Arrays.fill(locals, null);
            globalVersion = ctx.globalVersion;
        }
    }
    public Object read(B1RequestContext ctx, int id) {
        B1Ref ref;
        if (top && !eval) {
            refreshGlobalCache(ctx);
            if (locals[id] == null) locals[id] = ctx.findGlobal(names[id]);
            ref = locals[id];
        } else ref = eval ? caller.symbol(ctx,names[id]) : locals[id];
        if (ref == null || !ref.defined()) { ctx.diagnostic("Warning", "Undefined variable $" + names[id]); return null; }
        return ref.get();
    }
    public void write(B1RequestContext ctx, int id, Object value) { slot(ctx, id).set(ctx, value); }
    /** ZEND_ASSIGN used as a value. The result is retained before the write, whose release of the old value can
     * run a destructor that overwrites the CV again. Owned by the caller. */
    public Object assign(B1RequestContext ctx, int id, Object value) {
        B1Object.retain(value);
        try {
            write(ctx, id, value);
        } catch (Throwable ex) {
            Throwable pending = ex;
            try { B1Object.release(ctx, value); } catch (Throwable next) { pending = B1Thrown.chain(ctx, next, pending); }
            B1Thrown.propagate(pending);
        }
        return value;
    }
    public Object send(B1RequestContext ctx, int id, B1Callable target, int index) {
        if (target != null && target.byRef(index)) {
            B1Ref slot = slot(ctx, id);
            slot.markReference(ctx);
            return slot;
        }
        return read(ctx, id);
    }

    String callerLocation() { return caller == null ? "" : " in " + caller.file + " on line " + caller.line; }

    public Object sendValue(B1RequestContext ctx, B1Callable target, int index, Object value, boolean callResult) {
        if (target != null && target.byRef(index)) {
            if (!callResult) throw ctx.error("Error", target.display + "(): Argument #" + (index + 1)
                    + " ($" + target.locals[index] + ") could not be passed by reference");
            ctx.diagnostic("Notice", "Only variables should be passed by reference");
        }
        return value;
    }

    /** Published only after the last send. Builtins borrow their slots through their invocation. */
    public void outgoing(B1Callable target, int index, int slot) {
        if (target == null || target.display == null) return;
        if (index >= outgoing.length) outgoing = java.util.Arrays.copyOf(outgoing, Math.max(index + 1,outgoing.length * 2));
        outgoing[index] = slot + 1;
    }

    private void takeSend(B1RequestContext ctx, int index) {
        if (caller != null && !caller.internalCall && index < caller.outgoing.length && caller.outgoing[index] != 0) {
            int slot = caller.outgoing[index] - 1;
            caller.outgoing[index] = 0;
            caller.releaseTemp(ctx, slot);
        }
    }

    /** Transfer every passed zval before RECV can raise. Traces include later parameters and extras. */
    public void bindArguments(B1RequestContext ctx, B1Callable fn, Object[] args, Object first, Object second) {
        boolean moved = ctx.moved == fn && !(first instanceof B1Callable.ShortNamed);
        if (ctx.moved == fn) ctx.moved = null;
        if (caller != null) {
            discardReturn = caller.discardedCall == fn;
            caller.discardedCall = null;
        }
        B1Callable.ShortNamed named = first instanceof B1Callable.ShortNamed values ? values : null;
        int count = named != null ? named.count() : args != null ? args.length
                : second != B1Callable.OMITTED ? 2 : first != B1Callable.OMITTED ? 1 : 0;
        passed = Math.min(count,fn.arity() - (fn.variadic ? 1 : 0));
        for (int i = 0; i < passed; i++) {
            Object value = named != null ? named.at(i) : args != null ? args[i] : i == 0 ? first : second;
            if (value == B1Callable.OMITTED) continue;
            if (fn.byRef(i) && value instanceof B1Ref ref) {
                if (!moved) ref.bind();
                locals[i] = ref;
            } else if (moved && value instanceof B1Ref ref) {
                write(ctx, i, ref.get());
                ref.unbind(ctx);
            } else {
                write(ctx, i, value);
                if (moved) B1Object.release(ctx, value);
            }
            if (!moved) takeSend(ctx, i);
        }
        if (count > passed) {
            extras = args == null ? new Object[count - passed] : java.util.Arrays.copyOfRange(args, passed, count);
            for (int i = 0; i < extras.length; i++) {
                if (args == null) extras[i] = passed + i == 0 ? first : second;
                if (!moved) {
                    B1Object.retain(extras[i]);
                    takeSend(ctx, passed + i);
                }
            }
        }
    }

    public void bindVariadic(B1RequestContext ctx,int id) {
        B1Array args = new B1Array(extras == null ? 0 : extras.length);
        if (extras != null) for (Object extra : extras) args.append(ctx,extra);
        write(ctx,id,args); args.release(ctx);
    }

    public void checkParameter(B1RequestContext ctx, int id, int required, int total,String type) {
        if (!supplied(id)) throw ctx.error("ArgumentCountError", "Too few arguments to function " + function
                + "(), " + passed + " passed" + callerLocation() + (total == required ? " and exactly " : " and at least ") + required + " expected");
        Object value = locals[id].get();
        if (type == null || type.equals("mixed")) return;
        Object coerced = scalar(ctx,value,type,caller != null && !caller.internalCall && caller.strictTypes);
        if (coerced == null) throw ctx.error("TypeError",function + "(): Argument #" + (id+1)
                + " ($" + names[id] + ") must be of type " + type + ", " + ctx.typeName(value) + " given"
                + (caller == null || caller.internalCall ? "" : ", called" + callerLocation()));
        if (coerced != value) write(ctx,id,coerced);
    }
    static Object scalar(B1RequestContext ctx,Object value,String type,boolean strict) {
        if (type.equals("array")) return value instanceof B1Array ? value : null;
        if (type.equals("callable")) return ctx.isCallable(value) ? value : null;
        if (type.equals("int")) return value instanceof Long ? value : strict ? null : ctx.integer(value);
        if (value instanceof B1Bytes || value instanceof String) return value;
        if (strict) return null;
        if (value instanceof Number || value instanceof Boolean || value instanceof B1Object obj && obj.method(ctx,"__tostring") != null)
            return B1Bytes.of(ctx.stringBytes(value));
        return null;
    }
    public Object checkReturn(B1RequestContext ctx,String type,Object value,boolean missing) {
        if (!missing) {
            if (type.equals("mixed")) return value;
            if (value == null && type.equals("?string")) return null;
            Object accepted = scalar(ctx,value,type,strictTypes);
            if (accepted != null) return accepted;
        }
        throw ctx.error("TypeError",function + "(): Return value must be of type " + type + ", "
                + (missing ? "none" : ctx.typeName(value)) + " returned");
    }

    public boolean supplied(int id) { return id < passed && locals[id] != null; }
    private int passed;
    boolean isTop() { return top && !eval; }
    public B1Array traceArguments(B1RequestContext ctx) {
        var args = new B1Array(passed + (extras == null ? 0 : extras.length));
        if (fast) for (int i = 0; i < passed; i++) args.append(ctx, mirrored(i));
        else for (int i = 0; i < passed; i++) args.append(ctx, locals[i] == null ? null : locals[i].get());
        if (extras != null) for (Object extra : extras) args.append(ctx, extra);
        return args;
    }

    public Object storeTemp(B1RequestContext ctx, int id, Object value, boolean owned) {
        if (value instanceof B1Ref ref) ref.bind();
        else if (!owned) B1Object.retain(value);
        temps[id] = value;
        return value;
    }

    public Object temp(int id) { return temps[id]; }
    public Object[] arguments(int[] slots) {
        var args = new Object[slots.length];
        for (int i = 0; i < args.length; i++) args[i] = temps[slots[i]];
        return args;
    }
    public void outgoingAll(B1Callable callee, int[] slots) {
        for (int i = 0; i < slots.length; i++) outgoing(callee,i,slots[i]);
    }
    public void releaseAll(B1RequestContext ctx, int[] slots) {
        for (int id : slots) releaseTemp(ctx,id);
    }
    public Throwable unwindAll(B1RequestContext ctx, int[] slots, Throwable pending) {
        for (int id : slots) pending = unwindTemp(ctx,id,pending);
        return pending;
    }

    public void releaseTemp(B1RequestContext ctx, int id) {
        Object old = temps[id];
        temps[id] = null;
        if (old instanceof B1Ref ref) ref.unbind(ctx);
        else B1Object.release(ctx, old);
    }
    public Throwable unwindTemp(B1RequestContext ctx, int id, Throwable pending) {
        try { releaseTemp(ctx, id); return pending; }
        catch (Throwable ex) { return B1Thrown.chain(ctx, ex, pending); }
    }

    /** Transfer the protected NEW result out of the construction region. */
    public Object constructed(B1RequestContext ctx, int id, B1Object self, Object ctorResult) {
        self.constructing = false;
        B1Object.release(ctx, ctorResult);
        temps[id] = null;
        return self;
    }

    public void takeException(B1RequestContext ctx, int id, B1Thrown thrown) {
        try { write(ctx, id, thrown.exception); }
        finally { B1Object.release(ctx, thrown.exception); }
    }

    public Object leave(B1RequestContext ctx, Throwable pending, Object returned) {
        if (discardReturn && pending == null) {
            Object unused = returned;
            returned = null;
            try { B1Object.release(ctx, unused); } catch (Throwable ex) { pending = ex; }
        }
        ctx.pop(this); // PHP unlinks the callee before destructing its CVs and extras.
        if (!top) for (int i = 0; i < locals.length; i++) {
            B1Ref slot = locals[i];
            locals[i] = null;
            if (slot == null) continue;
            try { slot.unbind(ctx); } catch (Throwable ex) { pending = B1Thrown.chain(ctx, ex, pending); }
        }
        if (dynamic != null) for (B1Ref slot : dynamic.values()) {
            try { slot.unbind(ctx); } catch (Throwable ex) { pending = B1Thrown.chain(ctx,ex,pending); }
        }
        if (extras != null) for (Object extra : extras) {
            try { B1Object.release(ctx, extra); } catch (Throwable ex) { pending = B1Thrown.chain(ctx, ex, pending); }
        }
        for (int i = 0; i < temps.length; i++) pending = unwindTemp(ctx, i, pending);
        if (pending != null) {
            try { B1Object.release(ctx, returned); } catch (Throwable ex) { pending = B1Thrown.chain(ctx, ex, pending); }
        }
        B1Thrown.propagate(pending);
        return returned;
    }
}
