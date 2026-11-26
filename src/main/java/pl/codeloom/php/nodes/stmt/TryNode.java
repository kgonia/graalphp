package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpThrowable;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;

/**
 * try / catch / finally. Runtime errors (PhpError) are offered to catch clauses as PHP Error objects, so
 * `catch (Error $e)` and `catch (Throwable $e)` see them.
 */
public final class TryNode {
    /**
     * zend_exception_set_previous: pending goes at the end of thrown's getPrevious() chain, unless thrown is already
     * in pending's chain or pending in thrown's (a cycle).
     */
    @TruffleBoundary
    public static void setPrevious(PhpContext context, PhpThrowable thrown, PhpThrowable pending) {
        PhpObject exception = thrown.toThrowable(context);
        PhpObject previous = pending.toThrowable(context);
        PhpObject tail = pl.codeloom.php.ir.PhpExceptionChain.appendPoint(exception, previous, TryNode::previousOf);
        if (tail != null) tail.setProperty("previous", previous);
    }

    private static PhpObject previousOf(PhpObject exception) {
        return PhpRef.deref(exception.getProperty("previous")) instanceof PhpObject obj ? obj : null;
    }

    @TruffleBoundary
    public static boolean matches(PhpObject thrown, String[] types) {
        for (String type : types) {
            if (thrown.getPhpClass().isSubclassOf(type)) {
                return true;
            }
        }
        return false;
    }
}
