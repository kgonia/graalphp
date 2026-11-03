package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.nodes.stmt.ForeachNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpRef;

import java.util.HashSet;
import java.util.Set;

/** `[k => v, v2, &$ref, ...$spread]` with at least one non-constant element (constant ones fold to ConstNode). */
public final class ArrayLiteralNode extends ExprNode {
    /** Element kinds. */
    public static final byte VALUE = 0;
    public static final byte REF = 1;
    public static final byte SPREAD = 2;

    @Children private final ExprNode[] keys;
    @Children private final ExprNode[] values;
    @CompilationFinal(dimensions = 1) private final byte[] kinds;
    /**
     * Whether arrays built here have held anything but ints. Until then they start in the int strategy; after,
     * as an Object[] sized for the elements.
     */
    @CompilationFinal private boolean seenObjects;
    /** The literal keys, normalized once (null where a key isn't a literal int or string). */
    @CompilationFinal(dimensions = 1) private final Object[] constantKeys;
    /**
     * The keys' hashes when the literal is a record: every element has a distinct literal key and one is a string.
     * It is then built as a hash directly, with no rehash and nothing out of line; null otherwise.
     */
    @CompilationFinal(dimensions = 1) private final int[] recordHashes;

    public ArrayLiteralNode(ExprNode[] keys, ExprNode[] values, byte[] kinds) {
        this.keys = keys;
        this.values = values;
        this.kinds = kinds;
        this.constantKeys = new Object[keys.length];
        boolean record = true;
        boolean stringKey = false;
        Set<Object> distinct = new HashSet<>();
        for (int i = 0; i < keys.length; i++) {
            Object constant = keys[i] == null ? null : keys[i].constantValue();
            if (constant instanceof Long || constant instanceof TruffleString) {
                constantKeys[i] = ArrayKey.of(constant);
                stringKey |= constantKeys[i] instanceof TruffleString;
            }
            record &= kinds[i] == VALUE && constantKeys[i] != null && distinct.add(constantKeys[i]);
        }
        if (record && stringKey) {
            recordHashes = new int[keys.length];
            for (int i = 0; i < keys.length; i++) {
                recordHashes[i] = PhpArray.hashOf(constantKeys[i]);
            }
        } else {
            recordHashes = null;
        }
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        if (recordHashes != null) {
            PhpArray record = PhpArray.ofHashCapacity(values.length);
            for (int i = 0; i < values.length; i++) {
                record.addEntry(constantKeys[i], recordHashes[i], values[i].execute(frame));
            }
            return record;
        }
        PhpArray array = seenObjects ? PhpArray.ofCapacity(values.length) : new PhpArray();
        for (int i = 0; i < values.length; i++) {
            if (kinds[i] == SPREAD) {
                spread(array, values[i].execute(frame), this);
                continue;
            }
            Object key = keys[i] == null ? null
                    : constantKeys[i] != null ? constantKeys[i] : ArrayKey.of(keys[i].execute(frame));
            if (kinds[i] == REF) {
                PhpRef ref = ((LValueNode) values[i]).makeRef(frame);
                array.putRef(key == null ? (Object) array.nextIndex() : key, ref);
            } else if (key == null) {
                array.append(values[i].execute(frame));
            } else {
                array.put(key, values[i].execute(frame));
            }
        }
        if (!seenObjects && !array.holdsLongs()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            seenObjects = true;
        }
        return array;
    }

    /**
     * `...$source` into array (ZEND_ADD_ARRAY_UNPACK): its elements, int keys renumbered, string keys kept; a
     * Traversable's as its iterator gives them, a numeric string key renumbered too.
     */
    public static void spread(PhpArray array, Object source, Node site) {
        if (!(source instanceof PhpArray items)) {
            CompilerDirectives.transferToInterpreter();
            spreadTraversable(array, source, site);
            return;
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

    @TruffleBoundary
    private static void spreadTraversable(PhpArray array, Object source, Node site) {
        if (!(source instanceof PhpObject object) || !object.getPhpClass().isSubclassOf("traversable")) {
            // an object that has no iterator: a TypeError, anything else an Error
            throw new PhpError(source instanceof PhpObject ? "TypeError" : "Error",
                    "Only arrays and Traversables can be unpacked, " + Args.valueName(source) + " given");
        }
        ForeachNode.traverse(site, object, (key, value) -> {
            if (!(key instanceof Long) && !(key instanceof TruffleString)) {
                throw new PhpError("Keys must be of type int|string during array unpacking");
            }
            Object normalized = ArrayKey.of(key);
            if (normalized instanceof Long) {
                array.append(value);
            } else {
                array.put(normalized, value);
            }
            return true;
        });
    }
}
