package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpException;
import pl.codeloom.php.runtime.PhpThrowable;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;

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

    /**
     * finally runs when the try (or a catch) completes, throws, or leaves by return, break, continue or goto, as
     * in php-src; not while exit() or a cancelled context unwinds (the polyglot exit and cancel exceptions are
     * neither PHP exceptions nor control flow), where php-src runs no finally either. An exception the finally block
     * throws while another one unwinds takes that one as its previous; a return, break, continue or goto out of the
     * finally block drops it.
     */
    @Override
    public void execute(VirtualFrame frame) {
        if (finallyBody == null) {
            tryCatch(frame);
            return;
        }
        try {
            tryCatch(frame);
        } catch (ControlFlowException | PhpThrowable leaving) {
            try {
                finallyBody.execute(frame);
            } catch (PhpThrowable thrown) {
                if (leaving instanceof PhpThrowable pending) {
                    setPrevious(PhpContext.get(this), thrown, pending);
                }
                throw thrown;
            }
            throw leaving;
        }
        finallyBody.execute(frame);
    }

    private void tryCatch(VirtualFrame frame) {
        try {
            body.execute(frame);
        } catch (PhpException e) {
            handle(frame, e, e.getThrowable());
        } catch (PhpError e) {
            handle(frame, e, e.toThrowable(PhpContext.get(this)));
        }
    }

    @ExplodeLoop
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

    /**
     * zend_exception_set_previous: pending goes at the end of thrown's getPrevious() chain, unless thrown is already
     * in pending's chain or pending in thrown's (a cycle).
     */
    @TruffleBoundary
    static void setPrevious(PhpContext context, PhpThrowable thrown, PhpThrowable pending) {
        PhpObject exception = thrown.toThrowable(context);
        PhpObject previous = pending.toThrowable(context);
        if (exception == previous) {
            return;
        }
        PhpObject current = exception;
        do {
            for (Object ancestor = previousOf(previous); ancestor instanceof PhpObject object;
                 ancestor = previousOf(object)) {
                if (object == current) {
                    return;
                }
            }
            if (!(previousOf(current) instanceof PhpObject next)) {
                current.setProperty("previous", previous);
                return;
            }
            current = next;
        } while (current != previous);
    }

    private static Object previousOf(PhpObject exception) {
        return PhpRef.deref(exception.getProperty("previous"));
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
}
