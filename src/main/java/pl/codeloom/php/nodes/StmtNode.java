package pl.codeloom.php.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.GenerateWrapper;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.SourceLocation;

/**
 * A statement. Statements that come from a source line are instrumentable with {@link StandardTags.StatementTag},
 * so tools like the CPU sampler (--cpusampler.Mode=statements) can attribute time to lines.
 */
@GenerateWrapper
public abstract class StmtNode extends Node implements InstrumentableNode, SourceLocation.Line {
    /** The 1-based source line, or 0 for synthetic statements. */
    private int line;

    public abstract void execute(VirtualFrame frame);

    public void setLine(int line) {
        this.line = line;
    }

    @Override
    public int getLine() {
        return line;
    }

    /**
     * Statement lists call this for a runtime error leaving one of their statements: the innermost statement with a
     * line is where the error surfaced, which its Error object reports as its file and line.
     */
    protected static void surfaced(PhpError error, StmtNode statement) {
        if (statement.line > 0) {
            error.surfacedAt(statement);
        }
    }

    @Override
    public boolean isInstrumentable() {
        return line > 0;
    }

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StandardTags.StatementTag.class;
    }

    @Override
    public WrapperNode createWrapper(ProbeNode probe) {
        return new StmtNodeWrapper(this, probe);
    }

    @Override
    @TruffleBoundary
    public SourceSection getSourceSection() {
        if (line <= 0) {
            return null;
        }
        RootNode root = getRootNode();
        SourceSection rootSection = root == null ? null : root.getSourceSection();
        if (rootSection == null || !rootSection.getSource().hasCharacters()
                || line > rootSection.getSource().getLineCount()) {
            return null;
        }
        return rootSection.getSource().createSection(line);
    }
}
