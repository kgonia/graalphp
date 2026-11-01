package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.bytecode.ForceQuickening;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.nodes.PhpTypes;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Str;

/**
 * The comparison operators for the bytecode interpreter's operations ({@link OperationProxy}): one class per
 * operator, since a proxied node has no fields to tell `<` from `<=`. The specializations are {@link CompareNodes}'
 * and call its rules, with the same type system (an int meets a float as a float); the tree keeps CompareNodes'
 * classes (see there why). The int and float specializations are {@link ForceQuickening}: without it the DSL's
 * quickenings pair ints with floats (Less$Longs$Doubles, operands boxed), so `$i < $n` read both variables boxed.
 */
public final class Comparisons {
    private Comparisons() {
    }

    /** `===`. */
    @OperationProxy.Proxyable(allowUncached = true)
    @TypeSystemReference(PhpTypes.class)
    public abstract static class Identical extends Node {
        public abstract boolean execute(Object left, Object right);

        @ForceQuickening
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
            return CompareNodes.IdenticalNode.identical(a, b);
        }
    }

    /** `!==`. */
    @OperationProxy.Proxyable(allowUncached = true)
    @TypeSystemReference(PhpTypes.class)
    public abstract static class NotIdentical extends Node {
        public abstract boolean execute(Object left, Object right);

        @ForceQuickening
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
            return !CompareNodes.IdenticalNode.identical(a, b);
        }
    }

    /** `==`. */
    @OperationProxy.Proxyable(allowUncached = true)
    @TypeSystemReference(PhpTypes.class)
    public abstract static class Equal extends Node {
        public abstract boolean execute(Object left, Object right);

        @ForceQuickening
        @Specialization
        public static boolean longs(long a, long b) {
            return a == b;
        }

        @ForceQuickening
        @Specialization
        public static boolean doubles(double a, double b) {
            return a == b;
        }

        @Specialization
        public static boolean strings(TruffleString a, TruffleString b, @Cached TruffleString.EqualNode equal,
                                      @Cached TruffleString.ReadByteNode readByte) {
            return CompareNodes.EqualNode.equalStrings(a, b, equal, readByte);
        }

        @Specialization
        public static boolean booleans(boolean a, boolean b) {
            return a == b;
        }

        @Fallback
        public static boolean other(Object a, Object b) {
            return Convert.looseEquals(a, b);
        }
    }

    /** `!=`. */
    @OperationProxy.Proxyable(allowUncached = true)
    @TypeSystemReference(PhpTypes.class)
    public abstract static class NotEqual extends Node {
        public abstract boolean execute(Object left, Object right);

        @ForceQuickening
        @Specialization
        public static boolean longs(long a, long b) {
            return a != b;
        }

        @ForceQuickening
        @Specialization
        public static boolean doubles(double a, double b) {
            return a != b;
        }

        @Specialization
        public static boolean strings(TruffleString a, TruffleString b, @Cached TruffleString.EqualNode equal,
                                      @Cached TruffleString.ReadByteNode readByte) {
            return !CompareNodes.EqualNode.equalStrings(a, b, equal, readByte);
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

    /** `<`. */
    @OperationProxy.Proxyable(allowUncached = true)
    @TypeSystemReference(PhpTypes.class)
    public abstract static class Less extends Node {
        public abstract boolean execute(Object left, Object right);

        @ForceQuickening
        @Specialization
        public static boolean longs(long a, long b) {
            return a < b;
        }

        @ForceQuickening
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
    @TypeSystemReference(PhpTypes.class)
    public abstract static class LessEqual extends Node {
        public abstract boolean execute(Object left, Object right);

        @ForceQuickening
        @Specialization
        public static boolean longs(long a, long b) {
            return a <= b;
        }

        @ForceQuickening
        @Specialization
        public static boolean doubles(double a, double b) {
            return a <= b;
        }

        @Fallback
        public static boolean other(Object a, Object b) {
            return Convert.compare(a, b) <= 0;
        }
    }

    /** `>`, which php-src compiles as `right < left` (the operands still evaluate left first). */
    @OperationProxy.Proxyable(allowUncached = true)
    @TypeSystemReference(PhpTypes.class)
    public abstract static class Greater extends Node {
        public abstract boolean execute(Object left, Object right);

        @ForceQuickening
        @Specialization
        public static boolean longs(long a, long b) {
            return b < a;
        }

        @ForceQuickening
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
    @TypeSystemReference(PhpTypes.class)
    public abstract static class GreaterEqual extends Node {
        public abstract boolean execute(Object left, Object right);

        @ForceQuickening
        @Specialization
        public static boolean longs(long a, long b) {
            return b <= a;
        }

        @ForceQuickening
        @Specialization
        public static boolean doubles(double a, double b) {
            return b <= a;
        }

        @Fallback
        public static boolean other(Object a, Object b) {
            return Convert.compare(b, a) <= 0;
        }
    }
}
