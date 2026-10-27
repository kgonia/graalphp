package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.util.Arrays;
import java.util.List;

/**
 * The Closure class's methods (Zend/zend_closures.c): bind, bindTo, call and fromCallable. A closure value is a
 * PhpClosure; its function is the closure's own body, or for a "fake" closure (made from a function or method by
 * fromCallable or `f(...)`) that function or method, whose scope can't be changed.
 */
final class ClosureMethods {
    private ClosureMethods() {
    }

    static List<PhpFunction> methods(Registry registry) {
        return List.of(
                registry.method("bind", true, a -> {
                    PhpClosure closure = closureArgument(a, 0, "Closure::bind");
                    return bind(closure, thisArgument(a, 1, "Closure::bind"), Args.get(a, 2), "Closure::bind");
                }),
                registry.method("bindTo", false, a -> bind((PhpClosure) a[PhpFunction.ARG_THIS],
                        thisArgument(a, 0, "Closure::bindTo"), Args.get(a, 1), "Closure::bindTo")),
                registry.method("call", false, a -> call((PhpClosure) a[PhpFunction.ARG_THIS], a)),
                registry.method("fromCallable", true, ClosureMethods::fromCallable));
    }

    private static PhpClosure closureArgument(Object[] a, int index, String function) {
        Object value = Args.required(a, index, function);
        if (value instanceof PhpClosure closure) {
            return closure;
        }
        throw new PhpError("TypeError", function + "(): Argument #" + (index + 1) + " ($closure) must be of type "
                + "Closure, " + Convert.debugType(value) + " given");
    }

    /** ?object $newThis. */
    private static PhpObject thisArgument(Object[] a, int index, String function) {
        Object value = Args.required(a, index, function);
        if (value == PhpNull.NULL) {
            return null;
        }
        if (value instanceof PhpObject object) {
            return object;
        }
        throw new PhpError("TypeError", function + "(): Argument #" + (index + 1) + " ($newThis) must be of type "
                + "?object, " + Convert.debugType(value) + " given");
    }

    /** Whether the closure is a fake one: made from a named function or method, not a closure body. */
    private static boolean isFake(PhpClosure closure) {
        return closure.getMagicName() != null || !closure.getFunction().getName().startsWith("{closure");
    }

    /** The function's class scope: a closure's scope, or a fake closure's method's class (null for a function). */
    private static PhpClass scopeOf(PhpClosure closure) {
        return closure.getScope();
    }

    /**
     * do_closure_bind: the new scope is an object's class, a class by name, the closure's own for "static", or
     * none for null; a class that doesn't exist warns and gives null.
     */
    @TruffleBoundary
    private static Object bind(PhpClosure closure, PhpObject newThis, Object scopeValue, String function) {
        PhpContext context = PhpContext.get(null);
        PhpClass scope;
        if (scopeValue == null) {
            scope = scopeOf(closure);
        } else if (scopeValue == PhpNull.NULL) {
            scope = null;
        } else if (scopeValue instanceof PhpObject object) {
            scope = object.getPhpClass();
        } else if (scopeValue instanceof TruffleString || scopeValue instanceof Long || scopeValue instanceof Double
                || scopeValue instanceof Boolean) {
            String name = Str.java(Convert.toStr(scopeValue));
            if (name.equals("static")) {
                scope = scopeOf(closure);
            } else {
                scope = context.findClass(Str.lower(name.startsWith("\\") ? name.substring(1) : name));
                if (scope == null) {
                    warn(context, "Class \"" + name + "\" not found");
                    return PhpNull.NULL;
                }
            }
        } else {
            throw new PhpError("TypeError", function + "(): Argument #" + (function.equals("Closure::bind") ? 3 : 2)
                    + " ($newScope) must be of type object|string|null, " + Convert.debugType(scopeValue) + " given");
        }
        if (!validBinding(context, closure, newThis, scope)) {
            return PhpNull.NULL;
        }
        return new PhpClosure(closure.getFunction(), newThis, scope, captures(closure), closure.getMagicName());
    }

