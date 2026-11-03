package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
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
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
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

    public BuiltinRootNode(PhpLanguage language, String name, Builtin body, boolean method, int min, int max) {
        super(language);
        this.name = name;
        this.body = body;
        this.method = method;
        this.min = min;
        this.max = max;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] arguments = frame.getArguments();
        int count = arguments.length - PhpFunction.ARG_FIRST;
        if (count < min || count > max) {
            CompilerDirectives.transferToInterpreter();
            throw wrongCount(arguments);
        }
        return call(body, arguments);
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
        PhpObject self = method && arguments[PhpFunction.ARG_THIS] instanceof PhpObject object ? object : null;
        String className = method ? declaringClass(arguments) : null;
        PhpArray args = new PhpArray();
        for (int i = PhpFunction.ARG_FIRST; i < arguments.length; i++) {
            args.append(PhpRef.deref(arguments[i]));
        }
        Backtrace.describeCall(entry, name, className, self, options, args);
        return true;
    }

    /** The topmost class of the call's object (or static class) with this implementation, or null. */
    private String declaringClass(Object[] arguments) {
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
