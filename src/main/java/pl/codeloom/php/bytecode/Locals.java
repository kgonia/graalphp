package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.SlowPathException;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import pl.codeloom.php.nodes.vars.LocalVarNode;
import pl.codeloom.php.nodes.vars.Places;
import pl.codeloom.php.nodes.vars.Variables;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;

/**
 * A PHP variable in a bytecode local, as {@link LocalVarNode} keeps one in a frame slot, by the same rules
 * ({@link Variables}): the local holds a value, unboxed while it has only held ints (or only floats), or a
 * {@link PhpRef}. The bytecode node keeps the local's kind (Illegal, then Long or Double, then Object, generalized
 * once), as {@code FrameSlots} keeps a slot's in the frame descriptor.
 */
final class Locals {
    private Locals() {
    }

    /** The local holds no unboxed int or float of the kind asked for: the operation's specialization leaves. */
    static final class KindMismatch extends SlowPathException {
        private static final long serialVersionUID = 1L;
    }

    /** What the variable stores (a value or a PhpRef; a primitive boxed), or null when it is unset. */
    static Object stored(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode) {
        return variable.isCleared(bytecode, frame) ? null : variable.getObject(bytecode, frame);
    }

    /**
     * The variable's int. A boxed one counts: the uncached interpreter stores every value boxed, and a call that
     * moves to the cached interpreter in a loop finds them there until it writes them again.
     */
    static long readLong(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode) throws KindMismatch {
        try {
            return variable.getLong(bytecode, frame);
        } catch (UnexpectedResultException e) {
            if (e.getResult() instanceof Long boxed) {
                return boxed;
            }
            throw new KindMismatch();
        }
    }

    /** The variable's float, boxed or not (see readLong). */
    static double readDouble(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode) throws KindMismatch {
        try {
            return variable.getDouble(bytecode, frame);
        } catch (UnexpectedResultException e) {
            if (e.getResult() instanceof Double boxed) {
                return boxed;
            }
            throw new KindMismatch();
        }
    }

    /** A float operand's other side: the variable's float, or its int widened. */
    static double readNumber(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode) throws KindMismatch {
        try {
            return variable.getDouble(bytecode, frame);
        } catch (UnexpectedResultException notDouble) {
            if (notDouble.getResult() instanceof Double boxed) {
                return boxed;
            }
            return readLong(frame, variable, bytecode);
        }
    }

    /** A foreach's next position, kept in a hidden local as an int (boxed when the uncached tier stored it). */
    static int position(VirtualFrame frame, LocalAccessor position, BytecodeNode bytecode) {
        try {
            return (int) position.getLong(bytecode, frame);
        } catch (UnexpectedResultException e) {
            return (int) (long) (Long) e.getResult();
        }
    }

    static void writeLong(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode, long value) {
        Object old = stored(frame, variable, bytecode);
        if (old instanceof PhpRef ref) {
            ref.set(value);
            return;
        }
        variable.setLong(bytecode, frame, value);
        Variables.released(old);
    }

    static void writeDouble(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode, double value) {
        Object old = stored(frame, variable, bytecode);
        if (old instanceof PhpRef ref) {
            ref.set(value);
            return;
        }
        variable.setDouble(bytecode, frame, value);
        Variables.released(old);
    }

    static void writeObject(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode, Object value) {
        Object old = stored(frame, variable, bytecode);
        if (old instanceof PhpRef ref) {
            ref.set(value);
            return;
        }
        if (value instanceof PhpArray array) {
            array.addHolder();
        }
        variable.setObject(bytecode, frame, value);
        Variables.replaced(old, value);
    }

    static void write(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode, Object value) {
        if (value instanceof Long l) {
            writeLong(frame, variable, bytecode, l);
        } else if (value instanceof Double d) {
            writeDouble(frame, variable, bytecode, d);
        } else {
            writeObject(frame, variable, bytecode, value);
        }
    }

    /** Stores into a variable that holds nothing yet (a parameter, a capture): no old value to give up. */
    static void initialize(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode, Object value) {
        if (value instanceof Long l) {
            variable.setLong(bytecode, frame, l);
        } else if (value instanceof Double d) {
            variable.setDouble(bytecode, frame, d);
        } else {
            variable.setObject(bytecode, frame, value);
        }
    }

    /** The variable's reference, converting it into one if needed (`&$x`, a by-reference argument). */
    static PhpRef makeRef(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode) {
        Object stored = stored(frame, variable, bytecode);
        if (stored instanceof PhpRef ref) {
            return ref;
        }
        PhpRef ref = Variables.newRef(stored);
        variable.setObject(bytecode, frame, ref);
        return ref;
    }

    static void unset(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode) {
        Variables.released(stored(frame, variable, bytecode));
        variable.clear(bytecode, frame);
    }

    /**
     * The variable's array, exclusive to it so it can be modified in place (LocalVarNode.arrayForWrite): copied
     * if shared, created if the variable is unset, null or false; null for an object; an error for other scalars.
     * The profiles keep the cases a site hasn't met out of compiled code.
     */
    static PhpArray arrayForWrite(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode, Node node,
                                  InlinedBranchProfile sharedArray, InlinedBranchProfile refArray,
                                  InlinedBranchProfile createdArray) {
        Object stored = stored(frame, variable, bytecode);
        if (stored instanceof PhpArray array) {
            if (array.isShared()) {
                sharedArray.enter(node);
                PhpArray copy = Variables.separated(array);
                variable.setObject(bytecode, frame, copy);
                return copy;
            }
            return array;
        }
        if (stored instanceof PhpRef ref) {
            refArray.enter(node);
            return Places.refArrayForWrite(ref);
        }
        if (Variables.becomesArray(stored)) {
            createdArray.enter(node);
            PhpArray created = Variables.created();
            variable.setObject(bytecode, frame, created);
            return created;
        }
        if (stored instanceof PhpObject) {
            return null;
        }
        throw Variables.scalarAsArray();
    }
}