    /** The closure's captured values, shared with the new one (a by-value array counts it as another holder). */
    private static Object[] captures(PhpClosure closure) {
        int count = closure.getCaptureCount();
        Object[] captured = new Object[count];
        for (int i = 0; i < count; i++) {
            Object value = closure.getCaptured(i);
            if (value instanceof PhpRef ref) {
                ref.bind();
            } else if (value instanceof pl.codeloom.php.runtime.PhpArray array) {
                array.addHolder();
            }
            captured[i] = value;
        }
        return captured;
    }

    /** zend_valid_closure_binding. */
    private static boolean validBinding(PhpContext context, PhpClosure closure, PhpObject newThis, PhpClass scope) {
        PhpFunction function = closure.getFunction();
        boolean fake = isFake(closure);
        PhpClass functionScope = scopeOf(closure);
        if (newThis != null) {
            if (function.isStatic()) {
                warn(context, "Cannot bind an instance to a static closure, this will be an error in PHP 9");
                return false;
            }
            if (fake && functionScope != null && !newThis.getPhpClass().isSubclassOf(functionScope.getLowerName())) {
                warn(context, "Cannot bind method " + functionScope.getName() + "::" + function.getName()
                        + "() to object of class " + newThis.getPhpClass().getName()
                        + ", this will be an error in PHP 9");
                return false;
            }
        } else if (fake && functionScope != null && !function.isStatic()) {
            warn(context, "Cannot unbind $this of method, this will be an error in PHP 9");
            return false;
        } else if (!fake && closure.getBoundThis() != null && function.usesThis()) {
            warn(context, "Cannot unbind $this of closure using $this, this will be an error in PHP 9");
            return false;
        }
        if (scope != null && scope != functionScope && scope.getDeclaration() instanceof String) {
            warn(context, "Cannot bind closure to scope of internal class " + scope.getName()
                    + ", this will be an error in PHP 9");
            return false;
        }
        if (fake && scope != functionScope) {
            warn(context, functionScope == null
                    ? "Cannot rebind scope of closure created from function, this will be an error in PHP 9"
                    : "Cannot rebind scope of closure created from method, this will be an error in PHP 9");
            return false;
        }
        return true;
    }

    /** Closure::call($newThis, ...$args): the closure run once with $newThis and its class as the scope. */
    @TruffleBoundary
    private static Object call(PhpClosure closure, Object[] a) {
        Object value = Args.required(a, 0, "Closure::call");
        if (!(value instanceof PhpObject newThis)) {
            throw new PhpError("TypeError", "Closure::call(): Argument #1 ($newThis) must be of type object, "
                    + Convert.debugType(value) + " given");
        }
        PhpContext context = PhpContext.get(null);
        if (!validBinding(context, closure, newThis, newThis.getPhpClass())) {
            return PhpNull.NULL;
        }
        PhpClosure bound = new PhpClosure(closure.getFunction(), newThis, newThis.getPhpClass(), captures(closure),
                closure.getMagicName());
        int count = Args.count(a);
        Object[] values = count > 1 ? Arrays.copyOfRange(a, PhpFunction.ARG_FIRST + 1, PhpFunction.ARG_FIRST + count)
                : new Object[0];
        return PhpRef.deref(Callables.resolve(context, bound).call(values));
    }

    /** Closure::fromCallable(): a closure is returned as it is; anything else callable becomes a fake closure. */
    @TruffleBoundary
    private static Object fromCallable(Object[] a) {
        Object callable = Args.required(a, 0, "Closure::fromCallable");
        if (callable instanceof PhpClosure) {
            return callable;
        }
        PhpContext context = PhpContext.get(null);
        Callables.Target target = Callables.resolve(context, callable);
        if (target == null) {
            throw new PhpError("TypeError", "Failed to create closure from callable: "
                    + Callables.invalidReason(context, callable));
        }
        return new PhpClosure(target.function(), target.thisObject() instanceof PhpObject self ? self : null,
                target.staticClass(), new Object[0], target.magicName());
    }

    private static void warn(PhpContext context, String message) {
        ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of(message), SourceLocation.current());
    }
}
