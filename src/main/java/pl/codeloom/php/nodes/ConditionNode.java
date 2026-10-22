package pl.codeloom.php.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.ValueProfile;
import pl.codeloom.php.runtime.Convert;

/**
 * A value used as a condition. PHP truthiness depends on the type; the class profile lets a site that always sees
 * one type (nearly all of them) compile to that one test instead of the whole type chain.
 */
public final class ConditionNode extends ExprNode {
    @Child private ExprNode value;
    private final ValueProfile type = ValueProfile.createClassProfile();

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
    public boolean executeBoolean(VirtualFrame frame) {
        return Convert.toBool(type.profile(value.execute(frame)));
    }

    @Override
    public boolean isBooleanValued() {
        return true;
    }
}
