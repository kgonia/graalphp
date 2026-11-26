package pl.codeloom.php.b1.runtime;

/** Logical PHP frame. Generated code names CVs and live ranges by stable index. */
public final class B1Frame {
    final B1Frame caller;
    public final String file, function;
    public int line;
    boolean staticCall;
    String scope;
    private final String[] names;
    private final B1Ref[] locals;
    private final Object[] temps;
    private int[] outgoing = new int[0];
    private final boolean top, eval;
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
        ref.markReference();
        return ref;
    }
    public void bindCapture(B1RequestContext ctx, int id, int capture) {
        Object value = closure.capture(capture);
        if (value instanceof B1Ref ref) { ref.bind(); locals[id] = ref; }
        else write(ctx, id, value);
    }
    public void startEach(B1RequestContext ctx, int iterator, Object subject) {
        if (subject instanceof B1Array a) temps[iterator] = new B1Array.Cursor(a);
        else if (subject instanceof B1Object obj) {
            B1Array properties = obj.publicProperties(ctx);
            temps[iterator] = new B1Array.Cursor(properties);
            properties.release(ctx);
        } else ctx.diagnostic("Warning", "foreach() argument must be of type array|object, " + ctx.typeName(subject) + " given");
    }
    public boolean nextEach(B1RequestContext ctx, int iterator, int key, int value) {
        B1Array.Cursor cursor = (B1Array.Cursor) temps[iterator];
        if (cursor == null || cursor.pos == cursor.array.size()) return false;
        int pos = cursor.pos++;
        write(ctx, value, cursor.array.valueAt(pos));
        if (key >= 0) write(ctx, key, cursor.array.keyAt(pos));
        return true;
    }
    public B1Ref place(B1RequestContext ctx,int id) { return slot(ctx,id); }
    public void bindGlobal(B1RequestContext ctx,int id) {
        B1Ref global = ctx.global(names[id]);
        if (locals[id] == global) return;
        B1Ref old = locals[id]; global.bind(); locals[id] = global;
        if (old != null) old.unbind(ctx);
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
        if (locals[id] == null) locals[id] = top ? ctx.global(names[id]) : new B1Ref();
        return locals[id];
    }
    public Object read(B1RequestContext ctx, int id) {
        B1Ref ref = locals[id];
        if (ref == null && top) ref = ctx.findGlobal(names[id]);
        if (ref == null || !ref.defined()) { ctx.diagnostic("Warning", "Undefined variable $" + names[id]); return null; }
        return ref.get();
    }
    public void write(B1RequestContext ctx, int id, Object value) { slot(ctx, id).set(ctx, value); }
    public Object send(B1RequestContext ctx, int id, B1Callable target, int index) {
        if (target != null && target.byRef(index)) {
            B1Ref slot = slot(ctx, id);
            slot.markReference();
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
        if (caller != null && index < caller.outgoing.length && caller.outgoing[index] != 0) {
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
        if (type == null) return;
        Object coerced = scalar(ctx,value,type);
        if (coerced == null) throw ctx.error("TypeError",function + "(): Argument #" + (id+1)
                + " ($" + names[id] + ") must be of type " + type + ", " + ctx.typeName(value) + " given"
                + (caller == null ? "" : ", called" + callerLocation()));
        if (coerced != value) write(ctx,id,coerced);
    }
    private Object scalar(B1RequestContext ctx,Object value,String type) {
        if (type.equals("int")) return value instanceof Long ? value : ctx.integer(value);
        if (value instanceof B1Bytes || value instanceof String) return value;
        if (value instanceof Number || value instanceof Boolean || value instanceof B1Object obj && obj.method(ctx,"__tostring") != null)
            return B1Bytes.of(ctx.stringBytes(value));
        return null;
    }
    public Object checkReturn(B1RequestContext ctx,String type,Object value,boolean missing) {
        if (!missing) {
            if (value == null && type.equals("?string")) return null;
            Object accepted = scalar(ctx,value,type);
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
