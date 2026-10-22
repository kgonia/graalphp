package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;

/**
 * A function's local variable in a frame slot. The slot holds null (unset), a value, or a PhpRef when the
 * variable is a reference (by-ref parameter, `global`, `static`, by-ref capture, `$x = &...`).
 */
public final class LocalVarNode extends LValueNode {
    private final int slot;
    private final String name;

    public LocalVarNode(int slot, String name) {
        this.slot = slot;
        this.name = name;
    }

    public int getSlot() {
        return slot;
    }

    public String getName() {
        return name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = frame.getObject(slot);
        if (value instanceof PhpRef ref) {
            return ref.get();
        }
        return value == null ? PhpNull.NULL : value;
    }

    @Override
    public void write(VirtualFrame frame, Object value) {
        Object old = frame.getObject(slot);
        if (old instanceof PhpRef ref) {
            ref.set(value);
            return;
        }
        if (value instanceof PhpArray array) {
            array.addHolder();
        }
        frame.setObject(slot, value);
        if (old instanceof PhpArray array && old != value) {
            array.removeHolder();
        }
    }

    @Override
    public PhpRef makeRef(VirtualFrame frame) {
        Object value = frame.getObject(slot);
        if (value instanceof PhpRef ref) {
            return ref;
        }
        PhpRef ref = new PhpRef(value == null ? PhpNull.NULL : value);
        if (value instanceof PhpArray array) {
            array.removeHolder();
        }
        frame.setObject(slot, ref);
        return ref;
    }

    @Override
    public void bindRef(VirtualFrame frame, PhpRef ref) {
        Object old = frame.getObject(slot);
        if (old instanceof PhpArray array) {
            array.removeHolder();
        }
        frame.setObject(slot, ref);
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        Object value = frame.getObject(slot);
        if (value instanceof PhpArray array) {
            if (array.isShared()) {
                PhpArray copy = array.copy();
                copy.addHolder();
                array.removeHolder();
                frame.setObject(slot, copy);
                return copy;
            }
            return array;
        }
        if (value instanceof PhpRef ref) {
            return Places.refArrayForWrite(ref);
        }
        if (value == null || value == PhpNull.NULL || value == Boolean.FALSE) {
            PhpArray created = new PhpArray();
            created.addHolder();
            frame.setObject(slot, created);
            return created;
        }
        if (value instanceof PhpObject) {
            return null;
        }
        throw new PhpError("Cannot use a scalar value as an array");
    }

    @Override
    public void unset(VirtualFrame frame) {
        Object old = frame.getObject(slot);
        if (old instanceof PhpArray array) {
            array.removeHolder();
        }
        frame.setObject(slot, null);
    }

    @Override
    public boolean isSet(VirtualFrame frame) {
        Object value = frame.getObject(slot);
        if (value instanceof PhpRef ref) {
            value = ref.get();
        }
        return value != null && value != PhpNull.NULL;
    }

    @Override
    public Object readIfSet(VirtualFrame frame) {
        Object value = frame.getObject(slot);
        if (value instanceof PhpRef ref) {
            value = ref.get();
        }
        return value == PhpNull.NULL ? null : value;
    }

    @Override
    public boolean isPure() {
        return true;
    }
}
