package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.nodes.RootNode;

import java.util.Locale;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;

/** Resolves PHP callables: 'name', 'Cls::method', [$obj, 'method'], ['Cls', 'method'], closures, __invoke. */
public final class Callables {
    private Callables() {
    }

    /**
     * A resolved callable: the function and what to bind for the call. magicName is set when the function is
     * __call or __callStatic standing in for a missing method: the call then passes the name and the arguments.
     */
    public record Target(PhpFunction function, Object thisObject, PhpClass staticClass, Object closure,
                         TruffleString magicName) {
        public Target(PhpFunction function, Object thisObject, PhpClass staticClass, Object closure) {
            this(function, thisObject, staticClass, closure, null);
        }

        public Object[] frameArguments(Object[] values) {
            if (magicName != null) {
                values = magicValues(magicName, values);
            }
            Object[] arguments = new Object[PhpFunction.ARG_FIRST + values.length];
            arguments[PhpFunction.ARG_THIS] = thisObject;
            arguments[PhpFunction.ARG_STATIC] = staticClass;
            arguments[PhpFunction.ARG_CLOSURE] = closure;
            System.arraycopy(values, 0, arguments, PhpFunction.ARG_FIRST, values.length);
            return arguments;
        }
    }

    /** The target of a callable given to a built-in (zend_is_callable), or null if the value isn't callable. */
    @TruffleBoundary
    public static Target resolve(PhpContext context, Object callable) {
        return resolve(context, callable, false);
    }

    /**
     * direct: the target of a call `$f()` (zend_init_dynamic_call_*), which takes class and method names
     * literally ("self", "parent::m" mean nothing there) and binds no $this; else of a callable given to a
     * built-in, which resolves them against the calling code and takes its $this.
     */
    private static Target resolve(PhpContext context, Object callable, boolean direct) {
        if (callable instanceof PhpClosure closure) {
            PhpClass scope = closure.getBoundThis() != null ? closure.getBoundThis().getPhpClass() : closure.getScope();
            return new Target(closure.getFunction(), closure.getBoundThis(), scope, closure);
        }
        if (callable instanceof TruffleString name) {
            String text = Str.java(name);
            int separator = text.indexOf("::");
            if (separator > 0) {
                return method(namedClass(context, text.substring(0, separator), direct), null,
                        text.substring(separator + 2), direct);
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
                return method(object.getPhpClass(), object, Str.java(methodName), direct);
            }
            if (first instanceof TruffleString className) {
                return method(namedClass(context, Str.java(className), direct), null, Str.java(methodName), direct);
            }
            return null;
        }
        if (callable instanceof PhpObject object) {
            PhpFunction invoke = object.getPhpClass().findMethod("__invoke");
            return invoke == null ? null : new Target(invoke, object, object.getPhpClass(), null);
        }
        return null;
    }

    /** __call / __callStatic's arguments: the method name as it was called, and the arguments as an array. */
    @TruffleBoundary
    public static Object[] magicValues(TruffleString name, Object[] values) {
        PhpArray arguments = new PhpArray();
        for (Object value : values) {
            arguments.append(PhpRef.deref(value));
        }
        return new Object[] {name, arguments};
    }

    /** The lowercase name a class-name callable (`['Cls', 'm']`) is looked up by. */
    @TruffleBoundary
    public static String className(TruffleString name) {
        return stripNamespace(Str.java(name)).toLowerCase();
    }

    private static Target method(PhpClass phpClass, PhpObject object, String methodName, boolean direct) {
        if (phpClass == null) {
            return null;
        }
        if (!direct && methodName.startsWith("parent::")) {
            phpClass = phpClass.getParent();
            methodName = methodName.substring("parent::".length());
            if (phpClass == null) {
                return null;
            }
        }
        PhpFunction method = phpClass.findMethod(methodName.toLowerCase(Locale.ROOT));
        if (!direct && object == null && (method == null || !method.isStatic())) {
            // a class callable reached from code running on an instance of the class binds that $this
            // (zend_is_callable_check_func), for a non-static method or __call
            object = callerThisOf(phpClass);
        }
        if (method == null) {
            // a missing method: __call on an object, __callStatic on a class (zend_is_callable_check_func)
            if (object != null) {
                PhpFunction magicCall = phpClass.findMethod("__call");
                return magicCall == null ? null
                        : new Target(magicCall, object, object.getPhpClass(), null, Str.of(methodName));
            }
            if (direct && callsNonStaticMagic(phpClass)) {
                return null;
            }
            PhpFunction magicStatic = phpClass.findMethod("__callstatic");
            return magicStatic == null ? null
                    : new Target(magicStatic, null, phpClass, null, Str.of(methodName));
        }
        if (!method.isStatic() && object == null) {
            // "non-static method Cls::m() cannot be called statically"
            return null;
        }
        PhpClass staticClass = object != null ? object.getPhpClass() : phpClass;
        return new Target(method, method.isStatic() ? null : object, staticClass, null);
    }

