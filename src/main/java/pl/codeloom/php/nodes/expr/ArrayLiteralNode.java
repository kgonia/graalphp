package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpRef;

/** `[k => v, v2, &$ref, ...$spread]` with at least one non-constant element (constant ones fold to ConstNode). */
public final class ArrayLiteralNode extends ExprNode {
    /** Element kinds. */
    public static final byte VALUE = 0;
    public static final byte REF = 1;
    public static final byte SPREAD = 2;

    @Children private final ExprNode[] keys;
    @Children private final ExprNode[] values;
    @CompilationFinal(dimensions = 1) private final byte[] kinds;

    public ArrayLiteralNode(ExprNode[] keys, ExprNode[] values, byte[] kinds) {
        this.keys = keys;
        this.values = values;
        this.kinds = kinds;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        PhpArray array = PhpArray.ofCapacity(values.length);
        for (int i = 0; i < values.length; i++) {
            if (kinds[i] == SPREAD) {
                spread(array, values[i].execute(frame));
                continue;
            }
            Object key = keys[i] == null ? null : ArrayKey.of(keys[i].execute(frame));
            if (kinds[i] == REF) {
                PhpRef ref = ((LValueNode) values[i]).makeRef(frame);
                array.putRef(key == null ? (Object) array.nextIndex() : key, ref);
            } else if (key == null) {
                array.append(values[i].execute(frame));
            } else {
                array.put(key, values[i].execute(frame));
            }
        }
        return array;
    }

    private static void spread(PhpArray array, Object source) {
        if (!(source instanceof PhpArray items)) {
            throw new PhpError("Only arrays and Traversables can be unpacked");
        }
        for (int p = items.firstPosition(); p >= 0; p = items.nextPosition(p)) {
            Object key = items.keyAt(p);
            if (key instanceof Long) {
                array.append(items.valueAt(p));
            } else {
                array.put(key, items.valueAt(p));
            }
        }
    }
}
