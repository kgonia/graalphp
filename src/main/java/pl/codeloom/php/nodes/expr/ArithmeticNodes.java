package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Operators;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.Str;

/** Arithmetic, bitwise and concatenation operators, specialized on operand types. */
public final class ArithmeticNodes {
    private ArithmeticNodes() {
    }

    @NodeChild("left")
    @NodeChild("right")
    public abstract static class BinaryNode extends ExprNode {
    }

    public abstract static class AddNode extends BinaryNode {
        /** The common case, unboxed; the first overflow (PHP: the result becomes a float) deoptimizes. */
        @Specialization(rewriteOn = ArithmeticException.class)
        protected long addExact(long a, long b) {
            return Math.addExact(a, b);
        }

        @Specialization(replaces = "addExact")
        protected Object add(long a, long b) {
            return Operators.addLongs(a, b);
        }

        @Specialization
        protected double add(double a, double b) {
            return a + b;
        }

        @Specialization
        protected PhpArray add(PhpArray a, PhpArray b) {
            return Operators.union(a, b);
        }

        @Fallback
        protected Object add(Object a, Object b) {
            return Operators.add(a, b);
        }
    }

    public abstract static class SubNode extends BinaryNode {
        /** The common case, unboxed; the first overflow (PHP: the result becomes a float) deoptimizes. */
        @Specialization(rewriteOn = ArithmeticException.class)
        protected long subExact(long a, long b) {
            return Math.subtractExact(a, b);
        }

        @Specialization(replaces = "subExact")
        protected Object sub(long a, long b) {
            return Operators.subLongs(a, b);
        }

        @Specialization
        protected double sub(double a, double b) {
            return a - b;
        }

        @Fallback
        protected Object sub(Object a, Object b) {
            return Operators.sub(a, b);
        }
    }

    public abstract static class MulNode extends BinaryNode {
        /** The common case, unboxed; the first overflow (PHP: the result becomes a float) deoptimizes. */
        @Specialization(rewriteOn = ArithmeticException.class)
        protected long mulExact(long a, long b) {
            return Math.multiplyExact(a, b);
        }

        @Specialization(replaces = "mulExact")
        protected Object mul(long a, long b) {
            return Operators.mulLongs(a, b);
        }

        @Specialization
        protected double mul(double a, double b) {
            return a * b;
        }

        @Fallback
        protected Object mul(Object a, Object b) {
            return Operators.mul(a, b);
        }
    }

    public abstract static class DivNode extends BinaryNode {
        @Specialization
        protected Object div(long a, long b) {
            return Operators.divLongs(a, b);
        }

        @Specialization(guards = "isNonZero(b)")
        protected double div(double a, double b) {
            return a / b;
        }

        protected static boolean isNonZero(double b) {
            return b != 0;
        }

        @Fallback
        protected Object div(Object a, Object b) {
            return Operators.div(a, b);
        }
    }

    public abstract static class ModNode extends BinaryNode {
        @Specialization
        protected long mod(long a, long b) {
            return Operators.modLongs(a, b);
        }

        @Fallback
        protected long mod(Object a, Object b) {
            return Operators.mod(a, b);
        }
    }

    public abstract static class PowNode extends BinaryNode {
        @Specialization
        protected Object pow(Object a, Object b) {
            return Operators.pow(a, b);
        }
    }

    public abstract static class BitAndNode extends BinaryNode {
        @Specialization
        protected long and(long a, long b) {
            return a & b;
        }

        @Fallback
        protected long and(Object a, Object b) {
            return Convert.toLong(a) & Convert.toLong(b);
        }
    }

    public abstract static class BitOrNode extends BinaryNode {
        @Specialization
        protected long or(long a, long b) {
            return a | b;
        }

        @Fallback
        protected long or(Object a, Object b) {
            return Convert.toLong(a) | Convert.toLong(b);
        }
    }

    public abstract static class BitXorNode extends BinaryNode {
        @Specialization
        protected long xor(long a, long b) {
            return a ^ b;
        }

        @Fallback
        protected long xor(Object a, Object b) {
            return Convert.toLong(a) ^ Convert.toLong(b);
        }
    }

    public abstract static class ShiftLeftNode extends BinaryNode {
        @Specialization
        protected long shift(long a, long b) {
            return Operators.shiftLeft(a, b);
        }

        @Fallback
        protected long shift(Object a, Object b) {
            return Operators.shiftLeft(Convert.toLong(a), Convert.toLong(b));
        }
    }

    public abstract static class ShiftRightNode extends BinaryNode {
        @Specialization
        protected long shift(long a, long b) {
            return Operators.shiftRight(a, b);
        }

        @Fallback
        protected long shift(Object a, Object b) {
            return Operators.shiftRight(Convert.toLong(a), Convert.toLong(b));
        }
    }

    /** `.`: lazy concatenation (a rope until someone reads the bytes), with int operands formatted directly. */
    public abstract static class ConcatNode extends BinaryNode {
        @Specialization
        protected TruffleString concat(TruffleString a, TruffleString b,
                                       @Cached TruffleString.ConcatNode concat) {
            return concat.execute(a, b, Str.ENC, true);
        }

        @Specialization
        protected TruffleString concat(TruffleString a, long b,
                                       @Cached TruffleString.FromLongNode fromLong,
                                       @Cached TruffleString.ConcatNode concat) {
            return concat.execute(a, fromLong.execute(b, Str.ENC, true), Str.ENC, true);
        }

        @Specialization
        protected TruffleString concat(long a, TruffleString b,
                                       @Cached TruffleString.FromLongNode fromLong,
                                       @Cached TruffleString.ConcatNode concat) {
            return concat.execute(fromLong.execute(a, Str.ENC, true), b, Str.ENC, true);
        }

        @Fallback
        protected TruffleString concat(Object a, Object b) {
            return Str.concat(Convert.toStr(a), Convert.toStr(b));
        }
    }
}
