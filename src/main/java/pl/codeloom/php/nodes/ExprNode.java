package pl.codeloom.php.nodes;

import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import pl.codeloom.php.runtime.Convert;

/** An expression. execute() returns a dereferenced PHP value, never a PhpRef or Java null. */
@TypeSystemReference(PhpTypes.class)
@ReportPolymorphism
public abstract class ExprNode extends Node {
    public abstract Object execute(VirtualFrame frame);

    /**
     * Typed execute, as the DSL expects of every execute method: the value if it is a boolean, else
     * UnexpectedResultException with the value. A condition's truthiness is {@link #executeCondition}.
     */
    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        Object value = execute(frame);
        if (value instanceof Boolean b) {
            return b;
        }
        throw new UnexpectedResultException(value);
    }

    /**
     * The value as a condition (if, while, &&, !): PHP truthiness. Boolean-valued nodes give their result without
     * boxing; hand-written ones override this.
     */
    public boolean executeCondition(VirtualFrame frame) {
        if (isBooleanValued()) {
            try {
                return executeBoolean(frame);
            } catch (UnexpectedResultException e) {
                return Convert.toBool(e.getResult());
            }
        }
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

    /** Whether the node always yields a boolean (comparisons, tests, logic): conditions need no truthiness. */
    public boolean isBooleanValued() {
        return false;
    }

    /** Constant expressions (literals, folded constants) return their value here; others return null. */
    public Object constantValue() {
        return null;
    }
}
