package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.Str;

/**
 * Comparison operators, specialized on operand types: a site compiles the type pairs it has met. Everything else
 * goes through php-src's zend_compare rules in {@link Convert}. An int meets a float as a float (the implicit
 * cast), which is PHP 8's rule for `==` and `<`, but not for `===`, which has no float specialization.
 */
public final class CompareNodes {
    private CompareNodes() {
    }

    @NodeChild("left")
    @NodeChild("right")
    public abstract static class CompareNode extends ExprNode {
        @Override
        public boolean isBooleanValued() {
            return true;
        }
    }

    /** `===`; {@link #create} also makes `!==`. */
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class IdenticalNode extends CompareNode {
        public static CompareNode create(ExprNode left, ExprNode right, boolean negate) {
            return negate ? CompareNodesFactory.NotIdenticalNodeGen.create(left, right)
                    : CompareNodesFactory.IdenticalNodeGen.create(left, right);
        }

        @Specialization
        public static boolean longs(long a, long b) {
            return a == b;
        }

        @Specialization
        public static boolean strings(TruffleString a, TruffleString b, @Cached TruffleString.EqualNode equal) {
            return equal.execute(a, b, Str.ENC);
        }

        @Specialization
        public static boolean booleans(boolean a, boolean b) {
            return a == b;
        }

        @Fallback
        public static boolean other(Object a, Object b) {
            return identical(a, b);
        }

        static boolean identical(Object a, Object b) {
            if (a instanceof Long x) {
                return b instanceof Long y && x.longValue() == y.longValue();
            }
            if (a instanceof TruffleString x) {
                return b instanceof TruffleString y && x.equals(y);
            }
            if (a instanceof Boolean x) {
                return b instanceof Boolean y && x.booleanValue() == y.booleanValue();
            }
            if (a == PhpNull.NULL || b == PhpNull.NULL) {
                return a == b;
            }
            if (a instanceof Double x) {
                return b instanceof Double y && x.doubleValue() == y.doubleValue();
            }
            return Convert.strictEquals(a, b);
        }
    }

    /** `!==`. */
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class NotIdenticalNode extends CompareNode {
        @Specialization
        public static boolean longs(long a, long b) {
            return a != b;
        }

        @Specialization
        public static boolean strings(TruffleString a, TruffleString b, @Cached TruffleString.EqualNode equal) {
            return !equal.execute(a, b, Str.ENC);
        }

        @Specialization
        public static boolean booleans(boolean a, boolean b) {
            return a != b;
        }

        @Fallback
        public static boolean other(Object a, Object b) {
            return !IdenticalNode.identical(a, b);
        }
    }

    /** `==`; {@link #create} also makes `!=`. */
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class EqualNode extends CompareNode {
        public static CompareNode create(ExprNode left, ExprNode right, boolean negate) {
            return negate ? CompareNodesFactory.NotEqualNodeGen.create(left, right)
                    : CompareNodesFactory.EqualNodeGen.create(left, right);
        }

        /** `==` on values already evaluated (switch cases): use {@link #executeEquals}, not execute. */
        public static EqualNode createEvaluated() {
            return CompareNodesFactory.EqualNodeGen.create(null, null);
        }

        /** The comparison of two evaluated operands. */
        public abstract boolean executeEquals(Object left, Object right);

        @Specialization
        public static boolean longs(long a, long b) {
            return a == b;
        }

        @Specialization
        public static boolean doubles(double a, double b) {
            return a == b;
        }

        @Specialization
        public static boolean strings(TruffleString a, TruffleString b, @Cached TruffleString.EqualNode equal,
                                      @Cached TruffleString.ReadByteNode readByte) {
            return equalStrings(a, b, equal, readByte);
        }

        @Specialization
        public static boolean booleans(boolean a, boolean b) {
            return a == b;
        }

        @Fallback
        public static boolean other(Object a, Object b) {
            return Convert.looseEquals(a, b);
        }

        static boolean equalStrings(TruffleString a, TruffleString b, TruffleString.EqualNode equal,
                                    TruffleString.ReadByteNode readByte) {
            return equal.execute(a, b, Str.ENC)
                    || mayBeNumeric(a, readByte) && mayBeNumeric(b, readByte) && Convert.looseEquals(a, b);
        }

        /** Cheap pre-check: a numeric string starts with whitespace, a sign, a digit or a dot. */
        private static boolean mayBeNumeric(TruffleString s, TruffleString.ReadByteNode readByte) {
            if (s.byteLength(Str.ENC) == 0) {
                return false;
            }
            int first = readByte.execute(s, 0, Str.ENC);
            return first >= '0' && first <= '9' || first == '-' || first == '+' || first == '.' || first == ' '
                    || first == '\t' || first == '\n' || first == '\r' || first == 0x0B || first == '\f';
        }
    }

