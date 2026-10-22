package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.api.strings.TruffleStringBuilder;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Str;

/** `"text {$expr} more $var"`: the parts converted to strings and joined. */
public final class InterpolationNode extends ExprNode {
    @Children private final ExprNode[] parts;
    @Child private TruffleStringBuilder.AppendStringNode append = TruffleStringBuilder.AppendStringNode.create();
    @Child private TruffleStringBuilder.ToStringNode toStringNode = TruffleStringBuilder.ToStringNode.create();

    public InterpolationNode(ExprNode[] parts) {
        this.parts = parts;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        TruffleStringBuilder builder = TruffleStringBuilder.create(Str.ENC);
        for (ExprNode part : parts) {
            Object value = part.execute(frame);
            append.execute(builder, value instanceof TruffleString s ? s : Convert.toStr(value));
        }
        return toStringNode.execute(builder);
    }
}
