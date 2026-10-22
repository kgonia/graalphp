package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * Writes to local-variable slots, steered by the slot's kind in the frame descriptor (compilation-final): Illegal
 * until first written, then Long or Double while every value stored has been one, Object (with boxed numbers) once
 * it has held anything else. All writes to a slot thus use one tag. Choosing the tag by each value's runtime type
 * instead leaves several tags on the paths that merge after a write whose value type isn't known statically,
 * which disables Graal's frame intrinsics for the whole compilation unit. Reads test the tag, so they accept
 * whatever an activation stored before its slot was generalized.
 */
public final class FrameSlots {
    private FrameSlots() {
    }

    public static void setLong(VirtualFrame frame, int slot, long value) {
        FrameSlotKind kind = frame.getFrameDescriptor().getSlotKind(slot);
        if (kind == FrameSlotKind.Long) {
            frame.setLong(slot, value);
        } else if (kind == FrameSlotKind.Object) {
            frame.setObject(slot, value);
        } else {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            if (generalize(frame.getFrameDescriptor(), slot, FrameSlotKind.Long) == FrameSlotKind.Long) {
                frame.setLong(slot, value);
            } else {
                frame.setObject(slot, value);
            }
        }
    }

    public static void setDouble(VirtualFrame frame, int slot, double value) {
        FrameSlotKind kind = frame.getFrameDescriptor().getSlotKind(slot);
        if (kind == FrameSlotKind.Double) {
            frame.setDouble(slot, value);
        } else if (kind == FrameSlotKind.Object) {
            frame.setObject(slot, value);
        } else {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            if (generalize(frame.getFrameDescriptor(), slot, FrameSlotKind.Double) == FrameSlotKind.Double) {
                frame.setDouble(slot, value);
            } else {
                frame.setObject(slot, value);
            }
        }
    }

    /** Stores a non-number (or a number the caller has boxed on purpose) with the Object tag. */
    public static void setObject(VirtualFrame frame, int slot, Object value) {
        if (frame.getFrameDescriptor().getSlotKind(slot) != FrameSlotKind.Object) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            frame.getFrameDescriptor().setSlotKind(slot, FrameSlotKind.Object);
        }
        frame.setObject(slot, value);
    }

    /** Stores a value of any type: unboxed into a Long or Double slot, boxed into an Object slot. */
    public static void set(VirtualFrame frame, int slot, Object value) {
        if (value instanceof Long l) {
            setLong(frame, slot, l);
        } else if (value instanceof Double d) {
            setDouble(frame, slot, d);
        } else {
            setObject(frame, slot, value);
        }
    }

    private static FrameSlotKind generalize(FrameDescriptor descriptor, int slot, FrameSlotKind wanted) {
        FrameSlotKind kind = descriptor.getSlotKind(slot);
        FrameSlotKind result = kind == FrameSlotKind.Illegal || kind == wanted ? wanted : FrameSlotKind.Object;
        descriptor.setSlotKind(slot, result);
        return result;
    }
}
