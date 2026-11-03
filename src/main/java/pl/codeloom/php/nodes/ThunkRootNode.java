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
    /** {@link RunOnceCode#classify}: 0 not yet, 1 compiled as any code, 2 large and without a loop. */
    private volatile byte runOnce;

    public ThunkRootNode(PhpLanguage language, ExprNode expression) {
        super(language, FrameDescriptor.newBuilder().build());
        this.expression = expression;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return expression.execute(frame);
    }

    /**
     * An initializer runs once per class and context: a large one without a loop isn't worth compiling
     * ({@link RunOnceCode}).
     */
    @Override
    protected boolean prepareForCompilation(boolean rootCompilation, int compilationTier, boolean lastTier) {
        byte kind = runOnce;
        if (kind == 0) {
            kind = RunOnceCode.classify(this);
            runOnce = kind;
        }
        return kind != 2;
    }

    @Override
    public PhpClass.Thunk bind(PhpClass staticClass) {
        // the array, not call(Node, Object...): a leading null would be taken for the call location
        return () -> getCallTarget().call(new Object[] {null, staticClass, null});
    }
}
