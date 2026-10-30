package pl.codeloom.php.nodes.expr;

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

    /** `===`, or `!==` with negate. */
    public abstract static class IdenticalNode extends CompareNode {
        private final boolean negate;

        protected IdenticalNode(boolean negate) {
            this.negate = negate;
        }

        public static IdenticalNode create(ExprNode left, ExprNode right, boolean negate) {
            return CompareNodesFactory.IdenticalNodeGen.create(negate, left, right);
        }

        @Specialization
        protected boolean longs(long a, long b) {
            return (a == b) != negate;
        }

        @Specialization
        protected boolean strings(TruffleString a, TruffleString b, @Cached TruffleString.EqualNode equal) {
            return equal.execute(a, b, Str.ENC) != negate;
        }

        @Specialization
        protected boolean booleans(boolean a, boolean b) {
            return (a == b) != negate;
        }

        @Fallback
        protected boolean other(Object a, Object b) {
            return identical(a, b) != negate;
        }

        private static boolean identical(Object a, Object b) {
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

    /** `==`, or `!=` with negate. */
    public abstract static class EqualNode extends CompareNode {
        private final boolean negate;

        protected EqualNode(boolean negate) {
            this.negate = negate;
        }

        public static EqualNode create(ExprNode left, ExprNode right, boolean negate) {
            return CompareNodesFactory.EqualNodeGen.create(negate, left, right);
        }

        /** `==` on values already evaluated (switch cases): use {@link #executeEquals}, not execute. */
        public static EqualNode createEvaluated() {
            return CompareNodesFactory.EqualNodeGen.create(false, null, null);
        }

        /** The comparison of two evaluated operands. */
        public abstract boolean executeEquals(Object left, Object right);

        @Specialization
        protected boolean longs(long a, long b) {
            return (a == b) != negate;
        }

        @Specialization
        protected boolean doubles(double a, double b) {
            return (a == b) != negate;
        }

        @Specialization
        protected boolean strings(TruffleString a, TruffleString b, @Cached TruffleString.EqualNode equal,
                                  @Cached TruffleString.ReadByteNode readByte) {
            boolean result = equal.execute(a, b, Str.ENC)
                    || mayBeNumeric(a, readByte) && mayBeNumeric(b, readByte) && Convert.looseEquals(a, b);
            return result != negate;
        }

        @Specialization
        protected boolean booleans(boolean a, boolean b) {
            return (a == b) != negate;
        }

        @Fallback
        protected boolean other(Object a, Object b) {
            return Convert.looseEquals(a, b) != negate;
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

    /**
     * `<` and `<=` (orEqual); with swapped, `>` and `>=` as `right < left`. Operands still evaluate left first.
     */
    public abstract static class LessNode extends CompareNode {
        private final boolean orEqual;
        private final boolean swapped;

        protected LessNode(boolean orEqual, boolean swapped) {
            this.orEqual = orEqual;
            this.swapped = swapped;
        }

        public static LessNode create(ExprNode left, ExprNode right, boolean orEqual, boolean swapped) {
            return CompareNodesFactory.LessNodeGen.create(orEqual, swapped, left, right);
        }

        @Specialization
        protected boolean longs(long first, long second) {
            long a = swapped ? second : first;
            long b = swapped ? first : second;
            return orEqual ? a <= b : a < b;
        }

        @Specialization
        protected boolean doubles(double first, double second) {
            double a = swapped ? second : first;
            double b = swapped ? first : second;
            return orEqual ? a <= b : a < b;
        }

        @Fallback
        protected boolean other(Object first, Object second) {
            int c = Convert.compare(swapped ? second : first, swapped ? first : second);
            return orEqual ? c <= 0 : c < 0;
        }
    }

    /** `<=>`. */
    @NodeChild("left")
    @NodeChild("right")
    public abstract static class SpaceshipNode extends ExprNode {
        public static SpaceshipNode create(ExprNode left, ExprNode right) {
            return CompareNodesFactory.SpaceshipNodeGen.create(left, right);
        }

        @Specialization
        protected long longs(long a, long b) {
            return Long.compare(a, b);
        }

        @Fallback
        protected long other(Object a, Object b) {
            return Convert.compare(a, b);
        }
    }
}
