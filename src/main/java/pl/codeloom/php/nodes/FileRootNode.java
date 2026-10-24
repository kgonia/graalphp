package pl.codeloom.php.nodes;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.SourceLocation;

/**
 * A file's top-level code, run in the global scope. include returns the file's `return` value, or 1.
 * Unconditional function and class declarations are hoisted into the prologue by the translator.
 */
public final class FileRootNode extends RootNode implements SourceLocation.Code, Backtrace.Callee {
    private final String path;
    @Child private StmtNode prologue;
    @Child private StmtNode body;

    private final SourceSection sourceSection;

    public FileRootNode(PhpLanguage language, FrameDescriptor frame, String path, StmtNode prologue, StmtNode body,
                        SourceSection sourceSection) {
        super(language, frame);
        this.sourceSection = sourceSection;
        this.path = path;
        this.prologue = prologue;
        this.body = body;
    }

    @Override
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

    /** An included file is listed as its include; the script itself is {main}, and the prelude's aren't. */
    @Override
    public boolean describe(Frame frame, PhpArray entry, int options, Backtrace.CallSite site) {
        String kind = site == null ? null : site.includeKind();
        if (kind == null) {
            return false;
        }
        Backtrace.describeInclude(entry, kind, path, options);
        return true;
    }

    @Override
    public SourceSection getSourceSection() {
        return sourceSection;
    }

    @Override
    public String getName() {
        return path;
    }
}
