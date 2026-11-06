package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.nodes.stmt.ForeachNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;


/** `[k => v, v2, &$ref, ...$spread]` with at least one non-constant element (constant ones fold to ConstNode). */
public final class ArrayLiteralNode {

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
