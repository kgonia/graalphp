package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.builtins.Builtin;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
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

    public BuiltinRootNode(PhpLanguage language, String name, Builtin body, boolean method) {
        super(language);
        this.name = name;
        this.body = body;
        this.method = method;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return call(body, frame.getArguments());
    }

    @TruffleBoundary
    private static Object call(Builtin body, Object[] arguments) {
        return body.call(arguments);
    }

    /** Traces list a call with its arguments: the frames exceptions pass are captured. */
    @Override
    protected boolean isCaptureFramesForTrace(boolean compiledFrame) {
        return true;
    }

    /** A method in the class that declares it (the topmost one with this implementation), as php-src's scope. */
    @Override
    public boolean describe(Frame frame, PhpArray entry, int options, Backtrace.CallSite site) {
        Object[] arguments = frame == null ? new Object[PhpFunction.ARG_FIRST] : frame.getArguments();
        PhpObject self = method && arguments[PhpFunction.ARG_THIS] instanceof PhpObject object ? object : null;
        String className = null;
        if (method) {
            PhpClass called = self != null ? self.getPhpClass()
                    : arguments[PhpFunction.ARG_STATIC] instanceof PhpClass phpClass ? phpClass : null;
            String lowerName = Str.lower(name);
            for (PhpClass c = called; c != null; c = c.getParent()) {
                PhpFunction declared = c.findMethod(lowerName);
                if (declared != null && declared.getCallTarget() == getCallTarget()) {
                    className = c.getName();
                }
            }
        }
        PhpArray args = new PhpArray();
        for (int i = PhpFunction.ARG_FIRST; i < arguments.length; i++) {
            args.append(PhpRef.deref(arguments[i]));
        }
        Backtrace.describeCall(entry, name, className, self, options, args);
        return true;
    }

    @Override
    public SourceSection getSourceSection() {
        return BUILTINS.createUnavailableSection();
    }

    @Override
    public String getName() {
        return name;
    }
}
