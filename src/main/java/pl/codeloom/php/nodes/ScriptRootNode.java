package pl.codeloom.php.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.PhpFunction;

/** The entry script: calls its file with the standard frame arguments (no $this, no static class, no closure). */
public final class ScriptRootNode extends RootNode {
    @Child private DirectCallNode file;

    public ScriptRootNode(PhpLanguage language, CallTarget file) {
        super(language, FrameDescriptor.newBuilder().build());
        this.file = DirectCallNode.create(file);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return file.call(new Object[PhpFunction.ARG_FIRST]);
    }
}
