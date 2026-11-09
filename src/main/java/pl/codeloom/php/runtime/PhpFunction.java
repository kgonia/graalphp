package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.builtins.Builtin;
import pl.codeloom.php.builtins.BuiltinSignature;

import java.util.function.Supplier;

/**
 * A user function, method or built-in. The call target is built on first call, so bodies that never run are
 * never translated.
 *
 * <p>Calling convention (Truffle frame arguments): [0] $this or null, [1] the late-static-binding class or null,
 * [2] the closure or null, [3..] the PHP arguments; a by-reference parameter receives a {@link PhpRef}.
 */
public final class PhpFunction {
    public static final int ARG_THIS = 0;
    public static final int ARG_STATIC = 1;
    public static final int ARG_CLOSURE = 2;
    public static final int ARG_FIRST = 3;

    /**
     * A frame argument a call by name left out (php-src's UNDEF slot below num_args): the callee's defaults target
     * replaces it before the callee runs, so no prologue ever reads it. Distinct from PHP's null.
     */
    public static final Object NOT_PASSED = new Object() {
        @Override
        public String toString() {
            return "NOT_PASSED";
        }
    };

    /**
     * A parameter. defaultValue is null for required parameters, else how a declaration writes it ("= 'a'").
     * declared is the type the argument is checked against, or null. type is the declared type as Reflection
     * writes it ("?Foo", "int|string|null"), or null.
     */
    public record Param(String name, boolean byRef, boolean variadic, Object defaultValue, PhpType declared,
                        boolean nullable, String type) {
    }

    /** php-src's ZEND_ACC_* modifier bits, as ReflectionMethod::getModifiers() gives them. */
    public static final int ACC_PUBLIC = 1;
    public static final int ACC_PROTECTED = 2;
    public static final int ACC_PRIVATE = 4;
    public static final int ACC_STATIC = 16;
    public static final int ACC_FINAL = 32;
    public static final int ACC_ABSTRACT = 64;

    private final String name;
    /** Whether a parameter is by reference: calls from runtime code check their values only then. */
    private final boolean anyByRef;
    @CompilationFinal(dimensions = 1) private final Param[] params;
    private final boolean returnsRef;
    /**
     * ZEND_SEND_PREFER_REF (a built-in's array_multisort, extract): its by-reference parameters take a variable by
     * reference and any other argument as a value, without php-src's Error or notice.
     */
    private boolean preferRef;
    private final boolean isStatic;
    private final boolean isPublic;
    private final boolean isAbstract;
    /**
     * Makes the call target on first call; dropped then, as it holds the function's parsed php-ast (a WordPress
     * server kept ~130 MB of trees for functions translated long before). Guarded by createCallTarget's lock.
     */
    private Supplier<RootCallTarget> factory;
    /** A built-in's Java body, for DSL built-in nodes' fallbacks (BuiltinNodes.callFull); null for PHP functions. */
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal private Builtin builtin;
    /** A built-in's key in BuiltinSignature's table, and the signature once a named call asked for it. */
    private String builtinKey;
    private volatile BuiltinSignature builtinSignature;
    /** The declaring class's name for messages ("C::m"); null for functions and shared built-in methods. */
    private String className;

    /** Made on first call; volatile because contexts on several threads share the function. */
    @CompilationFinal private volatile RootCallTarget callTarget;
    /**
     * Makes the target that fills the arguments a call by name left out with the parameters' defaults (php-src's
     * zend_handle_undef_args), on the first such call; dropped then. Null for a built-in.
     */
    private Supplier<RootCallTarget> defaultsFactory;
    private volatile RootCallTarget defaultsTarget;

    public PhpFunction(String name, Param[] params, boolean returnsRef, boolean isStatic, boolean isPublic,
                       boolean isAbstract, Supplier<RootCallTarget> factory) {
        this.name = name;
        this.params = params;
        boolean byRef = false;
        for (Param param : params) {
            byRef |= param.byRef();
        }
        this.anyByRef = byRef;
        this.returnsRef = returnsRef;
        this.isStatic = isStatic;
        this.isPublic = isPublic;
        this.isAbstract = isAbstract;
        this.factory = factory;
    }

    public String getName() {
        return name;
    }

    /**
     * The name lowercased, and its id (PhpLanguage.functionId), each made once: the contexts share a file's
     * functions and every request declares them again. A race makes the same value twice.
     */
    private String lowerName;
    private int functionId = -1;

    public String lowerName() {
        String lower = lowerName;
        if (lower == null) {
            lower = Str.lower(name);
            lowerName = lower;
        }
        return lower;
    }

