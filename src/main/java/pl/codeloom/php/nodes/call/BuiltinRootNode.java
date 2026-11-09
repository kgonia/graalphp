package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.builtins.Builtin;
import pl.codeloom.php.bytecode.NamedExtras;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/**
 * A built-in function's call target. The body runs behind a TruffleBoundary: built-ins are plain Java (JDK
 * strings, collections, FFM) that partial evaluation must not expand. Built-ins worth inlining into PHP code
 * become dedicated nodes instead.
 */
public final class BuiltinRootNode extends RootNode implements Backtrace.Callee {
    /** A placeholder source so profilers and stack traces list built-ins by name. */
    private static final Source BUILTINS = Source.newBuilder(PhpLanguage.ID, "", "<builtin>").build();

    private final String name;
    private final Builtin body;
    /** A built-in class's method (traces name its class), not a function. */
    private final boolean method;
    /** The argument counts php-src's arginfo allows (Registry.arity); max is Integer.MAX_VALUE for variadic. */
    private final int min;
    private final int max;
    /**
     * Valid while no call has passed this built-in named arguments its variadic parameter doesn't name (NamedExtras
     * in the closure slot): until then nothing here looks for them.
     */
    private final Assumption noNamedExtras = Truffle.getRuntime().createAssumption("no named extras");
    /** Whether the built-in passes such names on (Z_PARAM_VARIADIC_WITH_NAMED), else refuses them. */
    @CompilationFinal private boolean forwardsNamed;

    public BuiltinRootNode(PhpLanguage language, String name, Builtin body, boolean method, int min, int max) {
        super(language);
        this.name = name;
        this.body = body;
        this.method = method;
        this.min = min;
        this.max = max;
    }

    private static final byte RESULT_OBJECT = 1;
    private static final byte RESULT_CLOSURE = 2;
    private static final byte PENDING = 4;
    /** The counting cases met (a case not met yet deoptimizes once). */
    @CompilationFinal private byte seen;

    private void met(byte kind) {
        if ((seen & kind) == 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            seen |= kind;
        }
    }

