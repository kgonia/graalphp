package pl.codeloom.php.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

/** A value used as a condition: PHP truthiness by a {@link ToBooleanNode}, specialized on the value's type. */
public final class ConditionNode extends ExprNode {
    @Child private ExprNode value;
    @Child private ToBooleanNode toBoolean = ToBooleanNode.create();

    private ConditionNode(ExprNode value) {
        this.value = value;
    }

    /** The expression itself when it already yields a boolean (comparisons, isset, logic), else a wrapper. */
    public static ExprNode of(ExprNode value) {
        return value.isBooleanValued() ? value : new ConditionNode(value);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value.execute(frame);
    }

    @Override
    public boolean executeCondition(VirtualFrame frame) {
        return toBoolean.execute(value.execute(frame));
    }

    @Override
    public boolean isBooleanValued() {
        return true;
    }
}
