package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
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

    public static final class AssignNode extends ExprNode {
        @Child private LValueNode target;
        @Child private ExprNode value;
        /** For a local or property target: assume the value is an int until it isn't, so it stays unboxed. */
        private final LValueNode local;
        @CompilationFinal private boolean speculateLong = true;

        public AssignNode(LValueNode target, ExprNode value) {
            this.target = target;
            this.value = value;
            this.local = target.hasLongFastPath() ? target : null;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (local != null && speculateLong) {
                try {
                    long result = value.executeLong(frame);
                    local.writeLong(frame, result);
                    return result;
                } catch (UnexpectedResultException e) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    speculateLong = false;
                    target.write(frame, e.getResult());
                    return e.getResult();
                }
            }
            Object result = value.execute(frame);
            target.write(frame, result);
            return result;
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
     * `$x op= value`, with op as a php-ast BINARY_* flag. The place is read and then written, so the translator
     * only builds this for places that are pure (re-evaluating them has no side effects).
     */
    public static final class CompoundAssignNode extends ExprNode {
        public static final int CONCAT = 8;
        public static final int COALESCE = 260;

        @Child private LValueNode target;
        @Child private ExprNode value;
        @Child private TruffleString.ConcatNode concat = TruffleString.ConcatNode.create();
        private final int op;

        /** For a local or property target and an int operator: the unboxed fast path, until a non-int shows up. */
        private final LValueNode local;
        @CompilationFinal private boolean speculateLong;

        public CompoundAssignNode(LValueNode target, ExprNode value, int op) {
            this.target = target;
            this.value = value;
            this.op = op;
            this.local = target.hasLongFastPath() ? target : null;
            this.speculateLong = local != null && (op == 1 || op == 2 || op == 3 || op == 9 || op == 10 || op == 11);
        }

        /**
         * `$local op= int`. php-src evaluates the right side before reading the variable, so this does too. An
         * overflow or a non-int operand or variable leaves the fast path for good.
         */
        private Object executeLocalLong(VirtualFrame frame) {
            long operand;
            try {
                operand = value.executeLong(frame);
            } catch (UnexpectedResultException e) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                speculateLong = false;
                return finish(frame, target.execute(frame), e.getResult());
            }
            long current;
            try {
                current = local.executeLong(frame);
            } catch (UnexpectedResultException e) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                speculateLong = false;
                return finish(frame, e.getResult(), operand);
            }
            try {
                long result = switch (op) {
                    case 1 -> Math.addExact(current, operand);
                    case 2 -> Math.subtractExact(current, operand);
                    case 3 -> Math.multiplyExact(current, operand);
                    case 9 -> current | operand;
                    case 10 -> current & operand;
                    default -> current ^ operand;
                };
                local.writeLong(frame, result);
                return result;
            } catch (ArithmeticException e) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                speculateLong = false;
                return finish(frame, current, operand);
            }
        }

        private Object finish(VirtualFrame frame, Object current, Object operand) {
            Object result = apply(current, operand);
            target.write(frame, result);
            return result;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (local != null && speculateLong) {
                return executeLocalLong(frame);
            }
            if (op == COALESCE) {
                Object current = target.readIfSet(frame);
                if (current != null) {
                    return current;
                }
                Object result = value.execute(frame);
                target.write(frame, result);
                return result;
            }
            Object current = target.execute(frame);
            Object operand = value.execute(frame);
            Object result = apply(current, operand);
            target.write(frame, result);
            return result;
        }

        private Object apply(Object a, Object b) {
            switch (op) {
                case CONCAT:
                    if (a instanceof TruffleString x && b instanceof TruffleString y) {
                        return concat.execute(x, y, Str.ENC, true);
                    }
                    return Str.concat(Convert.toStr(a), Convert.toStr(b));
                case 1:
                    if (a instanceof Long x && b instanceof Long y) {
                        return Operators.addLongs(x, y);
                    }
                    return Operators.add(a, b);
                case 2:
                    if (a instanceof Long x && b instanceof Long y) {
                        return Operators.subLongs(x, y);
                    }
                    return Operators.sub(a, b);
                case 3:
                    return Operators.mul(a, b);
                case 4:
                    return Operators.div(a, b);
                case 5:
                    return Operators.mod(a, b);
                case 12:
                    return Operators.pow(a, b);
                case 9:
                    return Convert.toLong(a) | Convert.toLong(b);
                case 10:
                    return Convert.toLong(a) & Convert.toLong(b);
                case 11:
                    return Convert.toLong(a) ^ Convert.toLong(b);
                case 6:
                    return Operators.shiftLeft(Convert.toLong(a), Convert.toLong(b));
                case 7:
                    return Operators.shiftRight(Convert.toLong(a), Convert.toLong(b));
                default:
                    throw unsupportedOperator(op);
            }
        }
    }

    /** `++$x`, `$x++`, `--$x`, `$x--`. */
    public static final class IncDecNode extends ExprNode {
        @Child private LValueNode target;
        private final boolean increment;
        private final boolean prefix;

        /** For a local or property target: the unboxed int fast path until a non-int or an overflow shows up. */
        private final LValueNode local;
        @CompilationFinal private boolean speculateLong = true;

        public IncDecNode(LValueNode target, boolean increment, boolean prefix) {
            this.target = target;
            this.increment = increment;
            this.prefix = prefix;
            this.local = target.hasLongFastPath() ? target : null;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (local != null && speculateLong) {
                try {
                    long old = local.executeLong(frame);
                    long updated = increment ? Math.addExact(old, 1) : Math.subtractExact(old, 1);
                    local.writeLong(frame, updated);
                    return prefix ? updated : old;
                } catch (UnexpectedResultException | ArithmeticException e) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    speculateLong = false;
                }
            }
            return executeGeneric(frame);
        }

        private Object executeGeneric(VirtualFrame frame) {
            Object old = target.execute(frame);
            Object updated;
            if (old instanceof Long l) {
                updated = increment ? Operators.addLongs(l, 1) : Operators.subLongs(l, 1);
            } else if (old instanceof Double d) {
                updated = increment ? d + 1 : d - 1;
            } else if (old == PhpNull.NULL) {
                updated = increment ? (Object) 1L : PhpNull.NULL;
            } else if (old instanceof TruffleString s && Convert.numericValue(s) != null) {
                Object number = Convert.numericValue(s);
                updated = increment ? Operators.add(number, 1L) : Operators.sub(number, 1L);
            } else if (old instanceof Boolean) {
                updated = old;
            } else {
                throw unsupportedIncrement(old);
            }
            target.write(frame, updated);
            return prefix ? updated : old;
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
    private static PhpError unsupportedOperator(int op) {
        return new PhpError("unsupported compound assignment operator " + op);
    }

    @TruffleBoundary
    private static PhpError unsupportedIncrement(Object value) {
        return new PhpError("Increment/decrement of " + Convert.typeName(value) + " is not supported");
    }
}
