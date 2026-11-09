package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.bytecode.NamedExtras;
import pl.codeloom.php.bytecode.SpreadArguments;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.Phases;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFiber;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;

import java.util.Arrays;
import java.util.List;

/** The Fiber class (Zend/zend_fibers.c) over {@link PhpFiber}. */
final class Fibers {
    private static final Callables.Parameter CONSTRUCT = new Callables.Parameter("Fiber::__construct", 1, false);

    private Fibers() {
    }

    /** Fiber's methods, and FiberError's constructor. */
    record Methods(List<PhpFunction> fiber, PhpFunction errorConstructor) {
    }

    static Methods methods(Registry registry) {
        // zend_fibers.c's FiberError::__construct: only the engine makes one
        PhpFunction errorConstructor = registry.method("FiberError", "__construct", false, a -> {
            throw new PhpError("Error", "The \"" + ((PhpObject) a[PhpFunction.ARG_THIS]).getPhpClass().getName()
                    + "\" class is reserved for internal use and cannot be manually instantiated");
        });
        return new Methods(List.of(
                registry.method("Fiber", "__construct", false, Fibers::construct),
                registry.method("Fiber", "start", false, Fibers::start),
                registry.method("Fiber", "resume", false, a -> fiber(a).resume(Args.get(a, 0, PhpNull.NULL))),
                registry.method("Fiber", "throw", false, Fibers::throwInto),
                registry.method("Fiber", "getReturn", false, a -> fiber(a).getReturn()),
                registry.method("Fiber", "isStarted", false,
                        a -> fiber(a).status() != PhpFiber.Status.INIT),
                registry.method("Fiber", "isSuspended", false,
                        a -> fiber(a).status() == PhpFiber.Status.SUSPENDED),
                registry.method("Fiber", "isRunning", false,
                        a -> fiber(a).status() == PhpFiber.Status.RUNNING),
                registry.method("Fiber", "isTerminated", false,
                        a -> fiber(a).status() == PhpFiber.Status.DEAD),
                registry.method("Fiber", "suspend", true,
                        a -> PhpFiber.suspend(PhpContext.get(null), Args.get(a, 0, PhpNull.NULL))),
                registry.method("Fiber", "getCurrent", true, a -> {
                    PhpFiber current = PhpContext.get(null).currentFiber();
                    return current == null ? PhpNull.NULL : current.object();
                })), errorConstructor);
    }

    static void declare(PhpContext context, Methods methods) {
        PhpClass phpClass = new PhpClass("Fiber", null, false, false, context.getLanguage().rootShape(), "Fiber");
        // zend_register_fiber_ce: final, not cloneable, not serializable
        phpClass.setHandlers(new PhpClass.ObjectHandlers(null, null, true, false, true, false));
        for (PhpFunction method : methods.fiber()) {
            phpClass.addMethod(method);
        }
        context.declareClass(phpClass);
    }

    /** FiberError, once Error is declared. */
    static void declareError(PhpContext context, Methods methods) {
        PhpClass phpClass = new PhpClass("FiberError", context.getClass("Error"), false, false,
                context.getLanguage().rootShape(), "FiberError");
        phpClass.addMethod(methods.errorConstructor());
        context.declareClass(phpClass);
    }

    /** The fiber of a method's $this; a Fiber whose constructor didn't run (a subclass's) has none. */
    private static PhpFiber fiber(Object[] a) {
        PhpObject self = (PhpObject) a[PhpFunction.ARG_THIS];
        if (self.getInternal() instanceof PhpFiber fiber) {
            return fiber;
        }
        throw new PhpError("Error", "Fiber has not been constructed");
    }

    @TruffleBoundary
    private static Object construct(Object[] a) {
        PhpObject self = (PhpObject) a[PhpFunction.ARG_THIS];
        PhpContext context = PhpContext.get(null);
        Object callable = Args.get(a, 0);
        Callables.Target target = Callables.parameter(context, callable, CONSTRUCT);
        if (self.getInternal() instanceof PhpFiber) {
            throw new PhpError("FiberError", "Cannot call constructor twice");
        }
        self.setInternal(new PhpFiber(context, self, callable, target, threadState()));
        return PhpNull.NULL;
    }

    /** Fiber::start(...$args): its named arguments go on to the function (Z_PARAM_VARIADIC_WITH_NAMED). */
    @TruffleBoundary
    private static Object start(Object[] a) {
        PhpFiber fiber = fiber(a);
        Callables.Target target = fiber.target();
        PhpArray named = NamedExtras.forwarded(a, 0);
        Object[] frame = named != null ? SpreadArguments.sendArray(target, named, null)
                : target.frameArguments(Arrays.copyOfRange(a, PhpFunction.ARG_FIRST, a.length));
        return fiber.start(frame);
    }

    @TruffleBoundary
    private static Object throwInto(Object[] a) {
        Object exception = Args.get(a, 0);
        if (!(exception instanceof PhpObject object) || !object.getPhpClass().isSubclassOf("throwable")) {
            throw Args.typeError("Fiber::throw", 1, "exception", "Throwable", exception);
        }
        return fiber(a).throwInto(object);
    }

    /**
     * What a fiber's thread takes over from the thread starting it: the request's phase counters, the builder pool,
     * the worker's persistent curl shares. One thread of a context runs at a time, so they share them safely.
     */
    private static Runnable threadState() {
        Phases phases = Phases.ofThread();
        Object pool = ByteBuilder.threadPool();
        Object shares = CurlNative.available() ? CurlFunctions.persistentShares() : null;
        return () -> {
            Phases.share(phases);
            ByteBuilder.sharePool(pool);
            if (shares != null) {
                CurlFunctions.sharePersistentShares(shares);
            }
        };
    }
}
