package pl.codeloom.php.b1.runtime;

/** Named vtable for cross-hidden-class calls. The receiver is separate from positional PHP parameters. */
public abstract class B1Callable {
    public static final Object OMITTED = new Object();
    private final boolean[] byRef;
    public boolean strictTypes, isStatic, evalBody, variadic;
    public int methodBudget, requiredParams;
    /** 1 public, 2 protected, 4 private (ZEND_ACC_* bits). */
    public int visibility = 1;
    /** The unit was lowered at the B1 language level; its evals are too. */
    public boolean extendedLanguage;
    public String declaredFile, scope;
    public int declaredLine;
    public B1Callable[] siblings;
    public java.util.Map<Integer,B1RequestContext.ClassDef> statementClasses = java.util.Map.of();
    public String display;
    /** Generated callables take ownership of arguments a moving caller passes (see B1RequestContext.moved). */
    public boolean consumes;

    @HotPath
    public final Object callSlice(B1RequestContext ctx, B1Object self, B1Array args, int offset, int length) {
        return switch (length) {
            case 0 -> invoke0(ctx, self);
            case 1 -> invoke1(ctx, self, forwarded(ctx,args,offset,0));
            case 2 -> invoke2(ctx, self, forwarded(ctx,args,offset,0), forwarded(ctx,args,offset+1,1));
            case 3 -> invoke3(ctx, self, forwarded(ctx,args,offset,0), forwarded(ctx,args,offset+1,1), forwarded(ctx,args,offset+2,2));
            default -> invoke(ctx, self, arguments(ctx,args, offset, length));
        };
    }
    Object named(B1RequestContext ctx, B1Object self, B1Array args, int start, int count) {
        if (count > 2) return namedMany(ctx,self,args,start,count);
        int first = position(ctx,args.keyAt(start),0);
        Object a = forwarded(ctx,args,start,first);
        int second = -1;
        Object b = OMITTED;
        if (count == 2) {
            Object key = args.keyAt(start+1);
            if (!(key instanceof B1Bytes) && args.keyAt(start) instanceof B1Bytes) throw positionalAfterNamed(ctx);
            second = position(ctx,key,1);
            if (second == first) throw duplicate(ctx,key);
            b = forwarded(ctx,args,start+1,second);
        }
        if (Math.max(first,second) > 1) return sparse(ctx,self,new ShortNamed(first,a,second,b));
        if (count == 1 && first == 0) return invoke1(ctx,self,a);
        return first == 0 ? invoke2(ctx,self,a,b) : invoke2(ctx,self,b,a);
    }

    record ShortNamed(int first,Object a,int second,Object b) {
        int count() { return Math.max(first,second)+1; }
        Object at(int i) { return i == first ? a : i == second ? b : OMITTED; }
    }
    Object sparse(B1RequestContext ctx,B1Object self,ShortNamed args) { return invoke1(ctx,self,args); }

    private int position(B1RequestContext ctx,Object key,int index) {
        if (!(key instanceof B1Bytes string)) return index;
        String name = string.toString();
        for (int i = 0; i < arity(); i++) if (locals[i].equals(name)) return i;
        throw ctx.error("Error","Unknown named parameter $" + name);
    }
    private B1Thrown positionalAfterNamed(B1RequestContext ctx) {
        return ctx.error("Error","Cannot use positional argument after named argument");
    }
    private B1Thrown duplicate(B1RequestContext ctx,Object key) {
        return ctx.error("Error","Named parameter $" + key + " overwrites previous argument");
    }
    private Object namedMany(B1RequestContext ctx,B1Object self,B1Array args,int start,int count) {
        Object[] values = new Object[Math.max(arity(),count)];
        java.util.Arrays.fill(values,OMITTED);
        int highest = 0;
        boolean named = false;
        for (int i = 0; i < count; i++) {
            Object key = args.keyAt(start+i);
            if (key instanceof B1Bytes) named = true;
            else if (named) throw positionalAfterNamed(ctx);
            int pos = position(ctx,key,i);
            if (values[pos] != OMITTED) throw duplicate(ctx,key);
            values[pos] = forwarded(ctx,args,start+i,pos);
            highest = Math.max(highest,pos+1);
        }
        return invoke(ctx,self,java.util.Arrays.copyOf(values,highest));
    }
    final Object forwarded(B1RequestContext ctx,B1Array args,int offset,int param) {
        if (byRef(param)) {
            if (args.rawAt(offset) instanceof B1Ref ref && ref.reference()) return ref;
            ctx.diagnostic("Warning",display + "(): Argument #" + (param+1) + " ($" + locals[param]
                    + ") must be passed by reference, value given");
        }
        return args.valueAt(offset);
    }
    private Object[] arguments(B1RequestContext ctx,B1Array args, int offset, int length) {
        Object[] result = new Object[length];
        for (int i = 0; i < length; i++) result[i] = forwarded(ctx,args,offset+i,i);
        return result;
    }
    final String[] locals;
    protected B1Callable(boolean[] byRef, String[] locals) {
        this.byRef = byRef.clone();
        this.locals = locals.clone();
    }
    final int arity() { return byRef.length; }
    /** A positional argument a leaf call can take as it is (no skipped parameter, no named arguments). */
    public static boolean positional(Object argument) { return argument != OMITTED && !(argument instanceof ShortNamed); }
    public final boolean byRef(int index) { return index < byRef.length && byRef[index]; }

    public Object invokeFrameless1(B1RequestContext ctx,B1Object self,Object a) { return invoke1(ctx,self,a); }
    public Object invokeFrameless2(B1RequestContext ctx,B1Object self,Object a,Object b) { return invoke2(ctx,self,a,b); }

    public int resumeFrame(B1RequestContext ctx,B1Object self,B1Frame frame) { throw new IllegalStateException("Not a generator"); }

    public abstract Object invoke0(B1RequestContext ctx, B1Object self);
    public abstract Object invoke1(B1RequestContext ctx, B1Object self, Object a);
    public abstract Object invoke2(B1RequestContext ctx, B1Object self, Object a, Object b);
    public abstract Object invoke(B1RequestContext ctx, B1Object self, Object[] args);
    /** Three positional arguments. Generated fast functions take them without an argument array. */
    public Object invoke3(B1RequestContext ctx, B1Object self, Object a, Object b, Object c) {
        return invoke(ctx, self, new Object[]{a, b, c});
    }
}
