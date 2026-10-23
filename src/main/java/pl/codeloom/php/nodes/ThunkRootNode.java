package pl.codeloom.php.nodes;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.ClassTemplate;
import pl.codeloom.php.runtime.PhpClass;

/**
 * A constant expression (class constant, property default) that doesn't fold. Language-level: each context's class
 * binds it to itself ({@link #bind}), and the binding evaluates it on first use with that class as `static`.
 */
public final class ThunkRootNode extends RootNode implements ClassTemplate.Deferred {
    @Child private ExprNode expression;

    public ThunkRootNode(PhpLanguage language, ExprNode expression) {
        super(language, FrameDescriptor.newBuilder().build());
        this.expression = expression;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return expression.execute(frame);
    }

    @Override
    public PhpClass.Thunk bind(PhpClass staticClass) {
        return () -> getCallTarget().call(null, staticClass, null);
    }
}
