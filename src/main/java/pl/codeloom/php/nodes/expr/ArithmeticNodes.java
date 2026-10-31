package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
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

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class AddNode extends BinaryNode {
        /** The common case, unboxed; the first overflow (PHP: the result becomes a float) deoptimizes. */
        @Specialization(rewriteOn = ArithmeticException.class)
        public static long addExact(long a, long b) {
            return Math.addExact(a, b);
        }

        @Specialization(replaces = "addExact")
        public static Object add(long a, long b) {
            return Operators.addLongs(a, b);
        }

        @Specialization
        public static double add(double a, double b) {
            return a + b;
        }

        @Specialization
        public static PhpArray add(PhpArray a, PhpArray b) {
            return Operators.union(a, b);
        }

        @Fallback
        public static Object add(Object a, Object b, @Bind Node node) {
            return Operators.add(node, a, b);
        }
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class SubNode extends BinaryNode {
        /** The common case, unboxed; the first overflow (PHP: the result becomes a float) deoptimizes. */
        @Specialization(rewriteOn = ArithmeticException.class)
        public static long subExact(long a, long b) {
            return Math.subtractExact(a, b);
        }

        @Specialization(replaces = "subExact")
        public static Object sub(long a, long b) {
            return Operators.subLongs(a, b);
        }

        @Specialization
        public static double sub(double a, double b) {
            return a - b;
        }

        @Fallback
        public static Object sub(Object a, Object b, @Bind Node node) {
            return Operators.sub(node, a, b);
        }
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class MulNode extends BinaryNode {
        /** The common case, unboxed; the first overflow (PHP: the result becomes a float) deoptimizes. */
        @Specialization(rewriteOn = ArithmeticException.class)
        public static long mulExact(long a, long b) {
            return Math.multiplyExact(a, b);
        }

        @Specialization(replaces = "mulExact")
        public static Object mul(long a, long b) {
            return Operators.mulLongs(a, b);
        }

        @Specialization
        public static double mul(double a, double b) {
            return a * b;
        }

        @Fallback
        public static Object mul(Object a, Object b, @Bind Node node) {
            return Operators.mul(node, a, b);
        }
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class DivNode extends BinaryNode {
        @Specialization
        public static Object div(long a, long b) {
            return Operators.divLongs(a, b);
        }

        @Specialization(guards = "isNonZero(b)")
        public static double div(double a, double b) {
            return a / b;
        }

        public static boolean isNonZero(double b) {
            return b != 0;
        }

        @Fallback
        public static Object div(Object a, Object b, @Bind Node node) {
            return Operators.div(node, a, b);
        }
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class ModNode extends BinaryNode {
        @Specialization
        public static long mod(long a, long b) {
            return Operators.modLongs(a, b);
        }

        @Fallback
        public static long mod(Object a, Object b, @Bind Node node) {
            return Operators.mod(node, a, b);
        }
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class PowNode extends BinaryNode {
        @Specialization
        public static Object pow(Object a, Object b, @Bind Node node) {
            return Operators.pow(node, a, b);
        }
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class BitAndNode extends BinaryNode {
        @Specialization
        public static long and(long a, long b) {
            return a & b;
        }

        @Fallback
        public static Object and(Object a, Object b, @Bind Node node) {
            return Operators.bitwise(node, "&", a, b);
        }
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class BitOrNode extends BinaryNode {
        @Specialization
        public static long or(long a, long b) {
            return a | b;
        }

        @Fallback
        public static Object or(Object a, Object b, @Bind Node node) {
            return Operators.bitwise(node, "|", a, b);
        }
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class BitXorNode extends BinaryNode {
        @Specialization
        public static long xor(long a, long b) {
            return a ^ b;
        }

        @Fallback
        public static Object xor(Object a, Object b, @Bind Node node) {
            return Operators.bitwise(node, "^", a, b);
        }
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class ShiftLeftNode extends BinaryNode {
        @Specialization
        public static long shift(long a, long b) {
            return Operators.shiftLeft(a, b);
        }

        @Fallback
        public static long shift(Object a, Object b, @Bind Node node) {
            return Operators.shift(node, "<<", a, b);
        }
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class ShiftRightNode extends BinaryNode {
        @Specialization
        public static long shift(long a, long b) {
            return Operators.shiftRight(a, b);
        }

        @Fallback
        public static long shift(Object a, Object b, @Bind Node node) {
            return Operators.shift(node, ">>", a, b);
        }
    }

    /** `.`: lazy concatenation (a rope until someone reads the bytes), with int operands formatted directly. */
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class ConcatNode extends BinaryNode {
        @Specialization
        public static TruffleString concat(TruffleString a, TruffleString b,
                                       @Cached TruffleString.ConcatNode concat) {
            return concat.execute(a, b, Str.ENC, true);
        }

        @Specialization
        public static TruffleString concat(TruffleString a, long b,
                                       @Cached TruffleString.FromLongNode fromLong,
                                       @Cached TruffleString.ConcatNode concat) {
            return concat.execute(a, fromLong.execute(b, Str.ENC, false), Str.ENC, true);
        }

        @Specialization
        public static TruffleString concat(long a, TruffleString b,
                                       @Cached TruffleString.FromLongNode fromLong,
                                       @Cached TruffleString.ConcatNode concat) {
            return concat.execute(fromLong.execute(a, Str.ENC, false), b, Str.ENC, true);
        }

        @Fallback
        public static TruffleString concat(Object a, Object b) {
            return Str.concat(Convert.toStr(a), Convert.toStr(b));
        }
    }
}
