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

    /** The innermost statement around the node, in its root's file. */
    @TruffleBoundary
    public static SourceLocation of(Node node) {
        int line = 0;
        for (Node n = node; n != null; n = n.getParent()) {
            if (n instanceof Line statement && statement.getLine() > 0) {
                line = statement.getLine();
                break;
            }
        }
        return node.getRootNode() instanceof Code code ? new SourceLocation(code.getPath(), line) : UNKNOWN;
    }

    /**
     * The PHP code running now, as seen from a built-in: the call in the nearest frame of PHP code (php-src's
     * zend_get_executed_lineno, which skips internal frames).
     */
    @TruffleBoundary
    public static SourceLocation current() {
        SourceLocation found = Truffle.getRuntime().iterateFrames(frame -> {
            RootNode root = ((RootCallTarget) frame.getCallTarget()).getRootNode();
            if (!(root instanceof Code code)) {
                return null;
            }
            Node call = frame.getCallNode();
            return call != null ? of(call) : new SourceLocation(code.getPath(), 0);
        });
        return found == null ? UNKNOWN : found;
    }
}
