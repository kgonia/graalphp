package pl.codeloom.php.b1.runtime;

import java.util.function.Consumer;

/** A suspended PHP frame. pc selects a generated empty-stack continuation. A negative
 * step result transfers control to the iterator, zero enters the single frame epilog. */
public final class B1Generator extends B1Object {
    private enum State { NEW, RUNNING, SUSPENDED, CLOSED }
    private State state = State.NEW;
    private final B1Callable body;
    private B1Object self;
    private B1Frame frame;
    private boolean first, returned, closing;
    private long largestKey = -1;
    private Object key, value, result, sent, delegate;
    private int position;
    private Throwable injection;

    private B1Generator(B1RequestContext ctx,B1RequestContext.ClassDef definition,B1Object self,B1Callable body,B1Frame frame) {
        super(ctx,ctx.acquireObjectId(),"generator",definition);
        this.self = (B1Object)retain(self);
        this.body = body;
        this.frame = frame;
        frame.generator = this;
        constructing = false;
    }

    public static B1Generator create(int pc,B1RequestContext ctx,B1Object self,B1Callable body,B1Frame frame) {
        if (pc == 0) { frame.leave(ctx,frame.pending,null); throw new IllegalStateException("Missing generator entry"); }
        frame.pc = pc;
        ctx.pop(frame);
        frame.caller = null;
        return ctx.track(new B1Generator(ctx,ctx.generatorClass,self,body,frame));
    }

    public int pause(B1RequestContext ctx,Object k,Object v,boolean delegating,int next) {
        if (closing) throw ctx.error("Error",delegating ? "Cannot use \"yield from\" in a force-closed generator"
                : "Cannot yield from finally in a force-closed generator");
        if (delegating) {
            if (!(v instanceof B1Array || v instanceof B1Generator))
                throw ctx.error("Error","Can use \"yield from\" only with arrays and Traversables");
            if (v instanceof B1Generator g && (g.state == State.RUNNING || g.leaf() == this))
                throw ctx.error("Error","Impossible to yield from the Generator being currently run");
            delegate = retain(v);
            position = 0;
        } else {
            if (k == B1Callable.OMITTED) k = ++largestKey;
            else if (k instanceof Long n) largestKey = Math.max(n,largestKey);
            publish(ctx,k,v);
        }
        frame.pc = next;
        return -1;
    }

    private void publish(B1RequestContext ctx,Object k,Object v) {
        Object oldKey = key, oldValue = value;
        key = retain(k); value = retain(v);
        release(ctx,oldValue); release(ctx,oldKey);
    }
    private void dropDelegate(B1RequestContext ctx) {
        Object old = delegate;
        delegate = null;
        release(ctx,old);
    }
    public Object receive() {
        var problem = injection;
        injection = null;
        B1Thrown.propagate(problem);
        Object received = sent;
        sent = null;
        return received;
    }

    private B1Generator leaf() {
        B1Generator current = this;
        while (current.delegate instanceof B1Generator inner) current = inner;
        return current;
    }
    Object value() { return state == State.CLOSED ? null : leaf().value; }
    Object key() { return state == State.CLOSED ? null : leaf().key; }

    private void initialize(B1RequestContext ctx) {
        if (state == State.NEW) { advance(ctx,null,null); first = true; }
    }
    private void rewind(B1RequestContext ctx) {
        initialize(ctx);
        if (!first) throw ctx.error("Exception","Cannot rewind a generator that was already run");
    }
    static final class Cursor {
        final B1Generator generator;
        boolean started;
        Cursor(B1RequestContext ctx,B1Generator gen) {
            if (gen.state == State.CLOSED) throw ctx.error("Exception","Cannot traverse an already closed generator");
            gen.rewind(ctx);
            generator = (B1Generator)retain(gen);
        }
        boolean advance(B1RequestContext ctx) {
            if (started) generator.advance(ctx,null,null);
            started = true;
            return generator.state == State.SUSPENDED;
        }
    }

    Object invoke(B1RequestContext ctx,String name,Object arg) {
        initialize(ctx);
        return switch (name) {
            case "rewind" -> { rewind(ctx); yield null; }
            case "current" -> retain(value());
            case "key" -> retain(key());
            case "valid" -> state != State.CLOSED;
            case "next" -> { advance(ctx,null,null); yield null; }
            case "send","throw" -> {
                advance(ctx,name.equals("send") ? arg : null,name.equals("throw") ? new B1Thrown(arg) : null);
                yield retain(value());
            }
            case "getreturn" -> {
                if (!returned) throw ctx.error("Exception","Cannot get return value of a generator that hasn't returned");
                yield retain(result);
            }
            default -> throw new IllegalArgumentException(name);
        };
    }

