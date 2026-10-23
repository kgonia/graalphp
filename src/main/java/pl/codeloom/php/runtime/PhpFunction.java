package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import pl.codeloom.php.builtins.Builtin;

import java.util.HashMap;
import java.util.Map;
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
    private final Supplier<RootCallTarget> factory;
    /** A built-in's Java body, for DSL built-in nodes' fallbacks (BuiltinNodes.callFull); null for PHP functions. */
    private Builtin builtin;
    private PhpClass declaringClass;
    private final Map<String, PhpRef> staticVariables = new HashMap<>();

    @CompilationFinal private RootCallTarget callTarget;

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

    public PhpClass getDeclaringClass() {
        return declaringClass;
    }

    public void setDeclaringClass(PhpClass declaringClass) {
        this.declaringClass = declaringClass;
    }

    public Builtin getBuiltin() {
        return builtin;
    }

    public void setBuiltin(Builtin builtin) {
        this.builtin = builtin;
    }

    public RootCallTarget getCallTarget() {
        if (callTarget == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            if (isAbstract) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Cannot call abstract method " + displayName() + "()");
            }
            callTarget = factory.get();
        }
        return callTarget;
    }

    /** The shared PhpRef of a `static $name` variable, or null before its first initialization. */
    @TruffleBoundary
    public PhpRef findStaticVariable(String variableName) {
        return staticVariables.get(variableName);
    }

    @TruffleBoundary
    public PhpRef createStaticVariable(String variableName, Object initial) {
        PhpRef ref = new PhpRef(initial);
        staticVariables.put(variableName, ref);
        return ref;
    }

    /** A call from runtime code (not a call node): no inline caching. */
    @TruffleBoundary
    public Object invoke(Object thisObject, PhpClass staticClass, Object... args) {
        Object[] arguments = new Object[ARG_FIRST + args.length];
        arguments[ARG_THIS] = thisObject;
        arguments[ARG_STATIC] = staticClass;
        System.arraycopy(args, 0, arguments, ARG_FIRST, args.length);
        return getCallTarget().call(arguments);
    }

    public String displayName() {
        return declaringClass == null ? name : declaringClass.getName() + "::" + name;
    }

    @Override
    public String toString() {
        return "function " + displayName();
    }
}
