package pl.codeloom.php.b1.runtime;

/** Logical PHP frame. Generated code names CVs and live ranges by stable index. */
public final class B1Frame {
    final B1Frame caller;
    public final String file, function;
    public int line;
    private final String[] names;
    private final B1Ref[] locals;
    private final Object[] temps;
    private int[] outgoing = new int[0];
    private final boolean top;
    private Object[] extras;
    private B1Callable discardedCall;
    private boolean discardReturn;

    public void discardCallResult(B1Callable target) { discardedCall = target; }

    B1Frame(B1Frame caller, String file, String function, String[] names, int ntemps, boolean top) {
        this.caller = caller;
        this.file = file;
        this.function = function;
        this.names = names;
        this.top = top;
        locals = new B1Ref[names.length];
        temps = new Object[ntemps];
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
        if (index >= outgoing.length) outgoing = java.util.Arrays.copyOf(outgoing, index + 1);
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
        int count = args == null ? fn.arity() : args.length;
        passed = Math.min(count, fn.arity());
        for (int i = 0; i < passed; i++) {
            Object value = args != null ? args[i] : i == 0 ? first : second;
            if (fn.byRef(i) && value instanceof B1Ref ref) {
                ref.bind();
                locals[i] = ref;
            } else write(ctx, i, value);
            takeSend(ctx, i);
        }
        if (count > passed) {
            extras = java.util.Arrays.copyOfRange(args, passed, count);
            for (int i = 0; i < extras.length; i++) {
                B1Object.retain(extras[i]);
                takeSend(ctx, passed + i);
            }
        }
    }

    public void checkParameter(B1RequestContext ctx, int id, int required, boolean integer) {
        if (id >= passed) throw ctx.error("ArgumentCountError", "Too few arguments to function " + function
                + "(), " + passed + " passed" + callerLocation() + " and exactly " + required + " expected");
        Object value = locals[id].get();
        if (integer && !(value instanceof Long)) {
            Long number = ctx.integer(value);
            if (number == null) throw ctx.error("TypeError", function + "(): Argument #" + (id + 1)
                    + " ($" + names[id] + ") must be of type int, " + ctx.typeName(value) + " given"
                    + (caller == null ? "" : ", called" + callerLocation()));
            write(ctx, id, number);
        }
    }

    private int passed;
    boolean isTop() { return top; }
    java.util.Map<Long, Object> traceArguments() {
        var args = new java.util.LinkedHashMap<Long, Object>();
        for (int i = 0; i < passed; i++) args.put((long)i, locals[i] == null ? null : locals[i].get());
        if (extras != null) for (Object extra : extras) args.put((long)args.size(), extra);
        return args;
    }

    public Object storeTemp(B1RequestContext ctx, int id, Object value, boolean owned) {
        if (value instanceof B1Ref ref) ref.bind();
        else if (!owned) B1Object.retain(value);
        temps[id] = value;
        return value;
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
