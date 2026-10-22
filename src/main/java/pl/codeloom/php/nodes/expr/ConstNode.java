package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.PhpArray;

/**
 * A compile-time constant: a literal, a folded constant expression, or a constant array. A constant array is
 * built once and held permanently (one extra holder), so every place that stores it copies before writing,
 * like php-src's immutable arrays.
 */
public final class ConstNode extends ExprNode {
    private final Object value;

    public ConstNode(Object value) {
        if (value instanceof PhpArray array) {
            array.addHolder();
        }
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value;
    }

    @Override
    public Object constantValue() {
        return value;
    }
}
