package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;

/**
 * A function's local variable in a frame slot. A variable that has only held ints (or only floats) is stored
 * unboxed, so loop counters and arithmetic stay primitive in compiled code; otherwise the slot holds Objects: a
 * value or a PhpRef when the variable is a reference (by-ref parameter, `global`, `static`, by-ref capture,
 * `$x = &...`). Writes follow the slot's kind ({@link FrameSlots}). A slot never written, or unset, has the
 * illegal tag (the frame descriptor's default), which merges with any tag. Reads test the tag; partial
 * evaluation knows the tags of a virtual frame, so the tests fold away.
 */
public final class LocalVarNode extends LValueNode {
    private final int slot;
    private final String name;
    /**
     * What this site has met, one bit per case (the SEEN_ constants). Partial evaluation uses no host profiles, so
     * an unprofiled write compiled every case: a long, a double and an Object store, each with a reference's
     * write and the old value's release, at every assignment of a value of unknown type (one of the largest
     * parts of big compiled functions). A case not yet met is a deoptimization in compiled code.
     */
    @CompilationFinal private int seen;
    private static final int SEEN_LONG = 1;
    private static final int SEEN_DOUBLE = 1 << 1;
    private static final int SEEN_OBJECT = 1 << 2;
    /** The variable was a reference when written. */
    private static final int SEEN_REF = 1 << 3;
    /** A write here stored an array (it gains a holder). */
    private static final int SEEN_NEW_ARRAY = 1 << 4;
    /** A write here replaced an array (it loses a holder). */
    private static final int SEEN_OLD_ARRAY = 1 << 5;
    /** arrayForWrite met a shared array, a reference, a value that becomes an array. */
    private static final int SEEN_SHARED = 1 << 6;
    private static final int SEEN_REF_ARRAY = 1 << 7;
    private static final int SEEN_CREATED = 1 << 8;
    /** The writes through the reference the variable is (SEEN_REF). */
    private final RefWrites refWrites = RefWrites.create();
    /** A read here met a tag other than the slot kind's (see execute). */
    @CompilationFinal private boolean anyTag;

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

