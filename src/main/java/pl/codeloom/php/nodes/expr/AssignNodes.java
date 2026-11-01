package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.ToStrNode;
import pl.codeloom.php.nodes.call.CallNode;
import pl.codeloom.php.nodes.vars.ListAssignNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Operators;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/** Assignment forms. */
public final class AssignNodes {
    private AssignNodes() {
    }

    /**
     * `$target = value`. The value is a DSL child: an int or float is written unboxed (writeLong / writeDouble), and
     * a site that has seen several value types keeps one specialization per type.
     */
    @NodeChild(value = "value", type = ExprNode.class)
    public abstract static class AssignNode extends ExprNode {
        @Child private LValueNode target;

        protected AssignNode(LValueNode target) {
            this.target = target;
        }

        public static AssignNode create(LValueNode target, ExprNode value) {
            return AssignNodesFactory.AssignNodeGen.create(target, value);
        }

        @Specialization
        protected long assignLong(VirtualFrame frame, long value) {
            target.writeLong(frame, value);
            return value;
        }

        @Specialization
        protected double assignDouble(VirtualFrame frame, double value) {
            target.writeDouble(frame, value);
            return value;
        }

        @Specialization(replaces = {"assignLong", "assignDouble"})
        protected Object assign(VirtualFrame frame, Object value) {
            target.write(frame, value);
            return value;
        }
    }

    /** `[$a, $b] = expr`, evaluating to expr. */
    public static final class ListAssignExprNode extends ExprNode {
        @Child private ListAssignNode target;
        @Child private ExprNode value;

        public ListAssignExprNode(ListAssignNode target, ExprNode value) {
            this.target = target;
            this.value = value;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object result = value.execute(frame);
            target.assign(frame, result);
            return result;
        }
    }

    /** `$a = &$b`. */
    public static final class RefAssignNode extends ExprNode {
        @Child private LValueNode target;
        @Child private LValueNode source;

        public RefAssignNode(LValueNode target, LValueNode source) {
            this.target = target;
            this.source = source;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            PhpRef ref = source.makeRef(frame);
            target.bindRef(frame, ref);
            return ref.get();
        }
    }

    /**
     * `$a = &f()`, `$a = &$o->m()` (ASSIGN_REF of a call's result): binds the target to the reference a function
     * returning by reference returned; a value is assigned, with php-src's notice.
     */
    public static final class CallRefAssignNode extends ExprNode {
        @Child private LValueNode target;
        @Child private ExprNode source;

        public CallRefAssignNode(LValueNode target, ExprNode source) {
            this.target = target;
            this.source = source;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object result = source instanceof CallNode call ? call.executeRef(frame) : source.execute(frame);
            if (result instanceof PhpRef ref) {
                target.bindRef(frame, ref);
                return ref.get();
            }
            CallNode.notice(this, CallNode.ASSIGNED_VALUE);
            target.write(frame, result);
            return result;
        }
    }

    /**
     * `$target op= value` (the target is pure: the translator moves a key with side effects into a hidden
     * variable). Both the value and the target's current content are DSL children, in php-src's order: the right
     * side first. A site compiles the type pairs it has met: two ints unboxed until an overflow (then PHP's float
     * result comes from longPair), ints and floats mixed as floats, anything else through the generic operators.
     * `??=` is {@link CoalesceAssignNode}: it evaluates its right side only when needed.
     */
    @NodeChild(value = "value", type = ExprNode.class)
    @NodeChild(value = "target", type = LValueNode.class)
    public abstract static class CompoundAssignNode extends ExprNode {
        public static final int CONCAT = 8;
        public static final int COALESCE = 260;

        private final int op;

        protected CompoundAssignNode(int op) {
            this.op = op;
        }

        public static ExprNode create(LValueNode target, ExprNode value, int op) {
            if (op == COALESCE) {
                return new CoalesceAssignNode(target, value);
            }
            return AssignNodesFactory.CompoundAssignNodeGen.create(op, value, target);
        }

        protected abstract LValueNode getTarget();

        protected boolean isIntOperator() {
            return op == 1 || op == 2 || op == 3 || op == 9 || op == 10 || op == 11;
        }

        protected boolean isFloatOperator() {
            return op == 1 || op == 2 || op == 3 || op == 4;
        }

        protected boolean isConcat() {
            return op == CONCAT;
        }

