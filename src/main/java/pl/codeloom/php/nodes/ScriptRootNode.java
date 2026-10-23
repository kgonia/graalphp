package pl.codeloom.php.nodes;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.PhpFunction;

/**
 * The entry script: calls its file with the standard frame arguments (no $this, no static class, no closure).
 * Parsing may happen without a context, so the file is loaded when the script first runs; translations are
 * language-level, so the call is direct for every context.
 */
public final class ScriptRootNode extends RootNode {
    private final String path;
    @Child private DirectCallNode file;

    public ScriptRootNode(PhpLanguage language, String path) {
        super(language, FrameDescriptor.newBuilder().build());
        this.path = path;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (file == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            file = insert(DirectCallNode.create(PhpContext.get(this).loadFile(path).getCallTarget()));
        }
        return file.call(new Object[PhpFunction.ARG_FIRST]);
    }
}
