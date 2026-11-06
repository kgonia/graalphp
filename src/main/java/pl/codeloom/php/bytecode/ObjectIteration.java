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
import pl.codeloom.php.runtime.Refcount;
import pl.codeloom.php.runtime.Str;

import java.util.List;

/**
 * foreach over an object, one element per step (ForeachNode.iterateObject as a loop the bytecode drives): an
 * Iterator through its methods, called from the loop's instruction, or the public properties the scope may access,
 * each as it is when the loop reaches it.
 */
final class ObjectIteration implements Refcount.Holder {
    /** The Iterator, or null for a walk over properties. */
    private final PhpObject iterator;
    private final PhpObject object;
    private final List<String> properties;
    private final String scope;
    private boolean started;
    private int index;
    /**
     * What the loop holds a count of until it ends ({@link #end}): the object it was given (FE_RESET's copy), or the
     * iterator an IteratorAggregate's getIterator() returned (that call's own count).
     */
    private PhpObject held;

    private ObjectIteration(PhpObject iterator, PhpObject object, List<String> properties, String scope,
                            PhpObject held) {
        this.iterator = iterator;
        this.object = object;
        this.properties = properties;
        this.scope = scope;
        this.held = held;
    }

    @Override
    public Object held() {
        return held;
    }

    /** The loop ends however it ends (FE_FREE): it lets go of what it held. */
    void end() {
        PhpObject was = held;
        held = null;
        Refcount.dec(was);
    }

    /** The iteration of subject for code whose calling scope is scope: an Iterator is rewound here. */
    @TruffleBoundary
    static ObjectIteration start(Node site, PhpObject subject, String scope) {
        PhpObject unwrapped = ForeachNode.unwrapAggregates(site, subject);
        if (unwrapped == subject) {
            subject.addRef();
        }
        PhpObject object = unwrapped.initialized();
        if (object.getPhpClass().isSubclassOf("iterator")) {
            try {
                ForeachNode.step(site, object, "rewind");
            } catch (RuntimeException e) {
                // FE_RESET threw: its result is freed
                Refcount.dec(unwrapped);
                throw e;
            }
            return new ObjectIteration(object, null, null, null, unwrapped);
        }
        return new ObjectIteration(null, object, object.visiblePropertyNames(scope), scope, unwrapped);
    }

    /** Binds the next element's key (hasKey) and value; false at the end. */
    boolean next(VirtualFrame frame, LocalAccessor value, LocalAccessor key, boolean hasKey, BytecodeNode bytecode,
                 Node site, Locals.Writes keyWrites, Locals.Writes valueWrites) {
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
                Object k = PhpRef.deref(ForeachNode.step(site, iterator, "key"));
                keyWrites.write(frame, key, bytecode, k);
                Refcount.dec(k);
            }
            valueWrites.write(frame, value, bytecode, current);
            // the calls' own counts: the variables counted theirs
            Refcount.dec(current);
            return true;
        }
        Object[] property = nextProperty();
        if (property == null) {
            return false;
        }
        if (hasKey) {
            keyWrites.write(frame, key, bytecode, property[0]);
        }
        valueWrites.write(frame, value, bytecode, property[1]);
        return true;
    }

    /** The next property still set, as its key and value; null past the last. */
    @TruffleBoundary
    private Object[] nextProperty() {
        String name;
        while ((name = object.visiblePropertyAt(properties, index++, scope)) != null) {
            Object current = object.visibleValue(name);
            if (current != null) {
                return new Object[] {Str.ofKey(name), current};
            }
        }
        return null;
    }
}
