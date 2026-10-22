package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Operators;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/** Unary minus/plus, bitwise not, and casts. */
public final class UnaryNodes {
    private UnaryNodes() {
    }

    public static final class NegateNode extends ExprNode {
        @Child private ExprNode operand;

        public NegateNode(ExprNode operand) {
            this.operand = operand;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object value = operand.execute(frame);
            if (value instanceof Long l && l != Long.MIN_VALUE) {
                return -l;
            }
            if (value instanceof Double d) {
                return -d;
            }
            return Operators.negate(value);
        }
    }

    public static final class PlusNode extends ExprNode {
        @Child private ExprNode operand;

        public PlusNode(ExprNode operand) {
            this.operand = operand;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return Convert.toNumber(operand.execute(frame));
        }
    }

    public static final class BitNotNode extends ExprNode {
        @Child private ExprNode operand;

        public BitNotNode(ExprNode operand) {
            this.operand = operand;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return ~Convert.toLong(operand.execute(frame));
        }
    }

    /** `(int)`, `(float)`, `(string)`, `(bool)`, `(array)`, `(object)`, `(unset)`; type uses php-ast TYPE_* flags. */
    public static final class CastNode extends ExprNode {
        public static final int TO_NULL = 1;
        public static final int TO_LONG = 4;
        public static final int TO_DOUBLE = 5;
        public static final int TO_STRING = 6;
        public static final int TO_ARRAY = 7;
        public static final int TO_OBJECT = 8;
        public static final int TO_BOOL = 18;

        @Child private ExprNode operand;
        private final int type;

        public CastNode(ExprNode operand, int type) {
            this.operand = operand;
            this.type = type;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object value = operand.execute(frame);
            switch (type) {
                case TO_LONG:
                    return Convert.toLong(value);
                case TO_DOUBLE:
                    return Convert.toDouble(value);
                case TO_STRING:
                    return Convert.toStr(value);
                case TO_BOOL:
                    return Convert.toBool(value);
                case TO_ARRAY:
                    return value instanceof PhpArray ? value : toArray(value);
                case TO_OBJECT:
                    return value instanceof PhpObject ? value : toObject(value);
                default:
                    return PhpNull.NULL;
            }
        }

        @TruffleBoundary
        private static PhpArray toArray(Object value) {
            if (value == PhpNull.NULL) {
                return new PhpArray();
            }
            if (value instanceof PhpObject object) {
                PhpArray array = new PhpArray();
                for (Object name : object.propertyNames()) {
                    array.put(pl.codeloom.php.runtime.ArrayKey.of(Str.of((String) name)),
                            PhpRef.deref(object.getProperty((String) name)));
                }
                return array;
            }
            if (value instanceof PhpClosure) {
                return PhpArray.list(value);
            }
            return PhpArray.list(value);
        }

        private Object toObject(Object value) {
            PhpObject object = PhpContext.get(this).getClass("stdClass").instantiate();
            if (value instanceof PhpArray array) {
                fill(object, array);
            } else if (value != PhpNull.NULL) {
                object.setProperty("scalar", value);
            }
            return object;
        }

        @TruffleBoundary
        private static void fill(PhpObject object, PhpArray array) {
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                Object value = array.valueAt(p);
                if (value instanceof PhpArray nested) {
                    nested.addHolder();
                }
                object.setProperty(Convert.toStr(array.keyAt(p)).toJavaStringUncached().intern(), value);
            }
        }
    }
}
