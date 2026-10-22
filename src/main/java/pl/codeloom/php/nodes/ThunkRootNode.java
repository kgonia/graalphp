package pl.codeloom.php.nodes;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.PhpClass;

/** Evaluates a constant expression (class constant, property default) on demand: a {@link PhpClass.Thunk}. */
public final class ThunkRootNode extends RootNode implements PhpClass.Thunk {
    @Child private ExprNode expression;
    private final PhpClass staticClass;

    public ThunkRootNode(PhpLanguage language, ExprNode expression, PhpClass staticClass) {
        super(language, FrameDescriptor.newBuilder().build());
        this.expression = expression;
        this.staticClass = staticClass;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return expression.execute(frame);
    }

    @Override
    public Object evaluate() {
        return getCallTarget().call(null, staticClass, null);
    }
}
