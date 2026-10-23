package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.ToBooleanNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Operators;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/** Unary minus/plus, bitwise not, and casts, specialized on the operand's type. */
public final class UnaryNodes {
    private UnaryNodes() {
    }

    @NodeChild("operand")
    public abstract static class UnaryNode extends ExprNode {
    }

    /** `-x`. PHP_INT_MIN has no int negation: it takes the float specialization (an int widens to a float). */
    public abstract static class NegateNode extends UnaryNode {
        public static NegateNode create(ExprNode operand) {
            return UnaryNodesFactory.NegateNodeGen.create(operand);
        }

        @Specialization(guards = "value != MIN")
        protected long negate(long value) {
            return -value;
        }

        @Specialization
        protected double negate(double value) {
            return -value;
        }

        @Fallback
        protected Object other(Object value) {
            return Operators.negate(value);
        }

        protected static final long MIN = Long.MIN_VALUE;
    }

    /** `+x`: the operand as a number. */
    public abstract static class PlusNode extends UnaryNode {
        public static PlusNode create(ExprNode operand) {
            return UnaryNodesFactory.PlusNodeGen.create(operand);
        }

        @Specialization
        protected long number(long value) {
            return value;
        }

        @Specialization
        protected double number(double value) {
            return value;
        }

        @Fallback
        protected Object other(Object value) {
            return Convert.toNumber(value);
        }
    }

    /** `~x`. */
    public abstract static class BitNotNode extends UnaryNode {
        public static BitNotNode create(ExprNode operand) {
            return UnaryNodesFactory.BitNotNodeGen.create(operand);
        }

        @Specialization
        protected long not(long value) {
            return ~value;
        }

        @Fallback
        protected long other(Object value) {
            return ~Convert.toLong(value);
        }
    }

    /** `(int)`, `(float)`, `(string)`, `(bool)`, `(array)`, `(object)`, `(unset)`; type uses php-ast TYPE_* flags. */
    public static final class CastNode {
        public static final int TO_NULL = 1;
        public static final int TO_LONG = 4;
        public static final int TO_DOUBLE = 5;
        public static final int TO_STRING = 6;
        public static final int TO_ARRAY = 7;
        public static final int TO_OBJECT = 8;
        public static final int TO_BOOL = 18;

        private CastNode() {
        }

        public static ExprNode create(ExprNode operand, int type) {
            return switch (type) {
                case TO_LONG -> UnaryNodesFactory.ToLongNodeGen.create(operand);
                case TO_DOUBLE -> UnaryNodesFactory.ToDoubleNodeGen.create(operand);
                case TO_STRING -> UnaryNodesFactory.ToStringNodeGen.create(operand);
                case TO_BOOL -> UnaryNodesFactory.ToBoolNodeGen.create(operand);
                default -> UnaryNodesFactory.ToOtherNodeGen.create(type, operand);
            };
        }
    }

    public abstract static class ToLongNode extends UnaryNode {
        @Specialization
        protected long same(long value) {
            return value;
        }

        @Specialization
        protected long bool(boolean value) {
            return value ? 1 : 0;
        }

        @Fallback
        protected long other(Object value) {
            return Convert.toLong(value);
        }
    }

    /** `(float)`: an int widens through the implicit cast. */
    public abstract static class ToDoubleNode extends UnaryNode {
        @Specialization
        protected double same(double value) {
            return value;
        }

        @Fallback
        protected double other(Object value) {
            return Convert.toDouble(value);
        }
    }

    public abstract static class ToStringNode extends UnaryNode {
        @Specialization
        protected TruffleString same(TruffleString value) {
            return value;
        }

        @Specialization
        protected TruffleString number(long value, @Cached TruffleString.FromLongNode fromLong) {
            return fromLong.execute(value, Str.ENC, true);
        }

        @Fallback
        protected TruffleString other(Object value) {
            return Convert.toStr(value);
        }
    }

    public abstract static class ToBoolNode extends UnaryNode {
        @Override
        public boolean isBooleanValued() {
            return true;
        }

        @Specialization
        protected boolean cast(Object value, @Cached ToBooleanNode toBoolean) {
            return toBoolean.execute(value);
        }
    }

    /** `(array)`, `(object)` and `(unset)`. */
    public abstract static class ToOtherNode extends UnaryNode {
        private final int type;

        protected ToOtherNode(int type) {
            this.type = type;
        }

        @Specialization
        protected Object cast(Object value) {
            switch (type) {
                case CastNode.TO_ARRAY:
                    return value instanceof PhpArray ? value : toArray(value);
                case CastNode.TO_OBJECT:
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
