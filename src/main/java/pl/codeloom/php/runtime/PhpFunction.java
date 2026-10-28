package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import pl.codeloom.php.builtins.Builtin;

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
     * A parameter. defaultValue is null for required parameters, else a value or a {@link PhpClass.Thunk}.
     * coerce is the scalar type the argument is converted to in coercive typing mode ('s', 'i', 'f', 'b'), or 0.
     */
    public record Param(String name, boolean byRef, boolean variadic, Object defaultValue, char coerce,
                        boolean nullable) {
    }

    private final String name;
    @CompilationFinal(dimensions = 1) private final Param[] params;
    private final boolean returnsRef;
    private final boolean isStatic;
    private final boolean isPublic;
    private final boolean isAbstract;
    /**
     * Makes the call target on first call; dropped then, as it holds the function's parsed php-ast (a WordPress
     * server kept ~130 MB of trees for functions translated long before). Guarded by createCallTarget's lock.
     */
    private Supplier<RootCallTarget> factory;
    /** A built-in's Java body, for DSL built-in nodes' fallbacks (BuiltinNodes.callFull); null for PHP functions. */
    private Builtin builtin;
    /** The declaring class's name for messages ("C::m"); null for functions and shared built-in methods. */
    private String className;

    /** Made on first call; volatile because contexts on several threads share the function. */
    @CompilationFinal private volatile RootCallTarget callTarget;

    public PhpFunction(String name, Param[] params, boolean returnsRef, boolean isStatic, boolean isPublic,
                       boolean isAbstract, Supplier<RootCallTarget> factory) {
        this.name = name;
        this.params = params;
        this.returnsRef = returnsRef;
        this.isStatic = isStatic;
        this.isPublic = isPublic;
        this.isAbstract = isAbstract;
        this.factory = factory;
    }

    public String getName() {
        return name;
    }

    public Param[] getParams() {
        return params;
    }

    public int paramCount() {
        return params.length;
    }

    /** Whether the argument at a position is passed by reference (variadic by-ref covers the rest). */
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

    /** A closure whose body uses $this (ZEND_ACC_USES_THIS): Closure::bind can't unbind its $this. */
    private boolean usesThis;

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

    public RootCallTarget getCallTarget() {
        RootCallTarget target = callTarget;
        if (target == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            target = createCallTarget();
        }
        return target;
    }

    /** Once, also when contexts on several threads call the function first at the same time. */
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
        Object[] arguments = new Object[ARG_FIRST + args.length];
        arguments[ARG_THIS] = thisObject;
        arguments[ARG_STATIC] = staticClass;
        System.arraycopy(args, 0, arguments, ARG_FIRST, args.length);
        return PhpRef.deref(getCallTarget().call(arguments));
    }

    public String displayName() {
        return className == null ? name : className + "::" + name;
    }

    @Override
    public String toString() {
        return "function " + displayName();
    }
}
