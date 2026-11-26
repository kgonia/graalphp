package pl.codeloom.php.b1.runtime;

import java.util.function.Consumer;

public final class B1Fiber extends B1Object {
    private enum Status { INIT, RUNNING, SUSPENDED, DEAD }
    private Status state = Status.INIT;
    private final B1RequestContext context;
    private final B1FiberGate toFiber = new B1FiberGate(), toCaller = new B1FiberGate();
    private Object callback, value, result;
    private Object[] arguments;
    private Object firstArg, secondArg;
    private int arity;
    private Throwable error;
    private boolean threw, closing;
    private B1Frame parked;

    B1Fiber(B1RequestContext ctx,B1RequestContext.ClassDef cls) {
        super(ctx,ctx.acquireObjectId(),"fiber",cls);
        context = ctx;
    }
    @Override boolean allowDynamicProperties() { return false; }
    B1Closure entryClosure() { return callback instanceof B1Closure c ? c : null; }

    Object invoke(B1RequestContext ctx,String method,Object[] args,int count,Object arg,Object second) {
        return switch (method) {
            case "__construct" -> {
                if (callback != null || state != Status.INIT) throw FiberError.state(ctx,"Cannot call constructor twice");
                if (!ctx.isCallable(arg)) throw ctx.error("TypeError","Fiber::__construct(): Argument #1 ($callback) must be a valid callback");
                callback = retain(arg);
                yield null;
            }
            case "start" -> {
                if (state != Status.INIT) throw FiberError.state(ctx,"Cannot start a fiber that has already been started");
                arguments = args; arity = count; firstArg = arg; secondArg = second;
                yield transfer(ctx.acquireFiberThread());
            }
            case "resume","throw" -> {
                if (state != Status.SUSPENDED) throw FiberError.state(ctx,"Cannot resume a fiber that is not suspended");
                if (method.equals("throw")) error = new B1Thrown(arg); else value = retain(arg);
                yield transfer(null);
            }
            case "isstarted" -> state != Status.INIT;
            case "issuspended" -> state == Status.SUSPENDED;
            case "isrunning" -> state == Status.RUNNING;
            case "isterminated" -> state == Status.DEAD;
            case "getreturn" -> {
                if (state != Status.DEAD || threw) throw FiberError.state(ctx,"Cannot get fiber return value: " +
                        (threw ? "The fiber threw an exception" : state == Status.INIT ? "The fiber has not been started" : "The fiber has not returned"));
                yield retain(result);
            }
            default -> throw new IllegalArgumentException(method);
        };
    }

    /** Enter a different PHP stack. Only the thread with the turn may touch the context. */
    private Object transfer(B1FiberThread start) {
        B1Fiber previous = context.currentFiber;
        B1Frame caller = context.swapFrame(parked);
        parked = null;
        context.currentFiber = this;
        state = Status.RUNNING;
        if (start == null) toFiber.open(); else start.assign(this);
        toCaller.await();
        parked = context.swapFrame(caller);
        context.currentFiber = previous;
        return receive();
    }
    private Object receive() {
        Throwable problem = error;
        error = null;
        B1Thrown.propagate(problem);
        Object payload = value;
        value = null;
        return payload;
    }
    public static Object suspend(B1RequestContext ctx,Object value) {
        B1Fiber fiber = ctx.currentFiber;
        if (fiber == null) throw FiberError.state(ctx,"Cannot suspend outside of a fiber");
        if (fiber.closing) throw FiberError.state(ctx,"Cannot suspend in a force-closed fiber");
        fiber.value = retain(value);
        fiber.state = Status.SUSPENDED;
        fiber.toCaller.open();
        fiber.toFiber.await();
        return fiber.receive();
    }
    void runOn(B1FiberThread worker) {
        try {
            Object[] args = arguments;
            arguments = null;
            Object a = firstArg, b = secondArg;
            firstArg = secondArg = null;
            var fn = context.callable(callback);
            var receiver = context.receiver(callback);
            result = args != null ? fn.invoke(context,receiver,args) : switch (arity) {
                case 0 -> fn.invoke0(context,receiver);
                case 1 -> fn.invoke1(context,receiver,a);
                default -> fn.invoke2(context,receiver,a,b);
            };
        } catch (B1ForceClose expected) {
            // A suspended fiber was destroyed.
        } catch (Throwable ex) { threw = true; error = ex; }
        finally {
            Object held = callback;
            callback = null;
            try { release(context,held); }
            catch (Throwable ex) { threw = true; error = B1Thrown.chain(context,ex,error); }
            state = Status.DEAD;
            context.recycle(worker);
            toCaller.open();
        }
    }

    @Override void destruct(B1RequestContext ctx) {
        super.destruct(ctx);
        if (state == Status.SUSPENDED) {
            closing = true;
            error = new B1ForceClose();
            release(ctx,transfer(null));
        }
    }
    @Override void clear(B1RequestContext ctx) {
        Object cb = callback, ret = result;
        callback = result = null;
        try { release(ctx,cb); } finally { release(ctx,ret); }
        super.clear(ctx);
    }
    @Override public void gcEdges(Consumer<Object> visit) {
        super.gcEdges(visit);
        visit.accept(callback); visit.accept(result); visit.accept(value);
        for (B1Frame f = parked; f != null; f = f.caller) f.gcEdges(visit);
    }
}
