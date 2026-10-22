package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.builtins.Builtin;

/**
 * A built-in function's call target. The body runs behind a TruffleBoundary: built-ins are plain Java (JDK
 * strings, collections, FFM) that partial evaluation must not expand. Built-ins worth inlining into PHP code
 * become dedicated nodes instead.
 */
public final class BuiltinRootNode extends RootNode {
    /** A placeholder source so profilers and stack traces list built-ins by name. */
    private static final Source BUILTINS = Source.newBuilder(PhpLanguage.ID, "", "<builtin>").build();

    private final String name;
    private final Builtin body;

    public BuiltinRootNode(PhpLanguage language, String name, Builtin body) {
        super(language);
        this.name = name;
        this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return call(body, frame.getArguments());
    }

    @TruffleBoundary
    private static Object call(Builtin body, Object[] arguments) {
        return body.call(arguments);
    }

    @Override
    public SourceSection getSourceSection() {
        return BUILTINS.createUnavailableSection();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isCloningAllowed() {
        return true;
    }
}