    /**
     * Reads by the slot's kind, a compile-time constant (FrameSlots): every write stores the kind's tag, so the
     * slot holds that or nothing, and compiled code tests for that tag only. Testing every tag compiled a long's
     * and a float's read and boxing at each read of a variable whose tag isn't known where it is read (one set on
     * some paths only). A value an activation stored before the slot's kind generalized makes this site read
     * every tag from then on (one deoptimization).
     */
    @Override
    public Object execute(VirtualFrame frame) {
        if (!anyTag) {
            FrameSlotKind kind = frame.getFrameDescriptor().getSlotKind(slot);
            byte tag = frame.getTag(slot);
            if (tag == FrameSlotKind.Illegal.tag) {
                return PhpNull.NULL;
            }
            if (kind == FrameSlotKind.Object && tag == FrameSlotKind.Object.tag) {
                return Variables.value(frame.getObject(slot));
            }
            if (kind == FrameSlotKind.Long && tag == FrameSlotKind.Long.tag) {
                return frame.getLong(slot);
            }
            if (kind == FrameSlotKind.Double && tag == FrameSlotKind.Double.tag) {
                return frame.getDouble(slot);
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            anyTag = true;
        }
        return executeAnyTag(frame);
    }

    private Object executeAnyTag(VirtualFrame frame) {
        if (frame.isLong(slot)) {
            return frame.getLong(slot);
        }
        if (frame.isDouble(slot)) {
            return frame.getDouble(slot);
        }
        if (!frame.isObject(slot)) {
            return PhpNull.NULL;
        }
        return Variables.value(frame.getObject(slot));
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

    /**
     * The slot as stored (a PhpRef stays a PhpRef; primitives are boxed; null when unset), for code that handles
     * every case.
     */
    public static Object raw(Frame frame, int slot) {
        if (frame.isLong(slot)) {
            return frame.getLong(slot);
        }
        if (frame.isDouble(slot)) {
            return frame.getDouble(slot);
        }
        return frame.isObject(slot) ? frame.getObject(slot) : null;
    }

    /** The Object stored in the slot, or null when it holds a primitive (which can't be a ref or an array). */
    private Object storedObject(Frame frame) {
        return frame.isObject(slot) ? frame.getObject(slot) : null;
    }

    /** Notes that this site met a case; in compiled code that hadn't seen it, deoptimizes first. */
    private void met(int bit) {
        if ((seen & bit) == 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            seen |= bit;
        }
    }

    // ---- writes ----

    @Override
    public void write(VirtualFrame frame, Object value) {
        if (value instanceof Long l) {
            met(SEEN_LONG);
            writeLong(frame, l);
        } else if (value instanceof Double d) {
            met(SEEN_DOUBLE);
            writeDouble(frame, d);
        } else {
            met(SEEN_OBJECT);
            writeObject(frame, value);
        }
    }

    @Override
    public void writeLong(VirtualFrame frame, long value) {
        Object old = storedObject(frame);
        if (old instanceof PhpRef ref) {
            met(SEEN_REF);
            refWrites.setScalar(ref, value);
            return;
        }
        FrameSlots.setLong(frame, slot, value);
        releaseArray(old);
    }

    @Override
    public void writeDouble(VirtualFrame frame, double value) {
        Object old = storedObject(frame);
        if (old instanceof PhpRef ref) {
            met(SEEN_REF);
            refWrites.setScalar(ref, value);
            return;
        }
        FrameSlots.setDouble(frame, slot, value);
        releaseArray(old);
    }

    private void writeObject(VirtualFrame frame, Object value) {
        Object old = storedObject(frame);
        if (old instanceof PhpRef ref) {
            met(SEEN_REF);
            refWrites.set(ref, value);
            return;
        }
        if (value instanceof PhpArray array) {
            met(SEEN_NEW_ARRAY);
            array.addHolder();
        }
        FrameSlots.setObject(frame, slot, value);
        if (old != value) {
            releaseArray(old);
        }
    }

    /** The old value of a variable that isn't a reference: an array loses this variable as a holder. */
    private void releaseArray(Object old) {
        if (old instanceof PhpArray array) {
            met(SEEN_OLD_ARRAY);
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
        PhpRef ref = Variables.newRef(value);
        FrameSlots.setObject(frame, slot, ref);
        return ref;
    }

    @Override
    public void bindRef(VirtualFrame frame, PhpRef ref) {
        Variables.released(storedObject(frame));
        ref.bind();
        FrameSlots.setObject(frame, slot, ref);
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        Object value = raw(frame, slot);
        if (value instanceof PhpArray array) {
            if (array.isShared()) {
                met(SEEN_SHARED);
                PhpArray copy = Variables.separated(array);
                FrameSlots.setObject(frame, slot, copy);
                return copy;
            }
            return array;
        }
        if (value instanceof PhpRef ref) {
            met(SEEN_REF_ARRAY);
            return Places.refArrayForWrite(ref);
        }
        if (Variables.becomesArray(value)) {
            met(SEEN_CREATED);
            PhpArray created = Variables.created();
            FrameSlots.setObject(frame, slot, created);
            return created;
        }
        if (value instanceof PhpObject) {
            return null;
        }
        throw Variables.scalarAsArray();
    }

    // The arrayForWrite cases are profiled too (SEEN_SHARED, SEEN_REF_ARRAY, SEEN_CREATED). An element write such
    // as `$q[$i] = $v` usually only ever finds an unshared array; compiled code keeps the other cases (separation,
    // references, auto-creation) out until they happen, instead of inlining all of them at every write site.

    @Override
    public void unset(VirtualFrame frame) {
        Variables.released(storedObject(frame));
        frame.clear(slot);
    }

    @Override
    public boolean isDefined(VirtualFrame frame) {
        return raw(frame, slot) != null;
    }

    @Override
    public boolean isSet(VirtualFrame frame) {
        if (frame.isLong(slot) || frame.isDouble(slot)) {
            return true;
        }
        if (!frame.isObject(slot)) {
            return false;
        }
        Object value = frame.getObject(slot);
        if (value instanceof PhpRef ref) {
            value = ref.get();
        }
        return value != null && value != PhpNull.NULL;
    }

    /** As execute, by the slot's kind; null (Java) when unset or null. */
    @Override
    public Object readIfSet(VirtualFrame frame) {
        if (!anyTag) {
            FrameSlotKind kind = frame.getFrameDescriptor().getSlotKind(slot);
            byte tag = frame.getTag(slot);
            if (tag == FrameSlotKind.Illegal.tag) {
                return null;
            }
            if (kind == FrameSlotKind.Object && tag == FrameSlotKind.Object.tag) {
                Object value = frame.getObject(slot);
                if (value instanceof PhpRef ref) {
                    value = ref.get();
                }
                return value == PhpNull.NULL ? null : value;
            }
            if (kind == FrameSlotKind.Long && tag == FrameSlotKind.Long.tag) {
                return frame.getLong(slot);
            }
            if (kind == FrameSlotKind.Double && tag == FrameSlotKind.Double.tag) {
                return frame.getDouble(slot);
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            anyTag = true;
        }
        return readIfSetAnyTag(frame);
    }

    private Object readIfSetAnyTag(VirtualFrame frame) {
        if (frame.isLong(slot)) {
            return frame.getLong(slot);
        }
        if (frame.isDouble(slot)) {
            return frame.getDouble(slot);
        }
        if (!frame.isObject(slot)) {
            return null;
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
