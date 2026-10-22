package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.builtins.Builtin;

/**
 * A built-in function's call target. The body is a constant per root, so Graal inlines it into callers like
 * any other function; bodies that do heavy byte work hide behind their own TruffleBoundary.
 */
public final class BuiltinRootNode extends RootNode {
    private final String name;
    private final Builtin body;

    public BuiltinRootNode(PhpLanguage language, String name, Builtin body) {
        super(language);
        this.name = name;
        this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return body.call(frame.getArguments());
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
