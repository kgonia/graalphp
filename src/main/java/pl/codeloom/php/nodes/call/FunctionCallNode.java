package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpRef;

/**
 * `name(args)`. Functions can't be redeclared, so the first lookup is final and the call is direct, which lets
 * Graal inline the callee.
 */
public final class FunctionCallNode extends ExprNode {
    private final String lowerName;
    private final String displayName;
    @Child private ArgumentsNode args;
    @Child private DirectCallNode call;
    @CompilationFinal private PhpFunction function;

    public FunctionCallNode(String displayName, ArgumentsNode args) {
        this.displayName = displayName;
        this.lowerName = displayName.toLowerCase();
        this.args = args;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (function == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            PhpFunction found = PhpContext.get(this).findFunction(lowerName);
            if (found == null) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Call to undefined function " + displayName + "()");
            }
            call = insert(DirectCallNode.create(found.getCallTarget()));
            function = found;
        }
        Object[] arguments = args.build(frame, function, null, null, null);
        return PhpRef.deref(call.call(arguments));
    }
}
