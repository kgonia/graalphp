package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives;
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
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Using $this when not in object context");
        }
        return self;
    }

    /** isset($this): whether the code runs on an object (false in a static method or a plain function). */
    public static final class IsSetNode extends ExprNode {
        @Override
        public Object execute(VirtualFrame frame) {
            return frame.getArguments()[PhpFunction.ARG_THIS] != null;
        }

        @Override
        public boolean isBooleanValued() {
            return true;
        }
    }
}