    public int functionId(PhpLanguage language) {
        int id = functionId;
        if (id < 0) {
            id = language.functionId(lowerName());
            functionId = id;
        }
        return id;
    }

    public Param[] getParams() {
        return params;
    }

    public int paramCount() {
        return params.length;
    }

    /** Whether the argument at a position is passed by reference (variadic by-ref covers the rest). */
    /** Whether any parameter takes its argument by reference (ZEND_ACC_HAS_... by-ref arginfo). */
    public boolean hasByRefParameter() {
        return anyByRef;
    }

    public boolean isByRef(int index) {
        if (index < params.length) {
            return params[index].byRef();
        }
        return params.length > 0 && params[params.length - 1].variadic() && params[params.length - 1].byRef();
    }

    /** `function &f()`: the call target returns a {@link PhpRef}, which call sites in value contexts dereference. */
    public boolean returnsRef() {
        return returnsRef;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    /** Where a user function was declared; null for a built-in. */
    private SourceLocation declaredAt;

    public void setDeclaredAt(SourceLocation declaredAt) {
        this.declaredAt = declaredAt;
    }

    public SourceLocation getDeclaredAt() {
        return declaredAt;
    }

    /** A method's ZEND_ACC_* modifiers (public, protected, private, static, final, abstract); 0 for a function. */
    private int modifiers;
    /** The declared return type as zend_type_to_string writes it, or null. */
    private String returnType;
    /** The return type values are checked against (null for none, void, or a generator), set before a call. */
    private PhpType declaredReturn;
    /** The doc comment before the declaration, or null; and the declaration's last line. */
    private String docComment;
    private int endLine;

    /** What Reflection reports of a user function's declaration. */
    public void setDeclaration(int modifiers, String returnType, String docComment, int endLine) {
        this.modifiers = modifiers;
        this.returnType = returnType;
        this.docComment = docComment;
        this.endLine = endLine;
    }

    public int getModifiers() {
        return modifiers;
    }

    public void setDeclaredReturn(PhpType type) {
        this.declaredReturn = type;
    }

    public PhpType getDeclaredReturn() {
        return declaredReturn;
    }

    /**
     * A trait alias's copy with its modifiers (`use T { m as public; m as protected n; }`, zend_traits_copy_
     * functions): a visibility replaces the method's, final adds to it. The copy calls the original's target.
     */
    public PhpFunction withAliasModifiers(int aliasModifiers) {
        int ppp = ACC_PUBLIC | ACC_PROTECTED | ACC_PRIVATE;
        int adapted = (aliasModifiers & ppp) != 0 ? aliasModifiers | (modifiers & ~ppp) : aliasModifiers | modifiers;
        if (adapted == modifiers) {
            return this;
        }
        boolean adaptedPublic = (adapted & (ACC_PROTECTED | ACC_PRIVATE)) == 0;
        PhpFunction copy = new PhpFunction(name, params, returnsRef, isStatic, adaptedPublic, isAbstract,
                this::getCallTarget);
        copy.setDeclaration(adapted, returnType, docComment, endLine);
        copy.declaredAt = declaredAt;
        copy.className = className;
        copy.builtin = builtin;
        copy.builtinKey = builtinKey;
        copy.usesThis = usesThis;
        copy.declaredReturn = declaredReturn;
        if (defaultsFactory != null || defaultsTarget != null) {
            copy.defaultsFactory = this::getDefaultsTarget;
        }
        return copy;
    }

    public void setDefaults(Supplier<RootCallTarget> factory) {
        this.defaultsFactory = factory;
    }

    /**
     * The target filling a call's left-out arguments (run on the call's frame arguments before the function's own
     * target), made on first use; null for a function without one (a built-in).
     */
    @TruffleBoundary
    public synchronized RootCallTarget getDefaultsTarget() {
        if (defaultsTarget == null && defaultsFactory != null) {
            defaultsTarget = defaultsFactory.get();
            defaultsFactory = null;
        }
        return defaultsTarget;
    }

    public String getReturnType() {
        return returnType;
    }

    public String getDocComment() {
        return docComment;
    }

    public int getEndLine() {
        return endLine;
    }

    /** The declaring class's name ("C" of "C::m"), or null for a function and a shared built-in method. */
    public String getClassName() {
        return className;
    }

    /** A closure whose body uses $this (ZEND_ACC_USES_THIS): Closure::bind can't unbind its $this. */
    private boolean usesThis;

    /**
     * A closure's `use` variables, then its `static` ones, by name, and the statics' initial values where they are
     * constants (else null): what its debug info shows before a call initializes them.
     */
    private String[] closureVariables;
    private Object[] staticInitials;

    public void setClosureVariables(String[] names, Object[] staticInitials) {
        this.closureVariables = names;
        this.staticInitials = staticInitials;
    }

    public String[] getClosureVariables() {
        return closureVariables;
    }

    public Object getStaticInitial(int index) {
        Object value = staticInitials[index];
        return value == null ? PhpNull.NULL : value;
    }

    public void setUsesThis(boolean usesThis) {
        this.usesThis = usesThis;
    }

    public boolean usesThis() {
        return usesThis;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public Builtin getBuiltin() {
        return builtin;
    }

    public void setBuiltin(Builtin builtin) {
        this.builtin = builtin;
    }

    /**
     * A built-in's arginfo parameters for named arguments (BuiltinSignature, by its Registry key), or null: a
     * function, or a built-in php-src doesn't have.
     */
    public BuiltinSignature getBuiltinSignature() {
        if (builtinKey == null) {
            return null;
        }
        BuiltinSignature signature = builtinSignature;
        if (signature == null) {
            signature = BuiltinSignature.of(builtinKey);
            builtinSignature = signature;
        }
        return signature;
    }

    /**
     * get_function_arg_name: the name messages give parameter index's argument, a built-in's its arginfo's; null
     * for a variadic parameter's argument (only the num_args before it are named) or a built-in php-src lacks.
     */
    public String argumentName(int index) {
        if (builtin != null) {
            BuiltinSignature signature = getBuiltinSignature();
            return signature != null && index < signature.declared() ? signature.name(index) : null;
        }
        return index < params.length && !params[index].variadic() ? params[index].name() : null;
    }

    /** The key a built-in's signature is found by: lower case "name" or "class::name". */
    public void setBuiltinKey(String builtinKey) {
        this.builtinKey = builtinKey;
    }

    public boolean isPreferRef() {
        return preferRef;
    }

    public void setPreferRef() {
        preferRef = true;
    }

    /**
     * The call target, made on first call. Compiled code deoptimizes for that only where this function is a
     * constant of the compilation (a call site's cached callee), so that it reads the target as a constant from
     * then on. Reached through generic dispatch (call_user_func_array, a hook's callbacks) it isn't one, and a
     * deoptimization there for every function not yet called was DeoptCyclePatterns.md's dispatch-node cycle:
     * WP_Hook::apply_filters deoptimized and was compiled again for each new callback it met.
     */
    public RootCallTarget getCallTarget() {
        RootCallTarget target = callTarget;
        if (target == null) {
            if (CompilerDirectives.isPartialEvaluationConstant(this)) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
            }
            target = createCallTarget();
        }
        return target;
    }

    /** Once, also when contexts on several threads call the function first at the same time. */
    @TruffleBoundary
    private synchronized RootCallTarget createCallTarget() {
        if (callTarget == null) {
            if (isAbstract) {
                throw new PhpError("Cannot call abstract method " + displayName() + "()");
            }
            callTarget = factory.get();
            factory = null;
        }
        return callTarget;
    }

    /** A call from runtime code (not a call node): no inline caching. The result is a value. */
    @TruffleBoundary
    public Object invoke(Object thisObject, PhpClass staticClass, Object... args) {
        return PhpRef.deref(invokeRef(thisObject, staticClass, args));
    }

    /**
     * invoke, the result as the function returned it: a reference from a function returning by reference. The count
     * a returned value owns goes to the context's pending releases (Refcount.pending), given up when the built-in
     * running ends: runtime code uses the result as a borrowed value.
     */
    @TruffleBoundary
    public Object invokeRef(Object thisObject, PhpClass staticClass, Object... args) {
        return Refcount.pending(invokeOwned(thisObject, staticClass, args));
    }

    /** invokeRef for a caller that gives up the result's own count itself (iteration, a destructor's call). */
    @TruffleBoundary
    public Object invokeOwned(Object thisObject, PhpClass staticClass, Object... args) {
        Object[] arguments = new Object[ARG_FIRST + args.length];
        arguments[ARG_THIS] = thisObject;
        arguments[ARG_STATIC] = staticClass;
        System.arraycopy(args, 0, arguments, ARG_FIRST, args.length);
        return getCallTarget().call(arguments);
    }

    public String displayName() {
        return className == null ? name : className + "::" + name;
    }

    @Override
    public String toString() {
        return "function " + displayName();
    }
}
