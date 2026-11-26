package pl.codeloom.php.b1.runtime;

import java.util.HashMap;
import java.util.List;

/** Request-local internal method tables for the two resumable PHP object types. */
final class B1CoroutineMethod extends B1Callable {
    private final String owner, method;
    private final int min, max;

    static B1RequestContext.ClassDef definition(String owner,String... names) {
        var methods = new HashMap<String,B1Callable>();
        for (String name : names) methods.put(name,new B1CoroutineMethod(owner,name));
        return new B1RequestContext.ClassDef(owner,List.of(),methods);
    }
    private B1CoroutineMethod(String owner,String method) {
        super(new boolean[0],new String[0]);
        this.owner = owner;
        this.method = method;
        min = switch (method) { case "__construct","throw","send" -> 1; default -> 0; };
        max = switch (method) { case "start" -> Integer.MAX_VALUE; case "resume","suspend" -> 1; default -> min; };
        isStatic = method.equals("suspend") || method.equals("getcurrent");
    }
    @Override public Object invoke0(B1RequestContext ctx,B1Object self) { return dispatch(ctx,self,null,0,null,null); }
    @Override public Object invoke1(B1RequestContext ctx,B1Object self,Object a) { return dispatch(ctx,self,null,1,a,null); }
    @Override public Object invoke2(B1RequestContext ctx,B1Object self,Object a,Object b) { return dispatch(ctx,self,null,2,a,b); }
    @Override public Object invoke(B1RequestContext ctx,B1Object self,Object[] args) {
        return dispatch(ctx,self,args,args.length,args.length == 0 ? null : args[0],null);
    }
    private Object dispatch(B1RequestContext ctx,B1Object self,Object[] args,int count,Object arg,Object second) {
        if (count < min || count > max) throw ctx.error("ArgumentCountError",owner + "::" + method
                + "() expects " + (min == max ? "exactly " + min : "at most " + max) + " argument" + (max == 1 ? "" : "s")
                + ", " + count + " given");
        if (method.equals("throw") && !(arg instanceof B1Object o && B1Thrown.throwable(o.type)))
            throw ctx.error("TypeError",owner + "::throw(): Argument #1 ($exception) must be of type Throwable, " + ctx.argumentType(arg) + " given");
        if (self instanceof B1Generator gen) return gen.invoke(ctx,method,arg);
        if (method.equals("suspend")) return B1Fiber.suspend(ctx,arg);
        if (method.equals("getcurrent")) return B1Object.retain(ctx.currentFiber);
        return ((B1Fiber)self).invoke(ctx,method,args,count,arg,second);
    }
}