    /** `!=`. */
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class NotEqualNode extends CompareNode {
        @Specialization
        public static boolean longs(long a, long b) {
            return a != b;
        }

        @Specialization
        public static boolean doubles(double a, double b) {
            return a != b;
        }

        @Specialization
        public static boolean strings(TruffleString a, TruffleString b, @Cached TruffleString.EqualNode equal,
                                      @Cached TruffleString.ReadByteNode readByte) {
            return !EqualNode.equalStrings(a, b, equal, readByte);
        }

        @Specialization
        public static boolean booleans(boolean a, boolean b) {
            return a != b;
        }

        @Fallback
        public static boolean other(Object a, Object b) {
            return !Convert.looseEquals(a, b);
        }
    }

    /**
     * `<`; {@link #create} also makes `<=` (orEqual) and, swapped, `>` and `>=`, which php-src compiles as
     * `right < left` and `right <= left`. Operands still evaluate left first.
     */
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class LessNode extends CompareNode {
        public static CompareNode create(ExprNode left, ExprNode right, boolean orEqual, boolean swapped) {
            if (swapped) {
                return orEqual ? CompareNodesFactory.GreaterEqualNodeGen.create(left, right)
                        : CompareNodesFactory.GreaterNodeGen.create(left, right);
            }
            return orEqual ? CompareNodesFactory.LessEqualNodeGen.create(left, right)
                    : CompareNodesFactory.LessNodeGen.create(left, right);
        }

        @Specialization
        public static boolean longs(long a, long b) {
            return a < b;
        }

        @Specialization
        public static boolean doubles(double a, double b) {
            return a < b;
        }

        @Fallback
        public static boolean other(Object a, Object b) {
            return Convert.compare(a, b) < 0;
        }
    }

    /** `<=`. */
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class LessEqualNode extends CompareNode {
        @Specialization
        public static boolean longs(long a, long b) {
            return a <= b;
        }

        @Specialization
        public static boolean doubles(double a, double b) {
            return a <= b;
        }

        @Fallback
        public static boolean other(Object a, Object b) {
            return Convert.compare(a, b) <= 0;
        }
    }

    /** `>`: `right < left`. */
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class GreaterNode extends CompareNode {
        @Specialization
        public static boolean longs(long a, long b) {
            return b < a;
        }

        @Specialization
        public static boolean doubles(double a, double b) {
            return b < a;
        }

        @Fallback
        public static boolean other(Object a, Object b) {
            return Convert.compare(b, a) < 0;
        }
    }

    /** `>=`: `right <= left`. */
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class GreaterEqualNode extends CompareNode {
        @Specialization
        public static boolean longs(long a, long b) {
            return b <= a;
        }

        @Specialization
        public static boolean doubles(double a, double b) {
            return b <= a;
        }

        @Fallback
        public static boolean other(Object a, Object b) {
            return Convert.compare(b, a) <= 0;
        }
    }

    /** `<=>`. */
    @NodeChild("left")
    @NodeChild("right")
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class SpaceshipNode extends ExprNode {
        public static SpaceshipNode create(ExprNode left, ExprNode right) {
            return CompareNodesFactory.SpaceshipNodeGen.create(left, right);
        }

        @Specialization
        public static long longs(long a, long b) {
            return Long.compare(a, b);
        }

        @Fallback
        public static long other(Object a, Object b) {
            return Convert.compare(a, b);
        }
    }
}