    @TruffleBoundary
    private static void releasePendingOnThrow(java.util.ArrayList<Object> pending, int mark) {
        if (pending.size() > mark) {
            pl.codeloom.php.runtime.Refcount.releasePending(pending, mark);
        }
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] arguments = frame.getArguments();
        int count = arguments.length - PhpFunction.ARG_FIRST;
        if (count < min || count > max) {
            CompilerDirectives.transferToInterpreter();
            throw wrongCount(arguments);
        }
        if (!noNamedExtras.isValid() && arguments[PhpFunction.ARG_CLOSURE] instanceof NamedExtras && !forwardsNamed) {
            throw unexpectedNamed(arguments);
        }
        pl.codeloom.php.PhpContext context = pl.codeloom.php.PhpContext.get(this);
        java.util.ArrayList<Object> pending = context.pendingReleases();
        int mark = pending.size();
        java.util.ArrayList<Object> moved = context.movedToBuiltins();
        if (!moved.isEmpty()) {
            // arguments moved into this call (Refcount.toBuiltin): counted while it runs
            pl.codeloom.php.runtime.Refcount.takeMoved(arguments, pending, moved);
        }
        Object result;
        try {
            result = call(body, arguments);
        } catch (RuntimeException | Error e) {
            // what PHP code it called gave it goes (out of line: the handler stays small)
            releasePendingOnThrow(pending, mark);
            throw e;
        }
        // a call's result owns a count, as a user function's return gives it (wiki/destructors.md, P1); by the
        // kinds this built-in returned, so the counting compiles only where it is met (and the root stays small
        // enough to inline)
        if (result instanceof PhpObject object) {
            met(RESULT_OBJECT);
            object.addRef();
        } else if (result instanceof PhpClosure closureValue) {
            met(RESULT_CLOSURE);
            closureValue.addRef();
        }
        if (pending.size() > mark) {
            // the counts results of PHP code it called owned (Refcount.pending): its work with them is done,
            // after its own result took its count
            met(PENDING);
            pl.codeloom.php.runtime.Refcount.releasePending(pending, mark, result);
        }
        return result;
    }

    /**
     * A call is about to pass the built-in named arguments its variadic parameter doesn't name: from now on it looks
     * for them. forwards: whether it takes them on (call_user_func...), else it throws (zpp's variadic).
     */
    public void expectNamedExtras(boolean forwards) {
        if (noNamedExtras.isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            forwardsNamed = forwards;
            noNamedExtras.invalidate();
        }
    }

    /**
     * zend_unexpected_extra_named_error, where zpp reaches a variadic parameter that doesn't take names: after the
     * argument count is checked, in the built-in's frame.
     */
    @TruffleBoundary
    private PhpError unexpectedNamed(Object[] arguments) {
        return new PhpError("ArgumentCountError", messageName(arguments)
                + "() does not accept unknown named parameters");
    }

    /** The name the built-in's messages give it: a method's after the class declaring it, as php-src's scope. */
    @TruffleBoundary
    public String messageName(Object[] arguments) {
        String className = method ? declaringClass(arguments) : null;
        return className == null ? name : className + "::" + name;
    }

    /**
     * zend_wrong_parameters_count_error, thrown in the built-in's frame (traces list it as #0). A method is named
     * after the class declaring it, as php-src's scope.
     */
    @TruffleBoundary
    private PhpError wrongCount(Object[] arguments) {
        String className = method ? declaringClass(arguments) : null;
        return Args.wrongCount(className == null ? name : className + "::" + name, min, max,
                arguments.length - PhpFunction.ARG_FIRST);
    }

    @TruffleBoundary
    private static Object call(Builtin body, Object[] arguments) {
        return body.call(arguments);
    }

    /**
     * A method in the class that declares it (the topmost one with this implementation), as php-src's scope. The
     * prelude's helpers (namespace Truffle) are part of the internal function that called them: no entry.
     */
    @Override
    public boolean describe(Frame frame, PhpArray entry, int options, Backtrace.CallSite site) {
        if (name.regionMatches(true, 0, "truffle\\", 0, "truffle\\".length())) {
            return false;
        }
        Object[] arguments = frame.getArguments();
        Object self = method && (arguments[PhpFunction.ARG_THIS] instanceof PhpObject
                || arguments[PhpFunction.ARG_THIS] instanceof PhpClosure) ? arguments[PhpFunction.ARG_THIS] : null;
        String className = method ? declaringClass(arguments) : null;
        PhpArray args = new PhpArray();
        for (int i = PhpFunction.ARG_FIRST; i < arguments.length; i++) {
            // a hole a call by name left, while its default is filled: UNDEF, shown as NULL
            args.append(arguments[i] == PhpFunction.NOT_PASSED ? PhpNull.NULL : PhpRef.deref(arguments[i]));
        }
        if (arguments[PhpFunction.ARG_CLOSURE] instanceof NamedExtras extras) {
            extras.describe(args);
        }
        Backtrace.describeCall(entry, name, className, self, options, args);
        return true;
    }

    /** The topmost class of the call's object (or static class) with this implementation, or null; a closure's is Closure. */
    private String declaringClass(Object[] arguments) {
        if (arguments[PhpFunction.ARG_THIS] instanceof PhpClosure) {
            return "Closure";
        }
        PhpClass called = arguments[PhpFunction.ARG_THIS] instanceof PhpObject self ? self.getPhpClass()
                : arguments[PhpFunction.ARG_STATIC] instanceof PhpClass phpClass ? phpClass : null;
        String lowerName = Str.lower(name);
        String className = null;
        for (PhpClass c = called; c != null; c = c.getParent()) {
            PhpFunction declared = c.findMethod(lowerName);
            if (declared != null && declared.getCallTarget() == getCallTarget()) {
                className = c.getName();
            }
        }
        return className;
    }

    @Override
    public SourceSection getSourceSection() {
        return BUILTINS.createUnavailableSection();
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * get_active_function_name for a built-in's messages: the innermost built-in function on the stack, or null.
     * Code that runs inside a stream layer (a socket's send) doesn't know which function called it.
     */
    @TruffleBoundary
    public static String activeName() {
        return Truffle.getRuntime().iterateFrames(frame ->
                frame.getCallTarget() instanceof RootCallTarget target
                        && target.getRootNode() instanceof BuiltinRootNode builtin ? builtin.name : null);
    }
}
