package pl.codeloom.php.nodes;

import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import pl.codeloom.php.runtime.Convert;

/** An expression. execute() returns a dereferenced PHP value, never a PhpRef or Java null. */
@TypeSystemReference(PhpTypes.class)
public abstract class ExprNode extends Node {
    public abstract Object execute(VirtualFrame frame);

    public boolean executeBoolean(VirtualFrame frame) {
        return Convert.toBool(execute(frame));
    }

    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object value = execute(frame);
        if (value instanceof Long l) {
            return l;
        }
        throw new UnexpectedResultException(value);
    }

    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        Object value = execute(frame);
        if (value instanceof Double d) {
            return d;
        }
        throw new UnexpectedResultException(value);
    }

    /** Whether executeBoolean is this node's natural result (comparisons, tests, logic): no truthiness needed. */
    public boolean isBooleanValued() {
        return false;
    }

    /** Constant expressions (literals, folded constants) return their value here; others return null. */
    public Object constantValue() {
        return null;
    }
}
