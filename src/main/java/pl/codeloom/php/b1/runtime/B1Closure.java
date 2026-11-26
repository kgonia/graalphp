package pl.codeloom.php.b1.runtime;

/** Captures hold their zvals independently of the frame that created the closure. */
public final class B1Closure {
    final B1Callable body;
    final long id;
    private final Object[] captures;
    private int owners = 1;
    public B1Closure(B1RequestContext ctx, B1Callable body, int count) {
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
    Object capture(int index) { return captures[index]; }
    void retain() { owners++; }
    void release(B1RequestContext ctx) {
        if (--owners != 0) return;
        Throwable pending = null;
        for (Object value : captures) {
            try {
                if (value instanceof B1Ref ref) ref.unbind(ctx);
                else B1Object.release(ctx, value);
            } catch (Throwable ex) { pending = B1Thrown.chain(ctx, ex, pending); }
        }
        ctx.releaseObjectId(id);
        B1Thrown.propagate(pending);
    }
}
