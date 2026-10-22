package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;

/** $this, passed as the first frame argument. */
public final class ThisNode extends ExprNode {
    @Override
    public Object execute(VirtualFrame frame) {
        Object self = frame.getArguments()[PhpFunction.ARG_THIS];
        if (self == null) {
            throw new PhpError("Using $this when not in object context");
        }
        return self;
    }
}
