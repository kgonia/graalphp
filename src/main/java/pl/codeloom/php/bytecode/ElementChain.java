package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.nodes.vars.DimNode;
import pl.codeloom.php.nodes.vars.ElementNodes;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;

/**
 * The containers on the way to a nested element (`$a[k1][k2] = v`, `$o->p[k][] = v`, `unset($a[i][j])`), as the
 * tree's DimNode chain makes them writable (DimNode.arrayForWrite) or reads them (DimNode.execute): a writable
 * array, or the object an element holds (an ArrayAccess object takes the write as offsetSet()).
 */
final class ElementChain {
    private ElementChain() {
    }

    /** The key of an `[]` on the way (`$a[][k] = v`): a new array appended. */
    static final Object APPEND = new Object() {
        @Override
        public String toString() {
            return "[]";
        }
    };

    /**
     * The container one level down for a write (DimNode.arrayForWrite): an array's element made a writable array
     * (copied if shared, created if unset or null), or the object it holds; an ArrayAccess object's offsetGet(),
     * which may not give an array; any other value as the tree reads it (an error for an object that isn't one).
     */
    static Object forWrite(Node node, Object container, Object key, ElementNodes.ArrayKeyNode toKey,
                           ElementNodes.ElementReadNode read) {
        if (container instanceof PhpArray array) {
            if (key == APPEND) {
                PhpArray created = new PhpArray();
                array.append(created);
                return created;
            }
            Object normalized = toKey.execute(key);
            PhpArray nested = array.arrayForWrite(normalized);
            if (nested != null) {
                return nested;
            }
            Object element = PhpRef.deref(array.get(normalized));
            if (element instanceof PhpObject) {
                return element;
            }
            throw scalarAsArray();
        }
        Object offset = key == APPEND ? PhpNull.NULL : key;
        if (container instanceof PhpObject object && object.isArrayAccess()) {
            Object element = PhpRef.deref(object.callMethod(node, "offsetget", offset));
            if (element instanceof PhpArray) {
                throw overloaded();
            }
            return element;
        }
        return read.execute(container, offset);
    }

    /** Down every key but the last (or every key, before an append) of keys, from base. */
    static Object forWrite(Node node, Object base, Object[] keys, int count, ElementNodes.ArrayKeyNode toKey,
                           ElementNodes.ElementReadNode read) {
        Object container = base;
        for (int i = 0; i < count; i++) {
            container = forWrite(node, container, keys[i], toKey, read);
        }
        return container;
    }

    /** `container[key] = value` once the container is writable: an array's element, else offsetSet(). */
    static void write(Node node, Object container, Object key, Object value, ElementNodes.ElementWriteNode write) {
        if (container instanceof PhpArray array) {
            if (key == APPEND) {
                array.append(value);
            } else {
                write.execute(array, key, value);
            }
            return;
        }
        DimNode.writeToObject(node, container, key == APPEND ? PhpNull.NULL : key, value);
    }

    /** The container of an unset's element (DimNode.unset reads the chain): base read down every key but the last. */
    static Object read(Object base, Object[] keys, ElementNodes.ElementReadNode read) {
        Object container = base;
        for (int i = 0; i < keys.length - 1; i++) {
            container = read.execute(container, keys[i]);
        }
        return container;
    }

    /**
     * What `unset(...[key])` does to the container it read (DimNode.unset): an array that has the element gives the
     * normalized key to remove (the chain is then made writable); an ArrayAccess object's offsetUnset() is called;
     * null when there is nothing (more) to do.
     */
    static Object removed(Node node, Object container, Object[] keys, ElementNodes.ArrayKeyNode toKey) {
        Object key = keys[keys.length - 1];
        if (container instanceof PhpArray current) {
            Object normalized = toKey.execute(key);
            return current.containsKey(normalized) ? normalized : null;
        }
        if (container instanceof PhpObject object && object.isArrayAccess()) {
            object.callMethod(node, "offsetunset", key);
        }
        return null;
    }

    /** Removes the element normalized from the chain made writable from base (DimNode.unset's arrayForWrite). */
    static void remove(Node node, Object base, Object[] keys, Object normalized, ElementNodes.ArrayKeyNode toKey,
                       ElementNodes.ElementReadNode read) {
        if (forWrite(node, base, keys, keys.length - 1, toKey, read) instanceof PhpArray array) {
            array.remove(normalized);
        }
    }

    @TruffleBoundary
    static PhpError scalarAsArray() {
        return new PhpError("Cannot use a scalar value as an array");
    }

    @TruffleBoundary
    static PhpError overloaded() {
        return new PhpError("Indirect modification of overloaded element");
    }
}