        /** `.=`: the target's content is converted first, then the value, as php-src's concat_function. */
        @Specialization(guards = "isConcat()")
        protected TruffleString concat(VirtualFrame frame, Object operand, Object current,
                                       @Cached ToStrNode currentToStr,
                                       @Cached ToStrNode operandToStr,
                                       @Cached TruffleString.ConcatNode concat) {
            TruffleString left = currentToStr.execute(current);
            TruffleString result = concat.execute(left, operandToStr.execute(operand), Str.ENC, true);
            getTarget().write(frame, result);
            return result;
        }

        @Specialization(guards = "isIntOperator()", rewriteOn = ArithmeticException.class)
        protected long longs(VirtualFrame frame, long operand, long current) {
            long result = switch (op) {
                case 1 -> Math.addExact(current, operand);
                case 2 -> Math.subtractExact(current, operand);
                case 3 -> Math.multiplyExact(current, operand);
                case 9 -> current | operand;
                case 10 -> current & operand;
                default -> current ^ operand;
            };
            getTarget().writeLong(frame, result);
            return result;
        }

        /**
         * Two ints that longs doesn't take (an overflow, or an operator such as / whose result may be a float).
         * Declared before doubles, which the implicit int-to-float cast would otherwise let take them.
         */
        @Specialization
        protected Object longPair(VirtualFrame frame, long operand, long current) {
            Object result = apply(current, operand);
            getTarget().write(frame, result);
            return result;
        }

        @Specialization(guards = "isFloatOperator()")
        protected double doubles(VirtualFrame frame, double operand, double current) {
            if (op == 4 && operand == 0) {
                CompilerDirectives.transferToInterpreter();
                Operators.div(this, current, operand); // throws DivisionByZeroError
            }
            double result = switch (op) {
                case 1 -> current + operand;
                case 2 -> current - operand;
                case 3 -> current * operand;
                default -> current / operand;
            };
            getTarget().writeDouble(frame, result);
            return result;
        }

        @Specialization(replaces = {"longs", "longPair", "doubles"})
        protected Object generic(VirtualFrame frame, Object operand, Object current) {
            Object result = apply(current, operand);
            getTarget().write(frame, result);
            return result;
        }

        private Object apply(Object a, Object b) {
            return apply(this, op, a, b);
        }

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

    /** `$target ??= value`: the right side is evaluated only when the target is unset or null. */
    public static final class CoalesceAssignNode extends ExprNode {
        @Child private LValueNode target;
        @Child private ExprNode value;

        CoalesceAssignNode(LValueNode target, ExprNode value) {
            this.target = target;
            this.value = value;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object current = target.readIfSet(frame);
            if (current != null) {
                return current;
            }
            Object result = value.execute(frame);
            target.write(frame, result);
            return result;
        }
    }

    /**
     * `++$x`, `$x++`, `--$x`, `$x--`. The target is the DSL child (it is read first): ints step unboxed until an
     * overflow, which PHP turns into a float, rewrites the node to the generic specialization.
     */
    @NodeChild(value = "target", type = LValueNode.class)
    public abstract static class IncDecNode extends ExprNode {
        private final boolean increment;
        private final boolean prefix;

        protected IncDecNode(boolean increment, boolean prefix) {
            this.increment = increment;
            this.prefix = prefix;
        }

        public static IncDecNode create(LValueNode target, boolean increment, boolean prefix) {
            return AssignNodesFactory.IncDecNodeGen.create(increment, prefix, target);
        }

        protected abstract LValueNode getTarget();

        @Specialization(rewriteOn = ArithmeticException.class)
        protected long longs(VirtualFrame frame, long old) {
            long updated = increment ? Math.addExact(old, 1) : Math.subtractExact(old, 1);
            getTarget().writeLong(frame, updated);
            return prefix ? updated : old;
        }

        @Specialization(replaces = "longs")
        protected Object generic(VirtualFrame frame, Object old) {
            Object updated = stepped(this, old, increment);
            getTarget().write(frame, updated);
            return prefix ? updated : old;
        }

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

    /** Evaluates first for its effect, then returns second's value (hoisted temporaries). */
    public static final class SequenceNode extends ExprNode {
        @Child private ExprNode first;
        @Child private ExprNode second;

        public SequenceNode(ExprNode first, ExprNode second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            first.execute(frame);
            return second.execute(frame);
        }
    }

    @TruffleBoundary
    public static PhpError unsupportedOperator(int op) {
        return new PhpError("unsupported compound assignment operator " + op);
    }

}
