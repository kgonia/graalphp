package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.builtins.Ini;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Operators;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.Str;

/** Unary minus/plus, bitwise not, and casts, specialized on the operand's type. */
public final class UnaryNodes {
    private UnaryNodes() {
    }

    /**
     * `@expr` (ZEND_BEGIN_SILENCE / END_SILENCE): while it runs, error_reporting keeps only the fatal errors, so
     * the others aren't displayed or logged (error_get_last() and a user error handler still see them); it is
     * restored however the expression ends, unless the expression changed it.
     */
    public static final class SilenceNode {
        /** BEGIN_SILENCE: error_reporting keeps only the fatal errors; the value before, for {@link #end}. */
        public static int begin(Ini ini) {
            int saved = ini.errorReporting();
            if (!onlyFatal(saved)) {
                ini.setErrorReporting(saved & ErrorFunctions.E_FATAL_ERRORS);
            }
            return saved;
        }

        /** END_SILENCE: the value before back, unless the expression set one reporting more than fatal errors. */
        public static void end(Ini ini, int saved) {
            if (onlyFatal(ini.errorReporting()) && !onlyFatal(saved)) {
                ini.setErrorReporting(saved);
            }
        }

        private static boolean onlyFatal(int mask) {
            return (mask & ~ErrorFunctions.E_FATAL_ERRORS) == 0;
        }
    }

    @NodeChild("operand")
    public abstract static class UnaryNode extends ExprNode {
    }

    /** `-x`. PHP_INT_MIN has no int negation: it takes the float specialization (an int widens to a float). */
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class NegateNode extends UnaryNode {
        public static NegateNode create(ExprNode operand) {
            return UnaryNodesFactory.NegateNodeGen.create(operand);
        }

        @Specialization(guards = "value != MIN")
        public static long negate(long value) {
            return -value;
        }

        @Specialization
        public static double negate(double value) {
            return -value;
        }

        @Fallback
        public static Object other(Object value, @Bind Node node) {
            return Operators.negate(node, value);
        }

        public static final long MIN = Long.MIN_VALUE;
    }

    /** `+x`: the operand as a number. */
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class PlusNode extends UnaryNode {
        public static PlusNode create(ExprNode operand) {
            return UnaryNodesFactory.PlusNodeGen.create(operand);
        }

        @Specialization
        public static long number(long value) {
            return value;
        }

        @Specialization
        public static double number(double value) {
            return value;
        }

        @Fallback
        public static Object other(Object value) {
            return Convert.toNumber(value);
        }
    }

    /** `~x`. */
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class BitNotNode extends UnaryNode {
        public static BitNotNode create(ExprNode operand) {
            return UnaryNodesFactory.BitNotNodeGen.create(operand);
        }

        @Specialization
        public static long not(long value) {
            return ~value;
        }

        @Fallback
        public static Object other(Object value, @Bind Node node) {
            return Operators.bitNot(node, value);
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
    }

    /** `(int)`; the bytecode interpreter proxies it (CastLong), as the casts below. */
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class ToLongNode extends UnaryNode {
        @Specialization
        public static long same(long value) {
            return value;
        }

        @Specialization
        public static long bool(boolean value) {
            return value ? 1 : 0;
        }

        @Fallback
        public static long other(Object value) {
            return Convert.toLong(value);
        }
    }

    /** `(float)`: an int widens through the implicit cast. */
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class ToDoubleNode extends UnaryNode {
        @Specialization
        public static double same(double value) {
            return value;
        }

        @Fallback
        public static double other(Object value) {
            return Convert.toDouble(value);
        }
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class ToStringNode extends UnaryNode {
        @Specialization
        public static TruffleString same(TruffleString value) {
            return value;
        }

        @Specialization
        public static TruffleString number(long value, @Cached TruffleString.FromLongNode fromLong) {
            return fromLong.execute(value, Str.ENC, true);
        }

        @Fallback
        public static TruffleString other(Object value, @Bind Node node) {
            return Convert.toStr(node, value);
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
                    return value instanceof PhpArray ? value : toArray(this, value);
                case CastNode.TO_OBJECT:
                    return value instanceof PhpObject ? value : toObject(this, value);
                default:
                    return PhpNull.NULL;
            }
        }

        /**
         * `(array)` of a value that isn't an array: an object's properties become its property table's entries, under
         * their mangled names; null an empty array, anything else a one-element list.
         */
        @TruffleBoundary
        public static PhpArray toArray(Node site, Object value) {
            if (value == PhpNull.NULL) {
                return new PhpArray();
            }
            if (value instanceof PhpObject object) {
                return object.toArray();
            }
            if (value instanceof PhpClosure) {
                return PhpArray.list(value);
            }
            return PhpArray.list(value);
        }

        /** `(object)` of a value that isn't an object: a stdClass of an array's elements, or of a scalar as $scalar. */
        public static PhpObject toObject(Node site, Object value) {
            PhpObject object = PhpContext.get(site).getClass("stdClass").instantiate();
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
                object.setProperty(Str.key(Convert.toStr(array.keyAt(p))), value);
            }
        }
    }
}
