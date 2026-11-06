package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.Convert;

/**
 * `"text {$expr} more $var"`: the parts converted to strings and joined. Each part is converted as soon as it is
 * evaluated, as php-src's ROPE_ADD does (a __toString() runs before the next part), and the join is one call:
 * inlined, a TruffleStringBuilder's appends and its result cost about 30 IR nodes per part in every compiled
 * function.
 */
public final class InterpolationNode {
    /**
     * A part as it is joined: a string or an int as it is (the join writes its digits), anything else converted.
     * The bytecode interpreter proxies it (InterpolationPart).
     */
    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class PartNode extends Node {
        abstract Object execute(Object value);

        @Specialization
        public static TruffleString string(TruffleString value) {
            return value;
        }

        @Specialization
        public static long number(long value) {
            return value;
        }

        /** Floats, booleans, null, arrays (warning at this part's statement), objects (__toString). */
        @Fallback
        public static TruffleString other(Object value, @Bind Node node) {
            return Convert.toStr(node, value);
        }
    }
}
