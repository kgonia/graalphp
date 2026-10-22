package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;

/**
 * A function's local variable in a frame slot. An int or float is stored unboxed (a long or double slot tag),
 * so loop counters and arithmetic stay primitive in compiled code; anything else is an Object slot holding null
 * (unset), a value, or a PhpRef when the variable is a reference (by-ref parameter, `global`, `static`, by-ref
 * capture, `$x = &...`). Partial evaluation knows the tags of a virtual frame, so the tag tests fold away.
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

    // ---- reads ----

    @Override
    public Object execute(VirtualFrame frame) {
        if (frame.isLong(slot)) {
            return frame.getLong(slot);
        }
        if (frame.isDouble(slot)) {
            return frame.getDouble(slot);
        }
        Object value = frame.getObject(slot);
        if (value instanceof PhpRef ref) {
            return ref.get();
        }
        return value == null ? PhpNull.NULL : value;
    }

    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        if (frame.isLong(slot)) {
            return frame.getLong(slot);
        }
        Object value = execute(frame);
        if (value instanceof Long l) {
            return l;
        }
        throw new UnexpectedResultException(value);
    }

    @Override
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        if (frame.isDouble(slot)) {
            return frame.getDouble(slot);
        }
        Object value = execute(frame);
        if (value instanceof Double d) {
            return d;
        }
        throw new UnexpectedResultException(value);
    }

    /** The slot as stored (a PhpRef stays a PhpRef; primitives are boxed), for code that handles every case. */
    public static Object raw(Frame frame, int slot) {
        if (frame.isLong(slot)) {
            return frame.getLong(slot);
        }
        if (frame.isDouble(slot)) {
            return frame.getDouble(slot);
        }
        return frame.getObject(slot);
    }

    /** The Object stored in the slot, or null when it holds a primitive (which can't be a ref or an array). */
    private Object storedObject(Frame frame) {
        return frame.isObject(slot) ? frame.getObject(slot) : null;
    }

    // ---- writes ----

    @Override
    public void write(VirtualFrame frame, Object value) {
        if (value instanceof Long l) {
            writeLong(frame, l);
        } else if (value instanceof Double d) {
            writeDouble(frame, d);
        } else {
            writeObject(frame, value);
        }
    }

    public void writeLong(VirtualFrame frame, long value) {
        Object old = storedObject(frame);
        if (old instanceof PhpRef ref) {
            ref.set(value);
            return;
        }
        frame.setLong(slot, value);
        if (old instanceof PhpArray array) {
            array.removeHolder();
        }
    }

    public void writeDouble(VirtualFrame frame, double value) {
        Object old = storedObject(frame);
        if (old instanceof PhpRef ref) {
            ref.set(value);
            return;
        }
        frame.setDouble(slot, value);
        if (old instanceof PhpArray array) {
            array.removeHolder();
        }
    }

    private void writeObject(VirtualFrame frame, Object value) {
        Object old = storedObject(frame);
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

    // ---- references and in-place updates ----

    @Override
    public PhpRef makeRef(VirtualFrame frame) {
        Object value = raw(frame, slot);
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
        if (storedObject(frame) instanceof PhpArray array) {
            array.removeHolder();
        }
        frame.setObject(slot, ref);
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        Object value = raw(frame, slot);
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
        throw scalarAsArray();
    }

    @TruffleBoundary
    private static PhpError scalarAsArray() {
        return new PhpError("Cannot use a scalar value as an array");
    }

    @Override
    public void unset(VirtualFrame frame) {
        if (storedObject(frame) instanceof PhpArray array) {
            array.removeHolder();
        }
        frame.setObject(slot, null);
    }

    @Override
    public boolean isSet(VirtualFrame frame) {
        if (!frame.isObject(slot)) {
            return true;
        }
        Object value = frame.getObject(slot);
        if (value instanceof PhpRef ref) {
            value = ref.get();
        }
        return value != null && value != PhpNull.NULL;
    }

    @Override
    public Object readIfSet(VirtualFrame frame) {
        if (!frame.isObject(slot)) {
            return execute(frame);
        }
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