    /**
     * The class a callable names: "self", "parent" and "static" (deprecated in callables since PHP 8.2, still
     * honoured) are the calling code's declaring class, its parent, and the called class.
     */
    private static PhpClass namedClass(PhpContext context, String name, boolean direct) {
        String lower = stripNamespace(name).toLowerCase(Locale.ROOT);
        if (direct) {
            return context.findClass(lower);
        }
        switch (lower) {
            case "self", "parent": {
                Caller caller = caller();
                String scope = caller == null ? null : caller.scopeName();
                PhpClass self = scope == null ? null : context.findClass(scope);
                return self == null || lower.equals("self") ? self : self.getParent();
            }
            case "static": {
                Caller caller = caller();
                return caller != null && caller.argument(PhpFunction.ARG_STATIC) instanceof PhpClass called
                        ? called : null;
            }
            default:
                return context.findClass(lower);
        }
    }

    /** The PHP code calling: its frame's arguments and the lowercase name of its declaring class (or null). */
    private record Caller(Object[] arguments, String scopeName) {
        Object argument(int index) {
            return index < arguments.length ? arguments[index] : null;
        }
    }

    /**
     * The innermost frame that isn't a built-in's (call_user_func and the like run between the PHP caller and
     * the callable), or null.
     */
    @TruffleBoundary
    private static Caller caller() {
        return Truffle.getRuntime().iterateFrames(frameInstance -> {
            RootNode root = frameInstance.getCallTarget() instanceof RootCallTarget target ? target.getRootNode()
                    : null;
            if (root instanceof pl.codeloom.php.nodes.call.BuiltinRootNode) {
                return null;
            }
            String scope = root instanceof pl.codeloom.php.nodes.call.FunctionRootNode function
                    ? function.getScopeName()
                    : root instanceof pl.codeloom.php.nodes.stmt.GeneratorNodes.BodyRootNode body
                    ? body.getScopeName() : null;
            return new Caller(frameInstance.getFrame(FrameInstance.FrameAccess.READ_ONLY).getArguments(), scope);
        });
    }

    /** $this of the PHP code calling when it is an instance of the class, or null. */
    private static PhpObject callerThisOf(PhpClass phpClass) {
        Caller caller = caller();
        return caller != null && caller.argument(PhpFunction.ARG_THIS) instanceof PhpObject self
                && self.getPhpClass().isSubclassOf(phpClass.getLowerName()) ? self : null;
    }

    /**
     * A direct static call of a missing method from code running on an instance of the class: php-src's
     * zend_std_get_static_method picks __call there, which is non-static, so the call is an error.
     */
    private static boolean callsNonStaticMagic(PhpClass phpClass) {
        return phpClass.findMethod("__call") != null && callerThisOf(phpClass) != null;
    }

    private static String stripNamespace(String name) {
        return name.startsWith("\\") ? name.substring(1) : name;
    }

    /**
     * A built-in's callable parameter, which php-src checks before the built-in runs (Z_PARAM_FUNC): a value
     * that isn't callable is a TypeError naming it, "usort(): Argument #2 ($callback) must be a valid callback".
     */
    public record Parameter(String function, int position, boolean nullable) {
    }

    /** The target of a direct call `$f()`, or the Error php-src raises for it. */
    @TruffleBoundary
    public static Target resolveCall(PhpContext context, Object callable) {
        Target target = resolve(context, callable, true);
        if (target == null) {
            throw new PhpError(directCallError(context, callable));
        }
        return target;
    }

    /**
     * The target of a callable given to a built-in, or the TypeError naming its parameter. A null parameter is a
     * callable the runtime calls (an autoloader, a shutdown function), checked when it was registered.
     */
    @TruffleBoundary
    public static Target resolveOrThrow(PhpContext context, Object callable, Parameter parameter) {
        Target target = resolve(context, callable, false);
        if (target == null) {
            if (parameter == null) {
                throw new PhpError("Error: " + invalidReason(context, callable));
            }
            throw new PhpError("TypeError: " + parameter.function() + "(): Argument #" + parameter.position()
                    + " ($callback) must be a valid callback" + (parameter.nullable() ? " or null" : "") + ", "
                    + invalidReason(context, callable));
        }
        return target;
    }