    private void advance(B1RequestContext ctx,Object input,Throwable thrown) {
        if (state == State.CLOSED) { B1Thrown.propagate(thrown); return; }
        if (state == State.RUNNING) throw ctx.error("Error","Cannot resume an already running generator");
        // Walk the delegation chain on the heap. A yield-from chain adds PHP frames
        // to the trace but does not consume a Java call frame per generator.
        var callers = new java.util.ArrayDeque<B1Generator>();
        B1Generator active = this;
        boolean initial = false;
        active.enter(ctx);
        try {
            for (;;) {
                boolean yielded = false;
                try {
                    if (!active.closing && active.delegate instanceof B1Generator child) {
                        if (child.state == State.RUNNING) throw ctx.error("Error","Cannot resume an already running generator");
                        if (initial && child.state == State.SUSPENDED) break;
                        if (child.state != State.CLOSED) {
                            callers.push(active);
                            active = child;
                            active.enter(ctx);
                            initial = false;
                            continue;
                        }
                        if (thrown == null && !child.returned) {
                            thrown = initial ? ctx.error("Error","Generator passed to yield from was aborted without proper return and is unable to continue")
                                    : ctx.error("ClosedGeneratorException","Generator yielded from aborted, no return value available");
                        }
                        if (thrown == null) input = child.result;
                    } else if (active.delegate instanceof B1Array array && thrown == null && active.position < array.size()) {
                        active.publish(ctx,array.keyAt(active.position),array.valueAt(active.position++));
                        break;
                    } else if (active.delegate != null) input = null;
                    if (thrown == null) active.sent = retain(input);
                    try { active.dropDelegate(ctx); }
                    catch (Throwable ex) {
                        Object dropped = active.sent;
                        active.sent = null;
                        thrown = B1Thrown.chain(ctx,ex,thrown);
                        try { release(ctx,dropped); } catch (Throwable rel) { thrown = B1Thrown.chain(ctx,rel,thrown); }
                    }
                    active.injection = thrown;
                    thrown = null;
                    input = null;
                    int next;
                    do {
                        next = active.body.resumeFrame(ctx,active.self,active.frame);
                        if (next >= 0) active.frame.pc = next;
                    } while (next > 0);
                    if (next == 0) active.finish(ctx,active.frame.pending,active.frame.result);
                    else if (active.delegate != null) { initial = true; continue; }
                    else yielded = true;
                } catch (Throwable ex) {
                    if (active.frame != null) {
                        try { active.finish(ctx,ex,active.frame.result); }
                        catch (Throwable unwound) { ex = unwound; }
                    }
                    thrown = ex;
                }
                if (yielded) break;
                if (callers.isEmpty()) { B1Thrown.propagate(thrown); return; }
                active = callers.pop();
                initial = false;
            }
        } finally {
            for (;;) {
                if (active.frame != null) {
                    ctx.pop(active.frame);
                    active.frame.caller = null;
                    active.state = State.SUSPENDED;
                }
                if (callers.isEmpty()) break;
                active = callers.pop();
            }
        }
    }

    private void enter(B1RequestContext ctx) {
        state = State.RUNNING;
        first = false;
        ctx.attach(frame);
    }

    private void finish(B1RequestContext ctx,Throwable failure,Object ret) {
        B1Frame done = frame;
        frame = null;
        state = State.CLOSED;
        done.generator = null;
        try {
            result = done.leave(ctx,failure,ret);
            returned = !closing;
        } finally {
            Object owner = self;
            self = null;
            release(ctx,owner);
        }
    }

    @Override void dump(B1RequestContext ctx,B1Dump dump,int depth) {
        var info = new B1Array(1);
        try { info.put(ctx,"function",B1Bytes.ofUtf8(body.display)); dump.object("Generator",id,info,depth); }
        finally { info.release(ctx); }
    }

    @Override boolean allowDynamicProperties() { return false; }

    @Override void destruct(B1RequestContext ctx) {
        super.destruct(ctx);
        closing = true;
        if (state == State.NEW) { ctx.attach(frame); finish(ctx,null,null); }
        else if (state == State.SUSPENDED) {
            try { advance(ctx,null,new B1ForceClose()); } catch (B1ForceClose expected) { /* finally ran */ }
        }
    }
    @Override void clear(B1RequestContext ctx) {
        Throwable pending = null;
        try { publish(ctx,null,null); } catch (Throwable ex) { pending = B1Thrown.chain(ctx,ex,pending); }
        try { dropDelegate(ctx); } catch (Throwable ex) { pending = B1Thrown.chain(ctx,ex,pending); }
        Object oldSent = sent;
        sent = null;
        try { release(ctx,oldSent); } catch (Throwable ex) { pending = B1Thrown.chain(ctx,ex,pending); }
        Object old = result;
        result = null;
        try { release(ctx,old); } catch (Throwable ex) { pending = B1Thrown.chain(ctx,ex,pending); }
        try { super.clear(ctx); } catch (Throwable ex) { pending = B1Thrown.chain(ctx,ex,pending); }
        B1Thrown.propagate(pending);
    }
    @Override public void gcEdges(Consumer<Object> visit) {
        super.gcEdges(visit);
        if (frame != null && state != State.RUNNING) frame.gcEdges(visit);
        visit.accept(self); visit.accept(key); visit.accept(value); visit.accept(result); visit.accept(delegate); visit.accept(sent);
    }
}
