package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Where PHP code is, as errors and exceptions report it: a file and the line of the statement. php-src reports
 * the opline's line, which is the statement's unless the statement spans lines.
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
     * The user code running now, as seen from a built-in: the call in the nearest frame of user code (php-src's
     * zend_get_executed_lineno, which skips internal frames).
     */
    @TruffleBoundary
    public static SourceLocation current() {
        SourceLocation found = Truffle.getRuntime().iterateFrames(frame -> {
            RootNode root = ((RootCallTarget) frame.getCallTarget()).getRootNode();
            if (!isUserCode(root)) {
                return null;
            }
            Node call = frame.getCallNode();
            return call != null ? of(call) : new SourceLocation(((Code) root).getPath(), 0);
        });
        return found == null ? UNKNOWN : found;
    }
}
