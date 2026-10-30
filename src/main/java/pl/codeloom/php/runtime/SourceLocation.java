package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.BytecodeLocation;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Where PHP code is, as errors and exceptions report it: a file and the line of the statement. php-src reports
 * the opline's line, which is the statement's unless the statement spans lines. A function on bytecode has no lines
 * in its instructions: each statement is a source section, built when a location is first asked for
 * ({@link BytecodeLocation#ensureSourceInformation}), and the innermost section around an instruction is its
 * statement.
 */
public record SourceLocation(String file, int line) {
    /** php-src's location when no PHP code runs. */
    public static final SourceLocation UNKNOWN = new SourceLocation("Unknown", 0);

    /** The root of PHP code (a file's, a function's): the file it is in. */
    public interface Code {
        String getPath();
    }

    /** A node that starts a source line: a statement; 0 for synthetic ones. */
    public interface Line {
        int getLine();
    }

    /**
     * User code (php-src's ZEND_USER_CODE): PHP code that isn't internal. The prelude's (built-in classes written in
     * PHP) stands for php-src's internal code: locations and traces skip it as php-src skips internal frames.
     */
    public static boolean isUserCode(RootNode root) {
        return root instanceof Code && !root.isInternal();
    }

    /** The innermost statement around the node, in its root's file; for the prelude's code, as current(). */
    @TruffleBoundary
    public static SourceLocation of(Node node) {
        RootNode root = node.getRootNode();
        if (!isUserCode(root)) {
            return current();
        }
        if (root instanceof BytecodeRootNode) {
            return new SourceLocation(((Code) root).getPath(), lineOf(bytecodeLocation(node, root)));
        }
        int line = 0;
        for (Node n = node; n != null; n = n.getParent()) {
            if (n instanceof Line statement && statement.getLine() > 0) {
                line = statement.getLine();
                break;
            }
        }
        return new SourceLocation(((Code) root).getPath(), line);
    }

    /**
     * An instruction of a function on bytecode: its file and the line of the innermost statement around it (0 when
     * it is in none, as in the prologue); an instruction of internal code (the prelude) is placed where user code
     * runs now (current()).
     */
    @TruffleBoundary
    public static SourceLocation of(BytecodeLocation location) {
        RootNode root = (RootNode) location.getBytecodeNode().getBytecodeRootNode();
        if (!isUserCode(root)) {
            return current();
        }
        return new SourceLocation(((Code) root).getPath(), lineOf(location));
    }

    /** An instruction of a function on bytecode in one of its statements: that statement; else null. */
    @TruffleBoundary
    public static SourceLocation ofStatement(BytecodeLocation location) {
        int line = lineOf(location);
        return line > 0 ? new SourceLocation(((Code) location.getBytecodeNode().getBytecodeRootNode()).getPath(), line)
                : null;
    }

    /** Where a call in a frame of user code was made: by its node, or by its instruction on bytecode. */
    @TruffleBoundary
    public static SourceLocation ofCall(RootNode root, Node call, BytecodeLocation instruction) {
        if (instruction != null) {
            return new SourceLocation(((Code) root).getPath(), lineOf(instruction));
        }
        return call != null ? of(call) : new SourceLocation(((Code) root).getPath(), 0);
    }

    /** The line of the innermost statement around an instruction, 0 if none is known. */
    private static int lineOf(BytecodeLocation location) {
        if (location == null) {
            return 0;
        }
        SourceSection section = location.ensureSourceInformation().getSourceLocation();
        return section == null || !section.isAvailable() ? 0 : section.getStartLine();
    }

    /**
     * The instruction a node of a bytecode root runs for: by the node itself when it is an instruction's own
     * (cached) node, else (in the uncached interpreter) by the bytecode index the innermost activation of the root
     * on this thread has stored.
     */
    private static BytecodeLocation bytecodeLocation(Node node, RootNode root) {
        BytecodeNode bytecode = BytecodeNode.get(node);
        Frame frame = Truffle.getRuntime().iterateFrames(instance -> instance.getCallTarget() == root.getCallTarget()
                ? instance.getFrame(FrameInstance.FrameAccess.READ_ONLY) : null);
        return bytecode == null || frame == null ? null : bytecode.getBytecodeLocation(frame, node);
    }

    /** The instruction of a bytecode root a frame (not the innermost) is calling from, else null. */
    @TruffleBoundary
    public static BytecodeLocation callingInstruction(FrameInstance frame) {
        RootNode root = ((RootCallTarget) frame.getCallTarget()).getRootNode();
        return root instanceof BytecodeRootNode ? BytecodeLocation.get(frame) : null;
    }

    /**
     * The user code running now, as seen from a built-in: the call in the nearest frame of user code (php-src's
     * zend_get_executed_lineno, which skips internal frames).
     */
    @TruffleBoundary
    public static SourceLocation current() {
        Object[] found = Truffle.getRuntime().iterateFrames(frame -> {
            RootNode root = ((RootCallTarget) frame.getCallTarget()).getRootNode();
            if (!isUserCode(root)) {
                return null;
            }
            // the line is looked up after the walk: a bytecode root may need its sources built first
            return new Object[] {root, frame.getCallNode(), callingInstruction(frame)};
        });
        return found == null ? UNKNOWN : ofCall((RootNode) found[0], (Node) found[1], (BytecodeLocation) found[2]);
    }
}
