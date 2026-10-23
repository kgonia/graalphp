package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.ToBooleanNode;
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
            return executeCondition(frame);
        }

        @Override
        @ExplodeLoop
        public boolean executeCondition(VirtualFrame frame) {
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
        @Child private ToBooleanNode toBoolean = ToBooleanNode.create();

        public EmptyNode(ExprNode operand) {
            this.operand = operand;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeCondition(frame);
        }

        @Override
        public boolean executeCondition(VirtualFrame frame) {
            if (operand instanceof LValueNode place) {
                Object value = place.readIfSet(frame);
                return value == null || value == PhpNull.NULL || !toBoolean.execute(value);
            }
            return !toBoolean.execute(operand.execute(frame));
        }
    }

    /** `$x instanceof Name`; the class need not be loaded (an unknown class matches nothing). */
    /** `$x instanceof $class`: the class by name (a string) or as an object's class. */
    public static final class DynamicInstanceofNode extends ExprNode {
        @Override
        public boolean isBooleanValued() {
            return true;
        }

        @Child private ExprNode operand;
        @Child private ExprNode classValue;

        public DynamicInstanceofNode(ExprNode operand, ExprNode classValue) {
            this.operand = operand;
            this.classValue = classValue;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeCondition(frame);
        }

        @Override
        public boolean executeCondition(VirtualFrame frame) {
            Object value = operand.execute(frame);
            Object target = classValue.execute(frame);
            return test(value, target);
        }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        private static boolean test(Object value, Object target) {
            String lowerClassName;
            if (target instanceof PhpObject object) {
                lowerClassName = object.getPhpClass().getLowerName();
            } else if (target instanceof com.oracle.truffle.api.strings.TruffleString name) {
                String text = name.toJavaStringUncached();
                lowerClassName = (text.startsWith("\\") ? text.substring(1) : text).toLowerCase();
            } else {
                throw new pl.codeloom.php.runtime.PhpError("Class name must be a valid object or a string");
            }
            if (value instanceof PhpObject object) {
                return object.getPhpClass().isSubclassOf(lowerClassName);
            }
            return value instanceof PhpClosure && lowerClassName.equals("closure");
        }
    }

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
            return executeCondition(frame);
        }

        @Override
        public boolean executeCondition(VirtualFrame frame) {
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
