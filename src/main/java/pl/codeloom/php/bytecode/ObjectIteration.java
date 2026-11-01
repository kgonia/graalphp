package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.nodes.stmt.ForeachNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

import java.util.List;

/**
 * foreach over an object, one element per step (ForeachNode.iterateObject as a loop the bytecode drives): an
 * Iterator through its methods, called from the loop's instruction, or the public properties the scope may access,
 * each as it is when the loop reaches it.
 */
final class ObjectIteration {
    /** The Iterator, or null for a walk over properties. */
    private final PhpObject iterator;
    private final PhpObject object;
    private final List<String> properties;
    private boolean started;
    private int index;

    private ObjectIteration(PhpObject iterator, PhpObject object, List<String> properties) {
        this.iterator = iterator;
        this.object = object;
        this.properties = properties;
    }

    /** The iteration of subject for code whose calling scope is scope: an Iterator is rewound here. */
    @TruffleBoundary
    static ObjectIteration start(Node site, PhpObject subject, String scope) {
        PhpObject object = ForeachNode.unwrapAggregates(site, subject);
        if (object.getPhpClass().isSubclassOf("iterator")) {
            ForeachNode.step(site, object, "rewind");
            return new ObjectIteration(object, null, null);
        }
        return new ObjectIteration(null, object, object.visiblePropertyNames(scope));
    }

    /** Binds the next element's key (hasKey) and value; false at the end. */
    boolean next(VirtualFrame frame, LocalAccessor value, LocalAccessor key, boolean hasKey, BytecodeNode bytecode,
                 Node site) {
        if (iterator != null) {
            if (started) {
                ForeachNode.step(site, iterator, "next");
            }
            started = true;
            if (!Convert.toBool(ForeachNode.step(site, iterator, "valid"))) {
                return false;
            }
            Object current = PhpRef.deref(ForeachNode.step(site, iterator, "current"));
            if (hasKey) {
                Locals.write(frame, key, bytecode, PhpRef.deref(ForeachNode.step(site, iterator, "key")));
            }
            Locals.write(frame, value, bytecode, current);
            return true;
        }
        Object[] property = nextProperty();
        if (property == null) {
            return false;
        }
        if (hasKey) {
            Locals.write(frame, key, bytecode, property[0]);
        }
        Locals.write(frame, value, bytecode, property[1]);
        return true;
    }

    /** The next property still set, as its key and value; null past the last. */
    @TruffleBoundary
    private Object[] nextProperty() {
        while (index < properties.size()) {
            String name = properties.get(index++);
            Object current = object.getProperty(name);
            if (current != null) {
                return new Object[] {Str.ofKey(name), PhpRef.deref(current)};
            }
        }
        return null;
    }
}
