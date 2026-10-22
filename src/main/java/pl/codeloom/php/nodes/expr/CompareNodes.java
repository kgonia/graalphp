package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.Str;

/**
 * Comparison operators. Fast paths cover same-typed ints, floats and strings; everything else goes through
 * php-src's zend_compare rules in {@link Convert}.
 */
public final class CompareNodes {
    private CompareNodes() {
    }

    /** `===`, or `!==` with negate. */
    public static final class IdenticalNode extends ExprNode {
        @Child private ExprNode left;
        @Child private ExprNode right;
        @Child private TruffleString.EqualNode equal = TruffleString.EqualNode.create();
        private final boolean negate;

        public IdenticalNode(ExprNode left, ExprNode right, boolean negate) {
            this.left = left;
            this.right = right;
            this.negate = negate;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeBoolean(frame);
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) {
            return identical(left.execute(frame), right.execute(frame)) != negate;
        }

        private boolean identical(Object a, Object b) {
            if (a instanceof Long x) {
                return b instanceof Long y && x.longValue() == y.longValue();
            }
            if (a instanceof TruffleString x) {
                return b instanceof TruffleString y && equal.execute(x, y, Str.ENC);
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
    public static final class EqualNode extends ExprNode {
        @Child private ExprNode left;
        @Child private ExprNode right;
        @Child private TruffleString.EqualNode equal = TruffleString.EqualNode.create();
        private final boolean negate;

        public EqualNode(ExprNode left, ExprNode right, boolean negate) {
            this.left = left;
            this.right = right;
            this.negate = negate;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeBoolean(frame);
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) {
            return equals(left.execute(frame), right.execute(frame)) != negate;
        }

        private boolean equals(Object a, Object b) {
            if (a instanceof Long x && b instanceof Long y) {
                return x.longValue() == y.longValue();
            }
            if (a instanceof TruffleString x && b instanceof TruffleString y) {
                if (equal.execute(x, y, Str.ENC)) {
                    return true;
                }
                return mayBeNumeric(x) && mayBeNumeric(y) && Convert.looseEquals(x, y);
            }
            if (a instanceof Boolean x && b instanceof Boolean y) {
                return x.booleanValue() == y.booleanValue();
            }
            return Convert.looseEquals(a, b);
        }

        /** Cheap pre-check: a numeric string starts with whitespace, a sign, a digit or a dot. */
        private static boolean mayBeNumeric(TruffleString s) {
            if (s.byteLength(Str.ENC) == 0) {
                return false;
            }
            int first = Str.byteAt(s, 0);
            return first >= '0' && first <= '9' || first == '-' || first == '+' || first == '.' || first == ' '
                    || first == '\t' || first == '\n' || first == '\r' || first == 0x0B || first == '\f';
        }
    }

    /**
     * `<` and `<=` (orEqual); with swapped, `>` and `>=` as `right < left`. Operands still evaluate left first.
     */
    public static final class LessNode extends ExprNode {
        @Child private ExprNode left;
        @Child private ExprNode right;
        private final boolean orEqual;
        private final boolean swapped;

        /** Ints on both sides until shown otherwise: loop conditions compare unboxed. */
        @CompilationFinal private boolean speculateLong = true;

        public LessNode(ExprNode left, ExprNode right, boolean orEqual, boolean swapped) {
            this.left = left;
            this.right = right;
            this.orEqual = orEqual;
            this.swapped = swapped;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeBoolean(frame);
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) {
            if (speculateLong) {
                long first;
                try {
                    first = left.executeLong(frame);
                } catch (UnexpectedResultException e) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    speculateLong = false;
                    return compareGeneric(e.getResult(), right.execute(frame));
                }
                long second;
                try {
                    second = right.executeLong(frame);
                } catch (UnexpectedResultException e) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    speculateLong = false;
                    return compareGeneric(first, e.getResult());
                }
                long a = swapped ? second : first;
                long b = swapped ? first : second;
                return orEqual ? a <= b : a < b;
            }
            return compareGeneric(left.execute(frame), right.execute(frame));
        }

        private boolean compareGeneric(Object first, Object second) {
            Object a = swapped ? second : first;
            Object b = swapped ? first : second;
            if (a instanceof Long x && b instanceof Long y) {
                return orEqual ? x <= y : x < y;
            }
            if (a instanceof Double x && b instanceof Double y) {
                return orEqual ? x <= y : x < y;
            }
            if (a instanceof Long x && b instanceof Double y) {
                return orEqual ? x <= y : x < y;
            }
            if (a instanceof Double x && b instanceof Long y) {
                return orEqual ? x <= y : x < y;
            }
            int c = Convert.compare(a, b);
            return orEqual ? c <= 0 : c < 0;
        }
    }

    /** `<=>`. */
    public static final class SpaceshipNode extends ExprNode {
        @Child private ExprNode left;
        @Child private ExprNode right;

        public SpaceshipNode(ExprNode left, ExprNode right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object a = left.execute(frame);
            Object b = right.execute(frame);
            if (a instanceof Long x && b instanceof Long y) {
                return (long) Long.compare(x, y);
            }
            return (long) Convert.compare(a, b);
        }
    }
}
