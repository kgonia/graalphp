package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

import pl.codeloom.php.runtime.Operators;
import pl.codeloom.php.runtime.PhpError;

/** Assignment forms. */
public final class AssignNodes {
    private AssignNodes() {
    }

    /**
     * `$target op= value` (the target is pure: the translator moves a key with side effects into a hidden
     * variable). Both the value and the target's current content are DSL children, in php-src's order: the right
     * side first. A site compiles the type pairs it has met: two ints unboxed until an overflow (then PHP's float
     * result comes from longPair), ints and floats mixed as floats, anything else through the generic operators.
     * `??=` is {@link CoalesceAssignNode}: it evaluates its right side only when needed.
     */
    public static final class CompoundAssignNode {
        public static final int CONCAT = 8;
        public static final int COALESCE = 260;

        /** `a op= b`'s result for every operator but `.=` and `??=`; site is where notices and errors are. */
        public static Object apply(Node site, int op, Object a, Object b) {
            switch (op) {
                case 1:
                    if (a instanceof Long x && b instanceof Long y) {
                        return Operators.addLongs(x, y);
                    }
                    return Operators.add(site, a, b);
                case 2:
                    if (a instanceof Long x && b instanceof Long y) {
                        return Operators.subLongs(x, y);
                    }
                    return Operators.sub(site, a, b);
                case 3:
                    return Operators.mul(site, a, b);
                case 4:
                    return Operators.div(site, a, b);
                case 5:
                    return Operators.mod(site, a, b);
                case 12:
                    return Operators.pow(site, a, b);
                case 9:
                    return a instanceof Long x && b instanceof Long y ? (Object) (x | y) : Operators.bitwise(site, "|", a, b);
                case 10:
                    return a instanceof Long x && b instanceof Long y ? (Object) (x & y) : Operators.bitwise(site, "&", a, b);
                case 11:
                    return a instanceof Long x && b instanceof Long y ? (Object) (x ^ y) : Operators.bitwise(site, "^", a, b);
                case 6:
                    return Operators.shift(site, "<<", a, b);
                case 7:
                    return Operators.shift(site, ">>", a, b);
                default:
                    throw unsupportedOperator(op);
            }
        }
    }

    /**
     * `++$x`, `$x++`, `--$x`, `$x--`. The target is the DSL child (it is read first): ints step unboxed until an
     * overflow, which PHP turns into a float, rewrites the node to the generic specialization.
     */
    public static final class IncDecNode {

        /** The value `++` (increment) or `--` makes of old; site is where notices are. */
        public static Object stepped(Node site, Object old, boolean increment) {
            if (old instanceof Long l) {
                return increment ? Operators.addLongs(l, 1) : Operators.subLongs(l, 1);
            }
            if (old instanceof Double d) {
                return increment ? d + 1 : d - 1;
            }
            return Operators.step(site, old, increment);
        }
    }

    @TruffleBoundary
    public static PhpError unsupportedOperator(int op) {
        return new PhpError("unsupported compound assignment operator " + op);
    }

}
