package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.api.strings.TruffleStringBuilder;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Str;

/** `"text {$expr} more $var"`: the parts converted to strings and joined. */
public final class InterpolationNode extends ExprNode {
    @Children private final ExprNode[] parts;
    @Children private final AppendNode[] appends;
    @Child private TruffleStringBuilder.ToStringNode toStringNode = TruffleStringBuilder.ToStringNode.create();

    public InterpolationNode(ExprNode[] parts) {
        this.parts = parts;
        this.appends = new AppendNode[parts.length];
        for (int i = 0; i < parts.length; i++) {
            appends[i] = InterpolationNodeFactory.AppendNodeGen.create();
        }
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        TruffleStringBuilder builder = TruffleStringBuilder.create(Str.ENC);
        for (int i = 0; i < parts.length; i++) {
            appends[i].execute(builder, parts[i].execute(frame));
        }
        return toStringNode.execute(builder);
    }

    /** Appends one part, specialized on its type: an int is written as digits without a string in between. */
    abstract static class AppendNode extends Node {
        abstract void execute(TruffleStringBuilder builder, Object value);

        @Specialization
        protected static void string(TruffleStringBuilder builder, TruffleString value,
                                     @Cached TruffleStringBuilder.AppendStringNode append) {
            append.execute(builder, value);
        }

        @Specialization
        protected static void number(TruffleStringBuilder builder, long value,
                                     @Cached TruffleStringBuilder.AppendLongNumberNode append) {
            append.execute(builder, value);
        }

        /** Floats, booleans, null, objects (__toString): the runtime conversion. */
        @Specialization(guards = {"!isString(value)", "!isLong(value)"})
        protected static void other(TruffleStringBuilder builder, Object value,
                                    @Cached TruffleStringBuilder.AppendStringNode append) {
            append.execute(builder, Convert.toStr(value));
        }

        protected static boolean isString(Object value) {
            return value instanceof TruffleString;
        }

        protected static boolean isLong(Object value) {
            return value instanceof Long;
        }
    }
}
