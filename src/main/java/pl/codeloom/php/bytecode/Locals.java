package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.SlowPathException;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import pl.codeloom.php.nodes.vars.LocalVarNode;
import pl.codeloom.php.nodes.vars.Places;
import pl.codeloom.php.nodes.vars.RefWrites;
import pl.codeloom.php.nodes.vars.Variables;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Refcount;

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
     * The variable's int, unboxed. Anything else is a mismatch, also an int the local keeps boxed (the uncached
     * interpreter stores every value boxed; a local whose kind generalized to Object boxes ints as well): accepting
     * it would leave the specialization in place, and compiled code doesn't compile the catch of
     * UnexpectedResultException, so each such read would deoptimize. The operation goes generic instead.
     */
    static long readLong(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode) throws KindMismatch {
        if (variable.isCleared(bytecode, frame)) {
            // unset: the frame has no value to put in the exception getLong would throw
            throw new KindMismatch();
        }
        try {
            return variable.getLong(bytecode, frame);
        } catch (UnexpectedResultException e) {
            throw new KindMismatch();
        }
    }

    /** The variable's float, unboxed (see readLong). */
    static double readDouble(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode) throws KindMismatch {
        if (variable.isCleared(bytecode, frame)) {
            throw new KindMismatch();
        }
        try {
            return variable.getDouble(bytecode, frame);
        } catch (UnexpectedResultException e) {
            throw new KindMismatch();
        }
    }

    /**
     * A foreach's next position, kept in a hidden local as an int. Only the uncached tier finds it boxed (the cached
     * tier's move re-stores the locals, unboxing it), and the uncached tier is never compiled.
     */
    static int position(VirtualFrame frame, LocalAccessor position, BytecodeNode bytecode) {
        try {
            return (int) position.getLong(bytecode, frame);
        } catch (UnexpectedResultException e) {
            return (int) (long) (Long) e.getResult();
        }
    }

    /**
     * A site's writes, profiled as {@link LocalVarNode}'s: without a profile, a write of a value of unknown type
     * compiled a long's, a double's and an Object's store, each with a reference's write and the old value's
     * release, at every site (a method of 100 statements `$a = $n[1];` passed HotSpot's 640 KB install limit).
     * A case not met yet deoptimizes once.
     */
    static final class Writes extends Node {
        /** For the uncached interpreter, which is never compiled: one for every site. */
        private static final Writes UNCACHED = new Writes();
        private static final int LONG = 1;
        private static final int DOUBLE = 1 << 1;
        private static final int OBJECT = 1 << 2;
        private static final int REF = 1 << 3;
        private static final int NEW_ARRAY = 1 << 4;
        private static final int OLD_ARRAY = 1 << 5;
        private static final int NEW_OBJECT = 1 << 6;
        private static final int OLD_OBJECT = 1 << 7;
        private static final int NEW_CLOSURE = 1 << 8;
        private static final int OLD_CLOSURE = 1 << 9;
        @CompilationFinal private int seen;
        private final RefWrites refWrites = RefWrites.create();
        private final Refcount.Releases releases = Refcount.Releases.create();

        static Writes create() {
            return new Writes();
        }

        static Writes getUncached() {
            return UNCACHED;
        }

        private void met(int bit) {
            if ((seen & bit) == 0) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seen |= bit;
            }
        }

        void write(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode, Object value) {
            if (value instanceof Long l) {
                met(LONG);
                writeLong(frame, variable, bytecode, l);
            } else if (value instanceof Double d) {
                met(DOUBLE);
                writeDouble(frame, variable, bytecode, d);
            } else {
                met(OBJECT);
                writeObject(frame, variable, bytecode, value);
            }
        }

        void writeLong(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode, long value) {
            Object old = stored(frame, variable, bytecode);
            if (old instanceof PhpRef ref) {
                met(REF);
                refWrites.setScalar(ref, value);
                return;
            }
            variable.setLong(bytecode, frame, value);
            releaseOld(old);
        }

        void writeDouble(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode, double value) {
            Object old = stored(frame, variable, bytecode);
            if (old instanceof PhpRef ref) {
                met(REF);
                refWrites.setScalar(ref, value);
                return;
            }
            variable.setDouble(bytecode, frame, value);
            releaseOld(old);
        }

        void writeObject(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode, Object value) {
            Object old = stored(frame, variable, bytecode);
            if (old instanceof PhpRef ref) {
                met(REF);
                refWrites.set(ref, value);
                return;
            }
            if (value instanceof PhpArray array) {
                met(NEW_ARRAY);
                array.addHolder();
            } else if (value instanceof PhpObject object) {
                met(NEW_OBJECT);
                object.addRef();
            } else if (value instanceof PhpClosure closureValue) {
                met(NEW_CLOSURE);
                closureValue.addRef();
            }
            variable.setObject(bytecode, frame, value);
            if (old instanceof PhpObject object) {
                // also when it is the value: counted twice above
                met(OLD_OBJECT);
                releases.release(object);
            } else {
                // also when it is the value: its holder was added above
                releaseOld(old);
            }
        }

        /** What the variable held directly gives up its count. */
        private void releaseOld(Object old) {
            if (old instanceof PhpArray array) {
                met(OLD_ARRAY);
                array.removeHolder();
            } else if (old instanceof PhpObject object) {
                met(OLD_OBJECT);
                releases.release(object);
            } else if (old instanceof PhpClosure closureValue) {
                met(OLD_CLOSURE);
                closureValue.release();
            }
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

    /**
     * The variable becomes the reference (`$x = &...`, LocalVarNode.bindRef), then gives up what it held, as
     * zend_assign_to_variable_reference: the new binding first, so `$x = &$x` doesn't take its reference to zero.
     */
    static void bindRef(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode, PhpRef ref) {
        Object old = stored(frame, variable, bytecode);
        ref.bind();
        variable.setObject(bytecode, frame, ref);
        Variables.released(old);
    }

    static void unset(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode) {
        // cleared first, as ZEND_UNSET_CV's ZVAL_UNDEF before the release: a destructor it runs sees it unset
        Object old = stored(frame, variable, bytecode);
        variable.clear(bytecode, frame);
        Variables.released(old);
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
