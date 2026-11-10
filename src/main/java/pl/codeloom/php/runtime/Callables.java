package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.bytecode.BindingPlan;

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

        /** Calls the target from runtime code (a built-in calling back): no inline caching. */
        @TruffleBoundary
        public Object call(Object... values) {
            return Refcount.pending(PhpRef.deref(function.getCallTarget().call(frameArguments(values))));
        }

        /**
         * call for a result runtime code only tests, converts or discards: its own count goes once returned
         * (Refcount.done). The value stays usable for the conversion.
         */
        public Object callScalar(Object... values) {
            return Refcount.done(call(values));
        }

        public Object[] frameArguments(Object[] values) {
            if (magicName != null) {
                values = magicValues(magicName, values);
            } else {
                values = sendValues(function, values);
            }
            return boundArguments(values);
        }

        /** The frame arguments for values as the function itself takes them (for __call, magicValues' two). */
        public Object[] boundArguments(Object[] values) {
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
        return resolve(context, callable, false, null, From.stack());
    }

    /** As resolve, in a built-in function's body: builtinArguments are the frame arguments its call gave it. */
    @TruffleBoundary
    public static Target resolve(PhpContext context, Object[] builtinArguments, Object callable) {
        return resolve(context, callable, false, null, From.builtin(builtinArguments));
    }

    /**
     * direct: the target of a call `$f()` (zend_init_dynamic_call_*) made from scope (the calling class,
     * lowercase, or null), which takes class and method names literally ("self", "parent::m" mean nothing there)
     * and binds no $this; else of a callable given to a built-in, which resolves them against the calling code,
     * checks visibility from its scope and takes its $this: from is that code.
     */
    private static Target resolve(PhpContext context, Object callable, boolean direct, String scope, From from) {
        if (callable instanceof PhpClosure closure) {
            PhpClass bound = closure.staticClass();
            return new Target(closure.getFunction(), closure.getBoundThis(), bound, closure, closure.getMagicName());
        }
        if (callable instanceof TruffleString name) {
            // a name seen before: its function id, the context's slot (the functions map's mirror)
            int known = context.getLanguage().callableFunctionId(name);
            if (known >= 0) {
                PhpFunction function = context.functionAt(known);
                if (function != null) {
                    return new Target(function, null, null, null);
                }
            }
            String text = Str.name(name);
            int separator = text.indexOf("::");
            if (separator > 0) {
                return method(classPart(context, text.substring(0, separator), direct, true, from), null,
                        text.substring(separator + 2), direct, scope, from);
            }
            PhpFunction function = context.findFunction(Str.lower(stripNamespace(text)));
            if (function == null) {
                return null;
            }
            context.getLanguage().rememberCallable(name, function.functionId(context.getLanguage()));
            return new Target(function, null, null, null);
        }
        if (callable instanceof PhpArray array && array.size() == 2) {
            Object first = array.get(0L);
            Object second = array.get(1L);
            if (!(second instanceof TruffleString methodName)) {
                return null;
            }
            if (first instanceof PhpObject object) {
                PhpClass phpClass = object.getPhpClass();
                return method(new ClassPart(phpClass, phpClass, object, null), object, Str.name(methodName), direct,
                        scope, from);
            }
            if (first instanceof PhpClosure closure && Str.lower(Str.name(methodName)).equals("__invoke")) {
                return resolve(context, closure, direct, scope, from);
            }
            if (first instanceof TruffleString className) {
                return method(classPart(context, Str.name(className), direct, true, from), null,
                        Str.name(methodName), direct, scope, from);
            }
            return null;
        }
        if (callable instanceof PhpObject object) {
            PhpFunction invoke = object.getPhpClass().findMethod("__invoke");
            return invoke == null ? null : new Target(invoke, object, object.getPhpClass(), null);
        }
        return null;
    }

    /** A missing or inaccessible method: __call on an object, __callStatic on a class (zend_is_callable_check_func). */
    private static Target magic(PhpClass phpClass, PhpObject object, String methodName, boolean direct,
                                From from) {
        if (object != null) {
            PhpFunction magicCall = phpClass.findMethod("__call");
            return magicCall == null ? null
                    : new Target(magicCall, object, object.getPhpClass(), null, Str.of(methodName));
        }
        if (direct && callsNonStaticMagic(phpClass, from)) {
            return null;
        }
        PhpFunction magicStatic = phpClass.findMethod("__callstatic");
        return magicStatic == null ? null : new Target(magicStatic, null, phpClass, null, Str.of(methodName));
    }

    /** __call / __callStatic's arguments: the method name as it was called, and the arguments as an array. */
    @TruffleBoundary
    public static Object[] magicValues(TruffleString name, Object[] values) {
        PhpArray arguments = new PhpArray();
        for (Object value : values) {
            // a reference stays one (zend_call_function doesn't separate references for __call)
            arguments.appendCopied(value);
        }
        return new Object[] {name, arguments};
    }

    /**
     * zend_call_function's sends from runtime code (call_user_func(), a built-in calling back): a value for a
     * by-reference parameter goes in a new reference after zend_param_must_be_ref's warning, the call going on
     * (a prefer-ref built-in's takes it quietly). values: the arguments, a copy made if one changes.
     */
    public static Object[] sendValues(PhpFunction function, Object[] values) {
        return function.hasByRefParameter() ? wrapValues(function, values, null) : values;
    }

    /** As sendValues, for a call made in place by PHP code at site (call_user_func() compiled as an operation). */
    public static Object[] sendValues(PhpFunction function, Object[] values, Node site) {
        return function.hasByRefParameter() ? wrapValues(function, values, site) : values;
    }

    @TruffleBoundary
    private static Object[] wrapValues(PhpFunction function, Object[] values, Node site) {
        Object[] sent = values;
        for (int i = 0; i < values.length; i++) {
            if (function.isByRef(i) && !(values[i] instanceof PhpRef) && !function.isPreferRef()) {
                BindingPlan.mustBeRef(function, i, site);
                if (sent == values) {
                    sent = values.clone();
                }
                sent[i] = new PhpRef(values[i]);
            }
        }
        return sent;
    }

    /** The lowercase name a class-name callable (`['Cls', 'm']`) is looked up by. */
    @TruffleBoundary
    public static String className(TruffleString name) {
        return Str.lower(stripNamespace(Str.name(name)));
    }

    /**
     * A method callable's target: an object's method as zend_std_get_method finds it from the scope, a class's as
     * zend_std_get_static_method; an inaccessible one is a missing one (__call, __callStatic), and a direct call
     * of it without them is zend_bad_method_call's Error.
     */
    private static Target method(ClassPart part, PhpObject object, String methodName, boolean direct,
                                 String scope, From from) {
        PhpClass phpClass = part.calling();
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
        String lower = Str.lower(methodName);
        if (!direct && phpClass.methodDependsOnScope(lower)) {
            Caller caller = from.caller();
            scope = caller == null ? null : caller.scopeName();
        }
        PhpFunction method = object != null ? phpClass.accessibleMethod(lower, scope)
                : phpClass.accessibleStaticMethod(lower, scope);
        if (!direct && object == null && (method == null || !method.isStatic())) {
            // the $this zend_is_callable_check_class took from the caller, for a non-static method or __call
            object = part.object();
        }
        if (method == null) {
            Target magic = magic(phpClass, object, methodName, direct, from);
            if (magic == null && direct && phpClass.findMethod(lower) != null) {
                throw phpClass.callError(methodName, scope);
            }
            return magic;
        }
        if (!method.isStatic() && object == null) {
            // "non-static method Cls::m() cannot be called statically"
            return null;
        }
        PhpClass staticClass = object != null && !method.isStatic() ? object.getPhpClass() : part.called();
        return new Target(method, method.isStatic() ? null : object, staticClass, null);
    }

    /**
     * zend_is_callable_check_class: the class a callable's method is looked up in (calling_scope), the class the
     * call gets as static (called_scope), the $this it gets from the caller, or why the name names none.
     */
    private record ClassPart(PhpClass calling, PhpClass called, PhpObject object, String error) {
        static ClassPart none(String error) {
            return new ClassPart(null, null, null, error);
        }
    }

    /**
     * The class part of a callable. In a call `$f()` (direct, zend_init_dynamic_call_*), the class named, literally.
     * Given to a built-in, from the code calling it: self is its scope, parent the scope's parent, static its called
     * class, each with its $this, the called class kept when it is a subclass; a named class gets the caller's $this
     * when that is an instance of the caller's scope and the scope one of the class. A named class is autoloaded
     * unless autoload is false (an error message's lookup, after the one that failed).
     */
    private static ClassPart classPart(PhpContext context, String name, boolean direct, boolean autoload,
                                       From from) {
        String lower = Str.lower(stripNamespace(name));
        if (direct) {
            PhpClass named = autoload ? context.findClassAutoloading(stripNamespace(name)) : context.findClass(lower);
            return named == null ? ClassPart.none("class \"" + name + "\" not found")
                    : new ClassPart(named, named, null, null);
        }
        Caller caller = from.caller();
        String scopeName = caller == null ? null : caller.scopeName();
        PhpClass scope = scopeName == null ? null : context.findClass(scopeName);
        PhpClass calledByCaller = caller != null && caller.argument(PhpFunction.ARG_STATIC) instanceof PhpClass c
                ? c : null;
        PhpObject callerThis = caller != null && caller.argument(PhpFunction.ARG_THIS) instanceof PhpObject self
                ? self : null;
        switch (lower) {
            case "self", "parent": {
                if (scope == null) {
                    return ClassPart.none("cannot access \"" + lower + "\" when no class scope is active");
                }
                PhpClass calling = lower.equals("self") ? scope : scope.getParent();
                if (calling == null) {
                    return ClassPart.none("cannot access \"parent\" when current class scope has no parent");
                }
                PhpClass called = calledByCaller != null && calledByCaller.isSubclassOf(calling.getLowerName())
                        ? calledByCaller : calling;
                return new ClassPart(calling, called, callerThis, null);
            }
            case "static": {
                if (calledByCaller == null) {
                    return ClassPart.none("cannot access \"static\" when no class scope is active");
                }
                return new ClassPart(calledByCaller, calledByCaller, callerThis, null);
            }
            default: {
                PhpClass named = autoload ? context.findClassAutoloading(stripNamespace(name))
                        : context.findClass(lower);
                if (named == null) {
                    return ClassPart.none("class \"" + name + "\" not found");
                }
                if (scope != null && callerThis != null && callerThis.getPhpClass().isSubclassOf(scope.getLowerName())
                        && scope.isSubclassOf(named.getLowerName())) {
                    return new ClassPart(named, callerThis.getPhpClass(), callerThis, null);
                }
                return new ClassPart(named, named, null, null);
            }
        }
    }

    /**
     * The class a callable names: "self", "parent" and "static" (deprecated in callables since PHP 8.2, still
     * honoured) are the calling code's declaring class, its parent, and the called class.
     */
    /**
     * The class a callable names. It is autoloaded, in a call (zend_init_dynamic_call_*) and a callable parameter
     * (zend_is_callable_check_class) alike; not again for the error message once the lookup failed.
     */
    private static PhpClass namedClass(PhpContext context, String name, boolean direct, boolean autoload,
                                       From from) {
        String lower = Str.lower(stripNamespace(name));
        if (direct) {
            return autoload ? context.findClassAutoloading(stripNamespace(name)) : context.findClass(lower);
        }
        switch (lower) {
            case "self", "parent": {
                Caller caller = from.caller();
                String scope = caller == null ? null : caller.scopeName();
                PhpClass self = scope == null ? null : context.findClass(scope);
                return self == null || lower.equals("self") ? self : self.getParent();
            }
            case "static": {
                Caller caller = from.caller();
                return caller != null && caller.argument(PhpFunction.ARG_STATIC) instanceof PhpClass called
                        ? called : null;
            }
            default:
                return autoload ? context.findClassAutoloading(stripNamespace(name)) : context.findClass(lower);
        }
    }

    /**
     * The class "self", "parent" or "static" means in the PHP code calling a built-in (zend_get_executed_scope, its
     * parent, zend_get_called_scope), or null when that code has none.
     */
    public static PhpClass callerClass(PhpContext context, String lowerName) {
        return namedClass(context, lowerName, false, false, From.stack());
    }

    /**
     * The PHP code calling: its frame's arguments and the lowercase name of its class scope (its declaring class, a
     * closure's bound one, the one a file was included into), or null.
     */
    public record Caller(Object[] arguments, String scopeName) {
        Object argument(int index) {
            return index < arguments.length ? arguments[index] : null;
        }
    }

    /**
     * The PHP code a callable is resolved for, php-src's EG(current_execute_data) behind the internal function:
     * what the call site says it is (an operation's own frame; the Caller a built-in function's call passed it in
     * the frame arguments' static-class slot, which a function has no use for), else the innermost PHP frame on the
     * stack. A stack walk costs microseconds where php-src reads a field, so it is made once, and only for a
     * callable whose meaning depends on the caller (a class's name, a method only some scopes may call).
     */
    private static final class From {
        /** The frame arguments of the built-in function asking, when its call gave it no Caller; else null. */
        private final Object[] asking;
        private Caller caller;
        private boolean found;
        /** Whether the resolution asked for the caller: what it found may depend on it (LastCall). */
        private boolean used;

        private From(Caller caller, boolean found, Object[] asking) {
            this.caller = caller;
            this.found = found;
            this.asking = asking;
        }

        /** The code on the stack: whatever asks, wherever it runs. */
        static From stack() {
            return new From(null, false, null);
        }

        /** The code an operation runs in: its frame's arguments and its scope (PhpBytecodeRootNode.callingScope). */
        static From code(Object[] arguments, String scopeName) {
            return new From(new Caller(arguments, scopeName), true, null);
        }

        /** The code calling a built-in function, in its body: arguments are the built-in's own frame arguments. */
        static From builtin(Object[] arguments) {
            return arguments.length > PhpFunction.ARG_STATIC
                    && arguments[PhpFunction.ARG_STATIC] instanceof Caller given ? new From(given, true, null)
                    : new From(null, false, arguments);
        }

        Caller caller() {
            used = true;
            if (!found) {
                caller = walk(asking);
                found = true;
            }
            return caller;
        }
    }

    /**
     * The innermost frame that isn't a built-in's (call_user_func and the like run between the PHP caller and
     * the callable), or null. asking: the frame arguments of the built-in function whose body asks (or null): the
     * walk starts in its frame, and from now on its calls pass it the Caller (BuiltinRootNode.wantCaller).
     */
    @TruffleBoundary
    private static Caller walk(Object[] asking) {
        boolean[] first = {asking != null};
        return Truffle.getRuntime().iterateFrames(frameInstance -> {
            RootNode running = frameInstance.getCallTarget() instanceof RootCallTarget target ? target.getRootNode()
                    : null;
            RootNode root = SourceLocation.codeRoot(running);
            if (root instanceof pl.codeloom.php.nodes.call.BuiltinRootNode builtin) {
                if (first[0]) {
                    first[0] = false;
                    if (frameInstance.getFrame(FrameInstance.FrameAccess.READ_ONLY).getArguments() == asking) {
                        builtin.wantCaller();
                    }
                }
                return null;
            }
            first[0] = false;
            String scope = root instanceof pl.codeloom.php.bytecode.PhpBytecodeRootNode bytecode
                    ? bytecode.getScopeName() : null;
            Object[] arguments = SourceLocation.codeFrame(running,
                    frameInstance.getFrame(FrameInstance.FrameAccess.READ_ONLY)).getArguments();
            // a closure's bound class, an include's class: the scope the code runs in
            return new Caller(arguments, arguments.length > PhpFunction.ARG_CLOSURE
                    ? pl.codeloom.php.nodes.vars.PropNode.callingScope(arguments[PhpFunction.ARG_CLOSURE], scope)
                    : scope);
        });
    }

    /** The frame argument of the PHP code calling (a built-in's caller), or null. */
    public static Object callerArgument(int index) {
        Caller caller = walk(null);
        return caller == null ? null : caller.argument(index);
    }

    /** The lowercase name of the class scope of the PHP code calling (its declaring class or a closure's), or null. */
    @TruffleBoundary
    public static String callerScope() {
        Caller caller = walk(null);
        return caller == null ? null : caller.scopeName();
    }

    /** $this of the PHP code calling when it is an instance of the class, or null. */
    private static PhpObject callerThisOf(PhpClass phpClass, From from) {
        Caller caller = from.caller();
        return caller != null && caller.argument(PhpFunction.ARG_THIS) instanceof PhpObject self
                && self.getPhpClass().isSubclassOf(phpClass.getLowerName()) ? self : null;
    }

    /**
     * The class a call of `['Cls', 'staticMethod']` made by PHP code gets as static (zend_is_callable_check_class's
     * called_scope): the class of the code's $this when that is an instance of its scope and the scope is one of
     * the class named, else the class named. callerArguments, callerScope: the code's frame arguments and scope.
     */
    public static PhpClass calledClass(PhpContext context, PhpClass named, Object[] callerArguments,
                                       String callerScope) {
        if (callerScope != null && callerArguments.length > PhpFunction.ARG_THIS
                && callerArguments[PhpFunction.ARG_THIS] instanceof PhpObject self && self.getPhpClass() != named) {
            return calledBy(context, named, self.getPhpClass(), callerScope);
        }
        return named;
    }

    @TruffleBoundary
    private static PhpClass calledBy(PhpContext context, PhpClass named, PhpClass callerClass, String callerScope) {
        PhpClass scope = context.findClass(callerScope);
        return scope != null && callerClass.isSubclassOf(scope.getLowerName())
                && scope.isSubclassOf(named.getLowerName()) ? callerClass : named;
    }

    /**
     * A direct static call of a missing method from code running on an instance of the class: php-src's
     * zend_std_get_static_method picks __call there, which is non-static, so the call is an error.
     */
    private static boolean callsNonStaticMagic(PhpClass phpClass, From from) {
        return phpClass.findMethod("__call") != null && callerThisOf(phpClass, from) != null;
    }

    private static String stripNamespace(String name) {
        return name.startsWith("\\") ? name.substring(1) : name;
    }

    /**
     * A built-in's callable parameter, which php-src checks before the built-in runs (Z_PARAM_FUNC): a value
     * that isn't callable is a TypeError naming it, "usort(): Argument #2 ($callback) must be a valid callback".
     * The name is null for a callback in a variadic list (array_udiff()'s), which has none of its own.
     */
    public record Parameter(String function, int position, boolean nullable, String name) {
        public Parameter(String function, int position, boolean nullable) {
            this(function, position, nullable, "callback");
        }
    }

    /** The target of a direct call `$f()` from scope (lowercase, or null), or the Error php-src raises for it. */
    @TruffleBoundary
    public static Target resolveCall(PhpContext context, Object callable, String scope) {
        From from = From.stack();
        Target target = resolve(context, callable, true, scope, from);
        if (target == null) {
            throw new PhpError(directCallError(context, callable, from));
        }
        return target;
    }

    /**
     * The target of a callable given to a built-in, or the TypeError naming its parameter. A null parameter is a
     * callable the runtime calls (an autoloader, a shutdown function), checked when it was registered.
     */
    @TruffleBoundary
    public static Target resolveOrThrow(PhpContext context, Object callable, Parameter parameter) {
        return resolveOrThrow(context, callable, parameter, From.stack());
    }

    /**
     * As resolveOrThrow, for a callable an operation of PHP code calls in place (call_user_func() compiled as one):
     * the calling code is the operation's own, callerArguments its frame's arguments and callerScope its scope.
     */
    @TruffleBoundary
    public static Target resolveOrThrow(PhpContext context, Object callable, Parameter parameter,
                                        Object[] callerArguments, String callerScope) {
        return resolveOrThrow(context, callable, parameter, From.code(callerArguments, callerScope));
    }

    private static Target resolveOrThrow(PhpContext context, Object callable, Parameter parameter, From from) {
        Target target = resolve(context, callable, false, null, from);
        if (target == null) {
            if (parameter == null) {
                throw new PhpError(invalidReason(context, callable, from));
            }
            throw new PhpError("TypeError", parameter.function() + "(): Argument #" + parameter.position()
                    + (parameter.name() == null ? "" : " ($" + parameter.name() + ")") + " must be a valid callback" + (parameter.nullable() ? " or null" : "") + ", "
                    + invalidReason(context, callable, from));
        }
        return target;
    }

    /** zend_is_callable_error's reason a value isn't callable. */
    public static String invalidReason(PhpContext context, Object callable) {
        return invalidReason(context, callable, From.stack());
    }

    private static String invalidReason(PhpContext context, Object callable, From from) {
        if (callable instanceof TruffleString name) {
            String text = Str.java(name);
            int separator = text.indexOf("::");
            if (separator <= 0) {
                return "function \"" + text + "\" not found or invalid function name";
            }
            return methodReason(context, text.substring(0, separator), text.substring(separator + 2), from);
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
                PhpFunction found = object.getPhpClass().findMethod(Str.lower(Str.java(method)));
                return found != null ? cannotAccess(object.getPhpClass(), found)
                        : "class " + object.getPhpClass().getName() + " does not have a method \"" + Str.java(method)
                        + "\"";
            }
            return methodReason(context, Str.java((TruffleString) first), Str.java(method), from);
        }
        return "no array or string given";
    }

    private static String methodReason(PhpContext context, String className, String methodName, From from) {
        ClassPart part = classPart(context, className, false, false, from);
        PhpClass phpClass = part.calling();
        if (phpClass == null) {
            return part.error();
        }
        PhpFunction method = phpClass.findMethod(Str.lower(methodName));
        if (method != null && !method.isStatic()) {
            return "non-static method " + phpClass.getName() + "::" + method.getName() + "() cannot be called "
                    + "statically";
        }
        return method != null ? cannotAccess(phpClass, method)
                : "class " + phpClass.getName() + " does not have a method \"" + methodName + "\"";
    }

    /** zend_is_callable_check_func's reason for a method the calling scope may not call. */
    private static String cannotAccess(PhpClass phpClass, PhpFunction method) {
        return "cannot access " + PhpClass.visibility(method) + " method " + phpClass.getName() + "::"
                + method.getName() + "()";
    }

    /** The Error a direct call `$f()` of a value that isn't callable raises (zend_init_dynamic_call_*). */
    private static String directCallError(PhpContext context, Object callable, From from) {
        if (callable instanceof TruffleString name) {
            String text = Str.java(name);
            int separator = text.indexOf("::");
            if (separator <= 0) {
                return "Call to undefined function " + text + "()";
            }
            return methodError(context, text.substring(0, separator), text.substring(separator + 2), from);
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
            return methodError(context, Str.java((TruffleString) first), Str.java(method), from);
        }
        if (callable instanceof PhpObject object) {
            return "Object of type " + object.getPhpClass().getName() + " is not callable";
        }
        return "Value of type " + Convert.debugType(callable) + " is not callable";
    }

    private static String methodError(PhpContext context, String className, String methodName, From from) {
        PhpClass phpClass = namedClass(context, className, true, false, from);
        if (phpClass == null) {
            return "Class \"" + className + "\" not found";
        }
        PhpFunction method = phpClass.findMethod(Str.lower(methodName));
        if (method != null && !method.isStatic() || method == null && callsNonStaticMagic(phpClass, from)) {
            return "Non-static method " + phpClass.getName() + "::" + (method != null ? method.getName() : methodName)
                    + "() cannot be called statically";
        }
        return "Call to undefined method " + phpClass.getName() + "::" + methodName + "()";
    }

    /**
     * The last callable resolved by {@link #call}, one per context: built-ins that call back in a loop (array_map,
     * usort, preg_replace_callback) pass the same callable value every time. An array callable is re-checked
     * element by element, since an unshared array can change in place. A target found by looking at the calling
     * code (a class named: the class called is that code's $this's when it is an instance of the class through the
     * code's scope) is that of code with the same class of $this and the same scope only.
     */
    public static final class LastCall {
        Object callable;
        Object first;
        Object second;
        Target target;
        /** Whether the resolution asked for the calling code; then the class of its $this (or null) and its scope. */
        boolean byCaller;
        PhpClass callerClass;
        String callerScope;

        boolean sameCaller(Caller caller) {
            PhpClass thisClass = caller != null && caller.argument(PhpFunction.ARG_THIS) instanceof PhpObject self
                    ? self.getPhpClass() : null;
            return thisClass == callerClass
                    && java.util.Objects.equals(caller == null ? null : caller.scopeName(), callerScope);
        }

        void keepCaller(Caller caller) {
            callerClass = caller != null && caller.argument(PhpFunction.ARG_THIS) instanceof PhpObject self
                    ? self.getPhpClass() : null;
            callerScope = caller == null ? null : caller.scopeName();
        }
        /**
         * Function-name callables resolved so far (php-src finds them with one hash lookup): a declared function
         * stays declared, so an entry never goes stale. Bounded, as names may come from input.
         */
        final java.util.HashMap<TruffleString, Target> functions = new java.util.HashMap<>();
    }

    /** Calls a callable from runtime code (array_map, usort, preg_replace_callback...): no inline caching. */
    @TruffleBoundary
    public static Object call(PhpContext context, Object callable, Object... values) {
        Target target = target(context, callable, null, From.stack());
        return Refcount.pending(PhpRef.deref(target.function().getCallTarget().call(target.frameArguments(values))));
    }

    /**
     * Checks a built-in's callable parameter on entry, as php-src's parameter parsing does: before any work, and
     * even when the callback would never be called (an empty array). The later {@link #call}s reuse the target.
     */
    @TruffleBoundary
    public static void check(PhpContext context, Object callable, Parameter parameter) {
        target(context, callable, parameter, From.stack());
    }

    /**
     * A built-in's callable parameter, resolved once on entry as php-src's fci_cache: the built-in calls the
     * target for every element or match, whatever its callbacks call in between.
     */
    @TruffleBoundary
    public static Target parameter(PhpContext context, Object callable, Parameter parameter) {
        return target(context, callable, parameter, From.stack());
    }

    /** As parameter, in a built-in function's body: builtinArguments are the frame arguments its call gave it. */
    @TruffleBoundary
    public static Target parameter(PhpContext context, Object[] builtinArguments, Object callable,
                                   Parameter parameter) {
        return target(context, callable, parameter, From.builtin(builtinArguments));
    }

    /** Whether what a method callable resolves to depends on the calling scope (PhpClass.methodDependsOnScope). */
    public static boolean scoped(Object callable, Target target) {
        PhpClass phpClass = target.staticClass();
        if (phpClass == null || callable instanceof PhpClosure) {
            return false;
        }
        String name = callable instanceof PhpArray array && array.get(1L) instanceof TruffleString method
                ? Str.java(method) : callable instanceof TruffleString text ? Str.java(text) : null;
        if (name == null) {
            return false;
        }
        return phpClass.methodDependsOnScope(Str.lower(name.substring(name.indexOf("::") + 2)));
    }

    /**
     * Whether the callable names a class relative to the caller: "self::m", ["static", "m"], [$obj, "parent::m"]
     * (zend_is_callable_check_class's self, parent and static, resolved against the calling scope and class).
     */
    private static boolean callerRelative(Object callable) {
        if (callable instanceof TruffleString text) {
            return relativeClass(Str.java(text));
        }
        if (callable instanceof PhpArray array) {
            return PhpRef.deref(array.get(0L)) instanceof TruffleString cls && relativeClass(Str.java(cls) + "::")
                    || PhpRef.deref(array.get(1L)) instanceof TruffleString method && relativeClass(Str.java(method));
        }
        return false;
    }

    /** Whether name starts with self::, parent:: or static:: (any case). */
    private static boolean relativeClass(String name) {
        int separator = name.indexOf("::");
        if (separator < 0) {
            return false;
        }
        String cls = name.substring(0, separator);
        return cls.equalsIgnoreCase("self") || cls.equalsIgnoreCase("parent") || cls.equalsIgnoreCase("static");
    }

    private static Target target(PhpContext context, Object callable, Parameter parameter, From from) {
        LastCall last = context.lastCall();
        if (callable == last.callable && (!(callable instanceof PhpArray array)
                || array.get(0L) == last.first && array.get(1L) == last.second)
                && (!last.byCaller || last.sameCaller(from.caller()))) {
            return last.target;
        }
        Target target;
        if (callable instanceof TruffleString name) {
            target = last.functions.get(name);
            if (target == null) {
                target = resolveOrThrow(context, callable, parameter, from);
                if (target.staticClass() == null) {
                    // a plain function, not "Cls::m" (whose class and $this may depend on the caller)
                    if (last.functions.size() >= 4096) {
                        last.functions.clear();
                    }
                    last.functions.put(name, target);
                }
            }
        } else {
            target = resolveOrThrow(context, callable, parameter, from);
        }
        // a class callable bound to the caller's $this, a method whose visibility the caller's scope decides, or
        // a class named relative to the caller (self::, parent::, static::) depends on the caller: not kept
        boolean boundByCaller = target.thisObject() != null && !(callable instanceof PhpClosure)
                && !(callable instanceof PhpArray array && array.get(0L) instanceof PhpObject);
        // a function's name (no class part: only "Cls::m" has a static class) is the same from every caller
        boolean function = callable instanceof TruffleString && target.staticClass() == null;
        last.callable = boundByCaller || !function && (scoped(callable, target) || callerRelative(callable))
                ? null : callable;
        last.target = target;
        last.byCaller = last.callable != null && from.used;
        if (last.byCaller) {
            last.keepCaller(from.caller());
        } else {
            last.callerClass = null;
            last.callerScope = null;
        }
        if (callable instanceof PhpArray array) {
            last.first = array.get(0L);
            last.second = array.get(1L);
        }
        return target;
    }
}
