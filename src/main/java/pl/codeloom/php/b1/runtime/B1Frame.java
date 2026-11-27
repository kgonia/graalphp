package pl.codeloom.php.b1.runtime;

/** Logical PHP frame. Generated code names CVs and live ranges by stable index. */
public final class B1Frame {
    B1Frame caller;
    public final String file, function;
    public int line;
    boolean staticCall;
    String scope;
    private final String[] names;
    private final B1Ref[] locals;
    private final Object[] temps;
    private int[] outgoing = new int[0];
    private final boolean top, eval;
    boolean strictTypes, internalCall;
    private long globalVersion = -1;
    private java.util.LinkedHashMap<String, B1Ref> dynamic;
    private Object[] extras;
    private B1Callable discardedCall;
    private boolean discardReturn;

    public void discardCallResult(B1Callable target) { discardedCall = target; }

    B1Frame(B1Frame caller, String file, String function, String[] names, int ntemps, boolean top, boolean eval) {
        this.caller = caller;
        this.file = file;
        this.function = function;
        this.names = names;
        this.top = top;
        this.eval = eval;
        locals = new B1Ref[names.length];
        temps = new Object[ntemps];
    }

    public B1Generator generator;

    void gcEdges(java.util.function.Consumer<Object> visit) {
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
        if (caller != null) {
            discardReturn = caller.discardedCall == fn;
            caller.discardedCall = null;
        }
        B1Callable.ShortNamed named = first instanceof B1Callable.ShortNamed values ? values : null;
        int count = named != null ? named.count() : args != null ? args.length : first == B1Callable.OMITTED ? 0 : second == B1Callable.OMITTED ? 1 : 2;
        passed = Math.min(count,fn.arity() - (fn.variadic ? 1 : 0));
        for (int i = 0; i < passed; i++) {
            Object value = named != null ? named.at(i) : args != null ? args[i] : i == 0 ? first : second;
            if (value == B1Callable.OMITTED) continue;
            if (fn.byRef(i) && value instanceof B1Ref ref) {
                ref.bind();
                locals[i] = ref;
            } else write(ctx, i, value);
            takeSend(ctx, i);
        }
        if (count > passed) {
            extras = args == null ? new Object[count - passed] : java.util.Arrays.copyOfRange(args, passed, count);
            for (int i = 0; i < extras.length; i++) {
                if (args == null) extras[i] = passed + i == 0 ? first : second;
                B1Object.retain(extras[i]);
                takeSend(ctx, passed + i);
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
        for (int i = 0; i < passed; i++) args.append(ctx, locals[i] == null ? null : locals[i].get());
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
