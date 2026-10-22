package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.StmtNode;

/** `throw` as an expression (PHP 8): `$x ?? throw new E()`. */
public final class ThrowExprNode extends ExprNode {
    @Child private StmtNode thrower;

    public ThrowExprNode(StmtNode thrower) {
        this.thrower = thrower;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        thrower.execute(frame);
        throw new IllegalStateException("throw did not throw");
    }
}
