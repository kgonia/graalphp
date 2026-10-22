package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;

/**
 * `[$a, 'k' => $b, [$c]] = $value`: destructuring into places. Positional entries take keys 0, 1, 2… counting
 * skipped entries; targets are places or nested lists (in `nested`, at the same index).
 */
public final class ListAssignNode extends Node {
    @Children private final ExprNode[] keys;
    @Children private final LValueNode[] targets;
    @Children private final ListAssignNode[] nested;

    /** keys[i] null means positional; exactly one of targets[i] and nested[i] is non-null, or both for a gap. */
    public ListAssignNode(ExprNode[] keys, LValueNode[] targets, ListAssignNode[] nested) {
        this.keys = keys;
        this.targets = targets;
        this.nested = nested;
    }

    @ExplodeLoop
    public void assign(VirtualFrame frame, Object value) {
        PhpArray array = value instanceof PhpArray a ? a : null;
        long position = 0;
        for (int i = 0; i < targets.length; i++) {
            Object key = keys[i] == null ? (Object) position++ : ArrayKey.of(keys[i].execute(frame));
            if (targets[i] == null && nested[i] == null) {
                continue;
            }
            Object element = array == null ? null : array.get(key);
            Object item = element == null ? PhpNull.NULL : element;
            if (nested[i] != null) {
                nested[i].assign(frame, item);
            } else {
                targets[i].write(frame, item);
            }
        }
    }
}
