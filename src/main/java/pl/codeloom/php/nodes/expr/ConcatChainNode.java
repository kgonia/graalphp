package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Str;

/**
 * `a . b . c ...`, three or more operands, or a two-part interpolation (FAST_CONCAT): one join, where a `.` per pair
 * made a call and an intermediate string each
 * (SQL and include paths built in pieces: ABSPATH . WPINC . '/x.php'). Operands are evaluated and converted as
 * php-src's chain of ZEND_CONCAT does it: the first two are evaluated, then converted; each next one is evaluated
 * and converted in turn.
 */
public final class ConcatChainNode extends ExprNode {
    @Children private final ExprNode[] parts;
    @Children private final InterpolationNode.PartNode[] strings;

    public ConcatChainNode(ExprNode[] parts) {
        assert parts.length >= 2;
        this.parts = parts;
        this.strings = new InterpolationNode.PartNode[parts.length];
        for (int i = 0; i < parts.length; i++) {
            strings[i] = InterpolationNodeFactory.PartNodeGen.create();
        }
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object[] values = new Object[parts.length];
        Object first = parts[0].execute(frame);
        Object second = parts[1].execute(frame);
        values[0] = strings[0].execute(first);
        values[1] = strings[1].execute(second);
        for (int i = 2; i < parts.length; i++) {
            values[i] = strings[i].execute(parts[i].execute(frame));
        }
        return Str.join(null, values);
    }
}
