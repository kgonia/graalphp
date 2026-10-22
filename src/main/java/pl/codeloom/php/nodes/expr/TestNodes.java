package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.profiles.ValueProfile;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;

/** isset(), empty() and instanceof. */
public final class TestNodes {
    private TestNodes() {
    }

    public static final class IssetNode extends ExprNode {
        @Override
        public boolean isBooleanValued() {
            return true;
        }

        @Children private final LValueNode[] places;

        public IssetNode(LValueNode[] places) {
            this.places = places;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeBoolean(frame);
        }

        @Override
        @ExplodeLoop
        public boolean executeBoolean(VirtualFrame frame) {
            for (LValueNode place : places) {
                if (!place.isSet(frame)) {
                    return false;
                }
            }
            return true;
        }
    }

    public static final class EmptyNode extends ExprNode {
        @Override
        public boolean isBooleanValued() {
            return true;
        }

        @Child private ExprNode operand;
        /** Truthiness depends on the type; a site that sees one type compiles to that one test. */
        private final ValueProfile type = ValueProfile.createClassProfile();

        public EmptyNode(ExprNode operand) {
            this.operand = operand;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeBoolean(frame);
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) {
            if (operand instanceof LValueNode place) {
                Object value = place.readIfSet(frame);
                return value == null || value == PhpNull.NULL || !Convert.toBool(type.profile(value));
            }
            return !Convert.toBool(type.profile(operand.execute(frame)));
        }
    }

    /** `$x instanceof Name`; the class need not be loaded (an unknown class matches nothing). */
    public static final class InstanceofNode extends ExprNode {
        @Override
        public boolean isBooleanValued() {
            return true;
        }

        @Child private ExprNode operand;
        private final String lowerClassName;

        public InstanceofNode(ExprNode operand, String lowerClassName) {
            this.operand = operand;
            this.lowerClassName = lowerClassName;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeBoolean(frame);
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) {
            Object value = operand.execute(frame);
            if (value instanceof PhpObject object) {
                return object.getPhpClass().isSubclassOf(lowerClassName);
            }
            if (value instanceof PhpClosure) {
                return lowerClassName.equals("closure");
            }
            return false;
        }
    }
}