    /** zend_is_callable_error's reason a value isn't callable. */
    private static String invalidReason(PhpContext context, Object callable) {
        if (callable instanceof TruffleString name) {
            String text = Str.java(name);
            int separator = text.indexOf("::");
            if (separator <= 0) {
                return "function \"" + text + "\" not found or invalid function name";
            }
            return methodReason(context, text.substring(0, separator), text.substring(separator + 2));
        }
        if (callable instanceof PhpArray array) {
            if (array.size() != 2) {
                return "array callback must have exactly two members";
            }
            Object first = array.get(0L);
            Object second = array.get(1L);
            if (!(first instanceof PhpObject) && !(first instanceof TruffleString)) {
                return "first array member is not a valid class name or object";
            }
            if (!(second instanceof TruffleString method)) {
                return "second array member is not a valid method";
            }
            if (first instanceof PhpObject object) {
                return "class " + object.getPhpClass().getName() + " does not have a method \"" + Str.java(method)
                        + "\"";
            }
            return methodReason(context, Str.java((TruffleString) first), Str.java(method));
        }
        return "no array or string given";
    }

    private static String methodReason(PhpContext context, String className, String methodName) {
        PhpClass phpClass = namedClass(context, className, false);
        if (phpClass == null) {
            return "class \"" + className + "\" not found";
        }
        PhpFunction method = phpClass.findMethod(methodName.toLowerCase(Locale.ROOT));
        if (method != null && !method.isStatic()) {
            return "non-static method " + phpClass.getName() + "::" + method.getName() + "() cannot be called "
                    + "statically";
        }
        return "class " + phpClass.getName() + " does not have a method \"" + methodName + "\"";
    }

    /** The Error a direct call `$f()` of a value that isn't callable raises (zend_init_dynamic_call_*). */
    private static String directCallError(PhpContext context, Object callable) {
        if (callable instanceof TruffleString name) {
            String text = Str.java(name);
            int separator = text.indexOf("::");
            if (separator <= 0) {
                return "Call to undefined function " + text + "()";
            }
            return methodError(context, text.substring(0, separator), text.substring(separator + 2));
        }
        if (callable instanceof PhpArray array) {
            if (array.size() != 2) {
                return "Array callback must have exactly two elements";
            }
            Object first = array.get(0L);
            Object second = array.get(1L);
            if (!(first instanceof PhpObject) && !(first instanceof TruffleString)) {
                return "First array member is not a valid class name or object";
            }
            if (!(second instanceof TruffleString method)) {
                return "Second array member is not a valid method";
            }
            if (first instanceof PhpObject object) {
                return "Call to undefined method " + object.getPhpClass().getName() + "::" + Str.java(method) + "()";
            }
            return methodError(context, Str.java((TruffleString) first), Str.java(method));
        }
        if (callable instanceof PhpObject object) {
            return "Object of type " + object.getPhpClass().getName() + " is not callable";
        }
        return "Value of type " + Convert.debugType(callable) + " is not callable";
    }

    private static String methodError(PhpContext context, String className, String methodName) {
        PhpClass phpClass = namedClass(context, className, true);
        if (phpClass == null) {
            return "Class \"" + className + "\" not found";
        }
        PhpFunction method = phpClass.findMethod(methodName.toLowerCase(Locale.ROOT));
        if (method != null && !method.isStatic() || method == null && callsNonStaticMagic(phpClass)) {
            return "Non-static method " + phpClass.getName() + "::" + (method != null ? method.getName() : methodName)
                    + "() cannot be called statically";
        }
        return "Call to undefined method " + phpClass.getName() + "::" + methodName + "()";
    }

    /**
     * The last callable resolved by {@link #call}, one per context: built-ins that call back in a loop (array_map,
     * usort, preg_replace_callback) pass the same callable value every time. An array callable is re-checked
     * element by element, since an unshared array can change in place.
     */
    public static final class LastCall {
        Object callable;
        Object first;
        Object second;
        Target target;
    }

    /** Calls a callable from runtime code (array_map, usort, preg_replace_callback...): no inline caching. */
    @TruffleBoundary
    public static Object call(PhpContext context, Object callable, Object... values) {
        Target target = target(context, callable, null);
        return target.function().getCallTarget().call(target.frameArguments(values));
    }

    /**
     * Checks a built-in's callable parameter on entry, as php-src's parameter parsing does: before any work, and
     * even when the callback would never be called (an empty array). The later {@link #call}s reuse the target.
     */
    @TruffleBoundary
    public static void check(PhpContext context, Object callable, Parameter parameter) {
        target(context, callable, parameter);
    }

    private static Target target(PhpContext context, Object callable, Parameter parameter) {
        LastCall last = context.lastCall();
        if (callable == last.callable && (!(callable instanceof PhpArray array)
                || array.get(0L) == last.first && array.get(1L) == last.second)) {
            return last.target;
        }
        Target target = resolveOrThrow(context, callable, parameter);
        // a class callable bound to the caller's $this depends on the caller: not kept
        boolean boundByCaller = target.thisObject() != null && !(callable instanceof PhpClosure)
                && !(callable instanceof PhpArray array && array.get(0L) instanceof PhpObject);
        last.callable = boundByCaller ? null : callable;
        last.target = target;
        if (callable instanceof PhpArray array) {
            last.first = array.get(0L);
            last.second = array.get(1L);
        }
        return target;
    }
}
