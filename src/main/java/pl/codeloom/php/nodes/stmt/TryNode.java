package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpException;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.Str;

/**
 * try / catch / finally. Runtime errors (PhpError) are offered to catch clauses as PHP Error objects, so
 * `catch (Error $e)` and `catch (Throwable $e)` see them.
 */
public final class TryNode extends StmtNode {
    @Child private StmtNode body;
    @CompilationFinal(dimensions = 2) private final String[][] catchTypes;
    @Children private final LValueNode[] catchTargets;
    @Children private final StmtNode[] catchBodies;
    @Child private StmtNode finallyBody;

    /** catchTypes[i] are lowercase class names; catchTargets[i] may be null (catch without variable). */
    public TryNode(StmtNode body, String[][] catchTypes, LValueNode[] catchTargets, StmtNode[] catchBodies,
                   StmtNode finallyBody) {
        this.body = body;
        this.catchTypes = catchTypes;
        this.catchTargets = catchTargets;
        this.catchBodies = catchBodies;
        this.finallyBody = finallyBody;
    }

    @Override
    public void execute(VirtualFrame frame) {
        try {
            body.execute(frame);
        } catch (PhpException e) {
            handle(frame, e, e.getThrowable());
        } catch (PhpError e) {
            handle(frame, e, toErrorObject(PhpContext.get(this), e));
        } finally {
            if (finallyBody != null) {
                finallyBody.execute(frame);
            }
        }
    }

    private void handle(VirtualFrame frame, RuntimeException original, PhpObject thrown) {
        for (int i = 0; i < catchBodies.length; i++) {
            if (matches(thrown, catchTypes[i])) {
                if (catchTargets[i] != null) {
                    catchTargets[i].write(frame, thrown);
                }
                catchBodies[i].execute(frame);
                return;
            }
        }
        throw original;
    }

    @TruffleBoundary
    static boolean matches(PhpObject thrown, String[] types) {
        for (String type : types) {
            if (thrown.getPhpClass().isSubclassOf(type)) {
                return true;
            }
        }
        return false;
    }

    @TruffleBoundary
    static PhpObject toErrorObject(PhpContext context, PhpError error) {
        String message = error.getMessage();
        String className = "Error";
        int colon = message.indexOf(": ");
        if (colon > 0 && message.substring(0, colon).matches("[A-Za-z]+Error")) {
            className = message.substring(0, colon);
            message = message.substring(colon + 2);
        }
        PhpObject object = (context.findClass(className.toLowerCase()) != null
                ? context.getClass(className) : context.getClass("Error")).instantiate();
        object.setProperty("message", Str.of(message));
        return object;
    }
}
