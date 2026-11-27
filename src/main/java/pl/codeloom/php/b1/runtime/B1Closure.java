package pl.codeloom.php.b1.runtime;

/** Captures hold their zvals independently of the frame that created the closure. */
public final class B1Closure implements B1GcNode {
    final B1Callable body;
    final long id;
    private final Object[] captures;
    B1Object self;
    boolean inGcRoots;
    int gcSlot = -1;
    private int owners = 1;
    public B1Closure(B1RequestContext ctx, B1Callable body, int count) { this(ctx,body,count,null); }
    public B1Closure(B1RequestContext ctx, B1Callable body, int count, B1Object self) {
        this.self = body.isStatic ? null : (B1Object)B1Object.retain(self);
        this.body = body;
        id = ctx.acquireObjectId();
        captures = new Object[count];
    }
    public B1Closure capture(int i, Object value) {
        if (value instanceof B1Ref ref) ref.bind();
        else B1Object.retain(value);
        captures[i] = value;
        return this;
    }
    B1Array debugInfo(B1RequestContext ctx) {
        var info = new B1Array(6);
        info.put(ctx,"name",body.display.substring(body.display.indexOf("{closure:")));
        info.put(ctx,"file",body.declaredFile);
        info.put(ctx,"line",(long)body.declaredLine);
        if (captures.length > 0) {
            var values = new B1Array(captures.length);
            for (int i = 0; i < captures.length; i++) values.put(ctx,body.locals[body.arity()+i],captures[i]);
            info.put(ctx,"static",values);
            values.release(ctx);
        }
        if (self != null) info.put(ctx,"this",self);
        if (body.arity() > 0) {
            var params = new B1Array(body.arity());
            for (int i = 0; i < body.arity(); i++) params.put(ctx,(body.byRef(i) ? "&$" : "$") + body.locals[i],
                    i < body.requiredParams ? "<required>" : "<optional>");
            info.put(ctx,"parameter",params);
            params.release(ctx);
        }
        return info;
    }
    Object capture(int index) { return captures[index]; }
    public int gcCount() { return owners; }
    public void gcEdges(java.util.function.Consumer<Object> visit) {
        visit.accept(self);
        for (var capture : captures) visit.accept(capture);
    }
    void retain() { owners++; }
    void release(B1RequestContext ctx) {
        int remaining = --owners;
        if (inGcRoots != (remaining > 0)) ctx.cycles.dropped(ctx,this,remaining);
        if (remaining != 0) return;
        try { clear(ctx); } finally { ctx.releaseObjectId(id); }
    }
    void clear(B1RequestContext ctx) {
        Throwable pending = null;
        for (int i = 0; i < captures.length; i++) {
            Object old = captures[i];
            captures[i] = null;
            try { B1Object.release(ctx,old); }
            catch (Throwable ex) { pending = B1Thrown.chain(ctx,ex,pending); }
        }
        var receiver = self;
        self = null;
        try { B1Object.release(ctx,receiver); } catch (Throwable ex) { pending = B1Thrown.chain(ctx,ex,pending); }
        B1Thrown.propagate(pending);
    }
}
