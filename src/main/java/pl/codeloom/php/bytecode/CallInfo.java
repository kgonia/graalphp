package pl.codeloom.php.bytecode;

import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.call.FunctionCallNode;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.PhpFunction;

/**
 * A call by name, `name(args)`: the constant operand of its ResolveFunction and Call instructions. Traces find it
 * in the instruction a frame called from (as they find a tree call's node): a call php-src compiles into an opcode
 * of its own makes no frame.
 */
public final class CallInfo implements Backtrace.CallSite {
    private final String displayName;
    /** The lowercase name's {@link pl.codeloom.php.PhpLanguage#functionId}. */
    private final int id;
    /** An unqualified name in a namespace falls back to the global function: its id, else -1. */
    private final int fallbackId;
    private final boolean frameless;

    public CallInfo(String displayName, int id, int fallbackId, boolean frameless) {
        this.displayName = displayName;
        this.id = id;
        this.fallbackId = fallbackId;
        this.frameless = frameless;
    }

    PhpFunction find(PhpContext context) {
        return FunctionCallNode.find(context, displayName, id, fallbackId);
    }

    @Override
    public boolean isFrameless() {
        return frameless;
    }

    @Override
    public String toString() {
        return displayName + "()";
    }
}
