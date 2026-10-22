package pl.codeloom.php.nodes;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.PhpLanguage;

/**
 * A file's top-level code, run in the global scope. include returns the file's `return` value, or 1.
 * Unconditional function and class declarations are hoisted into the prologue by the translator.
 */
public final class FileRootNode extends RootNode {
    private final String path;
    @Child private StmtNode prologue;
    @Child private StmtNode body;

    public FileRootNode(PhpLanguage language, String path, StmtNode prologue, StmtNode body) {
        super(language, FrameDescriptor.newBuilder().build());
        this.path = path;
        this.prologue = prologue;
        this.body = body;
    }

    public String getPath() {
        return path;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        prologue.execute(frame);
        try {
            body.execute(frame);
        } catch (ControlFlow.Return ret) {
            return ret.value;
        }
        return 1L;
    }

    @Override
    public String getName() {
        return path;
    }
}
