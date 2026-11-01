package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Str;

/**
 * `"text {$expr} more $var"`: the parts converted to strings and joined. Each part is converted as soon as it is
 * evaluated, as php-src's ROPE_ADD does (a __toString() runs before the next part), and the join is one call:
 * inlined, a TruffleStringBuilder's appends and its result cost about 30 IR nodes per part in every compiled
 * function.
 */
public final class InterpolationNode extends ExprNode {
    @Children private final ExprNode[] parts;
    @Children private final PartNode[] strings;

    public InterpolationNode(ExprNode[] parts) {
        this.parts = parts;
        this.strings = new PartNode[parts.length];
        for (int i = 0; i < parts.length; i++) {
            strings[i] = InterpolationNodeFactory.PartNodeGen.create();
        }
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object[] values = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = strings[i].execute(parts[i].execute(frame));
        }
        return Str.join(null, values);
    }

    /** A part as it is joined: a string or an int as it is (the join writes its digits), anything else converted. */
    abstract static class PartNode extends Node {
        abstract Object execute(Object value);

        @Specialization
        protected static TruffleString string(TruffleString value) {
            return value;
        }

        @Specialization
        protected static long number(long value) {
            return value;
        }

        /** Floats, booleans, null, arrays, objects (__toString): the runtime conversion. */
        @Fallback
        protected static TruffleString other(Object value) {
            return Convert.toStr(value);
        }
    }
}
