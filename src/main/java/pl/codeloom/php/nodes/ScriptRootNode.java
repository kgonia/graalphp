package pl.codeloom.php.nodes;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.runtime.PhpBailout;
import pl.codeloom.php.runtime.PhpThrowable;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;

import java.util.concurrent.locks.Lock;

/**
 * The entry script: calls its file with the standard frame arguments (no $this, no static class, no closure), then
 * ends it as php-src does: an uncaught exception goes to the exception handler or is a fatal error, and the
 * shutdown functions and the output's end follow.
 * Parsing may happen without a context, so the file is loaded when the script first runs; translations are
 * language-level, so the call is direct for every context.
 */
public final class ScriptRootNode extends RootNode {
    private final String path;
    @Child private DirectCallNode file;

    public ScriptRootNode(PhpLanguage language, String path) {
        super(language, FrameDescriptor.newBuilder().build());
        this.path = path;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpContext context = PhpContext.get(this);
        PhpThrowable startupError = context.builtinState().takeStartupError();
        if (startupError != null) {
            // raised while the SAPI registered the request's variables (filter.default): the script doesn't run
            ErrorFunctions.uncaught(context, startupError);
            context.endScript();
            return PhpNull.NULL;
        }
        DirectCallNode call = file;
        if (call == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            try {
                call = fileCall();
            } catch (PhpBailout ended) {
                // a compile error in the script (zend_compile's E_COMPILE_ERROR), reported where it was raised
                context.endScript();
                return PhpNull.NULL;
            }
        }
        Object result = PhpNull.NULL;
        try {
            result = call.call(new Object[PhpFunction.ARG_FIRST]);
        } catch (PhpThrowable e) {
            ErrorFunctions.uncaught(context, e);
        } catch (PhpBailout ended) {
            // the fatal error was reported where it was raised
        }
        context.endScript();
        return result;
    }

    /** Inserted under the AST lock: contexts on several threads may run the script first at the same time. */
    private DirectCallNode fileCall() {
        Lock lock = getLock();
        lock.lock();
        try {
            if (file == null) {
                file = insert(DirectCallNode.create(PhpContext.get(this).loadFile(path).getCallTarget()));
            }
            return file;
        } finally {
            lock.unlock();
        }
    }
}
