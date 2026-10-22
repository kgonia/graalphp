package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;

/** Resolves PHP callables: 'name', 'Cls::method', [$obj, 'method'], ['Cls', 'method'], closures, __invoke. */
public final class Callables {
    private Callables() {
    }

    /** A resolved callable: the function and what to bind for the call. */
    public record Target(PhpFunction function, Object thisObject, PhpClass staticClass, Object closure) {
        public Object[] frameArguments(Object[] values) {
            Object[] arguments = new Object[PhpFunction.ARG_FIRST + values.length];
            arguments[PhpFunction.ARG_THIS] = thisObject;
            arguments[PhpFunction.ARG_STATIC] = staticClass;
            arguments[PhpFunction.ARG_CLOSURE] = closure;
            System.arraycopy(values, 0, arguments, PhpFunction.ARG_FIRST, values.length);
            return arguments;
        }
    }

    /** The target, or null if the value isn't callable. */
    @TruffleBoundary
    public static Target resolve(PhpContext context, Object callable) {
        if (callable instanceof PhpClosure closure) {
            PhpClass scope = closure.getBoundThis() != null ? closure.getBoundThis().getPhpClass() : closure.getScope();
            return new Target(closure.getFunction(), closure.getBoundThis(), scope, closure);
        }
        if (callable instanceof TruffleString name) {
            String text = Str.java(name);
            int separator = text.indexOf("::");
            if (separator > 0) {
                return method(context.findClass(text.substring(0, separator).toLowerCase()), null,
                        text.substring(separator + 2));
            }
            PhpFunction function = context.findFunction(stripNamespace(text).toLowerCase());
            return function == null ? null : new Target(function, null, null, null);
        }
        if (callable instanceof PhpArray array && array.size() == 2) {
            Object first = array.get(0L);
            Object second = array.get(1L);
            if (!(second instanceof TruffleString methodName)) {
                return null;
            }
            if (first instanceof PhpObject object) {
                Target target = method(object.getPhpClass(), object, Str.java(methodName));
                if (target == null && object.getPhpClass().findMethod("__call") != null) {
                    return null;
                }
                return target;
            }
            if (first instanceof TruffleString className) {
                return method(context.findClass(stripNamespace(Str.java(className)).toLowerCase()), null,
                        Str.java(methodName));
            }
            return null;
        }
        if (callable instanceof PhpObject object) {
            return method(object.getPhpClass(), object, "__invoke");
        }
        return null;
    }

    private static Target method(PhpClass phpClass, PhpObject object, String methodName) {
        if (phpClass == null) {
            return null;
        }
        if (methodName.startsWith("parent::")) {
            phpClass = phpClass.getParent();
            methodName = methodName.substring("parent::".length());
            if (phpClass == null) {
                return null;
            }
        }
        PhpFunction method = phpClass.findMethod(methodName.toLowerCase());
        if (method == null) {
            return null;
        }
        PhpClass staticClass = object != null ? object.getPhpClass() : phpClass;
        return new Target(method, method.isStatic() ? null : object, staticClass, null);
    }

    private static String stripNamespace(String name) {
        return name.startsWith("\\") ? name.substring(1) : name;
    }

    @TruffleBoundary
    public static Target resolveOrThrow(PhpContext context, Object callable) {
        Target target = resolve(context, callable);
        if (target == null) {
            throw new PhpError("TypeError: Argument must be a valid callback, got " + describe(callable));
        }
        return target;
    }

    /** Calls a callable from runtime code (array_map, usort, preg_replace_callback...): no inline caching. */
    @TruffleBoundary
    public static Object call(PhpContext context, Object callable, Object... values) {
        Target target = resolveOrThrow(context, callable);
        return target.function().getCallTarget().call(target.frameArguments(values));
    }

    private static String describe(Object callable) {
        if (callable instanceof TruffleString s) {
            return "function \"" + Str.java(s) + "\" not found or invalid function name";
        }
        return Convert.typeName(callable);
    }
}
