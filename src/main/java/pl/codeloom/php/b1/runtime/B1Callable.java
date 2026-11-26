package pl.codeloom.php.b1.runtime;

/** Named vtable for cross-hidden-class calls. The receiver is separate from positional PHP parameters. */
public abstract class B1Callable {
    private final boolean[] byRef;
    public String display;
    final String[] locals;
    protected B1Callable(boolean[] byRef, String[] locals) {
        this.byRef = byRef.clone();
        this.locals = locals.clone();
    }
    final int arity() { return byRef.length; }
    public final boolean byRef(int index) { return index < byRef.length && byRef[index]; }

    public Object invoke0(B1RequestContext ctx, B1Object self) { return invoke(ctx, self, new Object[0]); }
    public Object invoke1(B1RequestContext ctx, B1Object self, Object a) { return invoke(ctx, self, new Object[]{a}); }
    public Object invoke2(B1RequestContext ctx, B1Object self, Object a, Object b) {
        return invoke(ctx, self, new Object[]{a, b});
    }
    public abstract Object invoke(B1RequestContext ctx, B1Object self, Object[] args);
}
