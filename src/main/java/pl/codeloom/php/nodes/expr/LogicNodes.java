package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import pl.codeloom.php.nodes.ConditionNode;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.PhpNull;

/** Boolean operators, the ternaries and `??`. */
public final class LogicNodes {
    private LogicNodes() {
    }

    /** `&&` / `and`, or `||` / `or` with isOr. */
    public static final class AndOrNode extends ExprNode {
        @Override
        public boolean isBooleanValued() {
            return true;
        }

        @Child private ExprNode left;
        @Child private ExprNode right;
        private final boolean isOr;
        private final CountingConditionProfile shortCircuit = CountingConditionProfile.create();

        public AndOrNode(ExprNode left, ExprNode right, boolean isOr) {
            this.left = ConditionNode.of(left);
            this.right = ConditionNode.of(right);
            this.isOr = isOr;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeBoolean(frame);
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) {
            boolean first = left.executeBoolean(frame);
            if (shortCircuit.profile(first == isOr)) {
                return first;
            }
            return right.executeBoolean(frame);
        }
    }

    public static final class XorNode extends ExprNode {
        @Override
        public boolean isBooleanValued() {
            return true;
        }

        @Child private ExprNode left;
        @Child private ExprNode right;

        public XorNode(ExprNode left, ExprNode right) {
            this.left = ConditionNode.of(left);
            this.right = ConditionNode.of(right);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeBoolean(frame);
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) {
            return left.executeBoolean(frame) ^ right.executeBoolean(frame);
        }
    }

    public static final class NotNode extends ExprNode {
        @Override
        public boolean isBooleanValued() {
            return true;
        }

        @Child private ExprNode operand;

        public NotNode(ExprNode operand) {
            this.operand = ConditionNode.of(operand);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeBoolean(frame);
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) {
            return !operand.executeBoolean(frame);
        }
    }

    /** `cond ? a : b`, and `a ?: b` when then is null. */
    public static final class TernaryNode extends ExprNode {
        @Child private ExprNode condition;
        @Child private ExprNode then;
        @Child private ExprNode otherwise;
        private final CountingConditionProfile profile = CountingConditionProfile.create();

        public TernaryNode(ExprNode condition, ExprNode then, ExprNode otherwise) {
            this.condition = ConditionNode.of(condition);
            this.then = then;
            this.otherwise = otherwise;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (then == null) {
                Object value = condition.execute(frame);
                return profile.profile(pl.codeloom.php.runtime.Convert.toBool(value)) ? value : otherwise.execute(frame);
            }
            return profile.profile(condition.executeBoolean(frame)) ? then.execute(frame) : otherwise.execute(frame);
        }
    }

    /** `a ?? b`: a if it is set and not null. Places are tested with isset() so missing keys don't count. */
    public static final class CoalesceNode extends ExprNode {
        @Child private ExprNode left;
        @Child private ExprNode right;
        private final CountingConditionProfile profile = CountingConditionProfile.create();

        public CoalesceNode(ExprNode left, ExprNode right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object value = left instanceof LValueNode place ? place.readIfSet(frame) : left.execute(frame);
            return profile.profile(value != null && value != PhpNull.NULL) ? value : right.execute(frame);
        }
    }
}
