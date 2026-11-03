package pl.codeloom.php.nodes;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.stmt.GotoNodes;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.SourceLocation;

/**
 * A file's top-level code, run in the global scope. include returns the file's `return` value, or 1.
 * Unconditional function and class declarations are hoisted into the prologue by the translator.
 */
public final class FileRootNode extends RootNode implements SourceLocation.Code, Backtrace.Callee, LargeCode {

    /** Whether this root is {@link LargeCode large}: 0 not counted yet, 1 no, 2 yes. */
    private byte large;

    @Override
    public boolean isLarge() {
        if (large == 0) {
            large = LargeCode.countLarge(this) ? (byte) 2 : (byte) 1;
        }
        return large == 2;
    }
    /** {@link RunOnceCode#classify}: 0 not yet, 1 compiled as any code, 2 large and without a loop. */
    private volatile byte runOnce;
    /** An include site ran the file twice in one context: it runs a number of times a request that depends on data. */
    private volatile boolean includedAgain;

    private final String path;
    private final boolean internal;
    @Child private StmtNode prologue;
    @Child private StmtNode body;

    private final SourceSection sourceSection;

    public FileRootNode(PhpLanguage language, FrameDescriptor frame, String path, boolean internal,
                        StmtNode prologue, StmtNode body, SourceSection sourceSection) {
        super(language, frame);
        this.sourceSection = sourceSection;
        this.path = path;
        this.internal = internal;
        this.prologue = prologue;
        this.body = body;
    }

    /** An include site ran the file again in a context where it had run it (IncludeNode). */
    public void includedAgain() {
        if (!includedAgain) {
            includedAgain = true;
        }
    }

    /**
     * Large top-level code without a loop that no include site has run twice in one context runs a number of times a
     * request bounded by the program's text, and isn't worth compiling ({@link RunOnceCode}); declining it here makes
     * the compilation a retryable bailout, so it is tried again (and asked again) after Truffle's threshold.
     */
    @Override
    protected boolean prepareForCompilation(boolean rootCompilation, int compilationTier, boolean lastTier) {
        if (includedAgain) {
            return true;
        }
        byte kind = runOnce;
        if (kind == 0) {
            kind = RunOnceCode.classify(this);
            runOnce = kind;
        }
        return kind != 2;
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
        } catch (GotoNodes.Jump jump) {
            // ponytail: the tree's goto unwinds only to a label in a statement list around it, and the file's
            // labels are checked (JumpChecks), so this one is in an if, a try or a catch, where php-src jumps in;
            // the tree goes when top-level code moves to bytecode
            throw new PhpError("Unsupported goto into a statement at a file's top level");
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

    /** The prelude's code: php-src's internal code, which tools and traces leave out. */
    @Override
    public boolean isInternal() {
        return internal;
    }

    @Override
    public String getName() {
        return path;
    }
}
