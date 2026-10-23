package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;

/**
 * A compile-time constant: a literal, a folded constant expression, or a constant array. A constant array is
 * built once and frozen ({@link PhpArray#freeze}), so every place that stores it copies before writing, like
 * php-src's immutable arrays, and contexts on several threads share it safely.
 */
public final class ConstNode extends ExprNode {
    private final Object value;
    private final boolean truthy;

    public ConstNode(Object value) {
        if (value instanceof PhpArray array) {
            array.freeze();
        }
        this.value = value;
        this.truthy = Convert.toBool(value);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value;
    }

    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        if (value instanceof Long l) {
            return l;
        }
        throw new UnexpectedResultException(value);
    }

    @Override
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        if (value instanceof Double d) {
            return d;
        }
        throw new UnexpectedResultException(value);
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame) {
        return truthy;
    }

    @Override
    public Object constantValue() {
        return value;
    }
}
