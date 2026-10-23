package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.ValueProfile;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/**
 * `$container[key]` and `$container[]`: array elements, string offsets (read) and ArrayAccess objects.
 * A literal key is normalized once by the translator (constantKey); other keys are normalized per access.
 */
public final class DimNode extends LValueNode {
    @Child private ExprNode container;
    @Child private ExprNode key;
    @Child private TruffleString.ReadByteNode readByte = TruffleString.ReadByteNode.create();
    private final Object constantKey;
    /** The container's class (array, string, ArrayAccess object): reads compile to the one case seen. */
    private final ValueProfile containerType = ValueProfile.createClassProfile();
    private final ValueProfile keyType = ValueProfile.createClassProfile();
    /** Whether a write here has met an ArrayAccess object: until then compiled code leaves that path out. */
    @CompilationFinal private boolean seenObjectWrite;
    /**
     * Whether an int read or write here has met anything but an element of an int-strategy array. Until then
     * compiled code holds only that case: no calls or field writes on other paths, so a loop can keep the
     * array's fields in registers instead of reloading them at every access.
     */
    @CompilationFinal private boolean seenLongMiss;
    /** Where a string key was found last time (PhpArray.slotOf); checked by identity before hashing. */
    private int slotGuess = -1;

    /** key and constantKey are both null for `$a[]`. */
    public DimNode(ExprNode container, ExprNode key, Object constantKey) {
        this.container = container;
        this.key = constantKey != null ? null : key;
        this.constantKey = constantKey;
    }

    private boolean isAppend() {
        return key == null && constantKey == null;
    }

    private Object rawKey(VirtualFrame frame) {
        return constantKey != null ? constantKey : key.execute(frame);
    }

    private Object normalizedKey(VirtualFrame frame) {
        return constantKey != null ? constantKey : ArrayKey.of(keyType.profile(key.execute(frame)));
    }

    private LValueNode place() {
        if (container instanceof LValueNode place) {
            return place;
        }
        CompilerDirectives.transferToInterpreter();
        throw new PhpError("Cannot use temporary expression in write context");
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (isAppend()) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Cannot use [] for reading");
        }
        Object value = containerType.profile(container.execute(frame));
        if (value instanceof PhpArray array) {
            Object element = lookup(array, normalizedKey(frame));
            return element == null ? PhpNull.NULL : element;
        }
        return readOther(frame, value);
    }

    /** An int element of an int-strategy array is read without boxing. */
    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        if (isAppend()) {
            return super.executeLong(frame);
        }
        Object value = containerType.profile(container.execute(frame));
        Object result;
        if (value instanceof PhpArray array) {
            Object k = normalizedKey(frame);
            if (k instanceof Long index && array.holdsLongAt(index)) {
                return array.longAt(index);
            }
            longMiss();
            Object element = lookup(array, k);
            result = element == null ? PhpNull.NULL : element;
        } else {
            longMiss();
            result = readOther(frame, value);
        }
        if (result instanceof Long number) {
            return number;
        }
        throw new UnexpectedResultException(result);
    }

    /** A read from a container that isn't an array. */
    private Object readOther(VirtualFrame frame, Object value) {
        if (value instanceof TruffleString string) {
            return stringOffset(string, rawKey(frame));
        }
        if (value instanceof PhpObject object && object.isArrayAccess()) {
            return PhpRef.deref(object.callMethod("offsetget", rawKey(frame)));
        }
        return PhpNull.NULL;
    }

    /** The dereferenced element under a normalized key, or null when absent. */
    private Object lookup(PhpArray array, Object k) {
        if (k instanceof TruffleString) {
            int slot = slotGuess;
            if (!array.isKeyAt(slot, k)) {
                slot = array.slotOf(k);
                if (slot < 0) {
                    return null;
                }
                slotGuess = slot;
            }
            return PhpRef.deref(array.rawAt(slot));
        }
        return array.get(k);
    }

    private TruffleString stringOffset(TruffleString string, Object offsetValue) {
        long offset = Convert.toLong(offsetValue);
        int length = string.byteLength(Str.ENC);
        if (offset < 0) {
            offset += length;
        }
        if (offset < 0 || offset >= length) {
            return Str.EMPTY;
        }
        return Str.singleByte(readByte.execute(string, (int) offset, Str.ENC));
    }

    @Override
    public void write(VirtualFrame frame, Object value) {
        // Hold the value while the container is made writable, so `$a[k] = $a` copies $a first.
        PhpArray held = value instanceof PhpArray array ? array : null;
        if (held != null) {
            held.addHolder();
        }
        try {
            PhpArray array = place().arrayForWrite(frame);
            if (array == null) {
                if (!seenObjectWrite) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    seenObjectWrite = true;
                }
                writeObject(frame, value);
            } else if (isAppend()) {
                array.append(value);
            } else {
                array.put(normalizedKey(frame), value);
            }
        } finally {
            if (held != null) {
                held.removeHolder();
            }
        }
    }

    /** Stores an int unboxed while the array holds only ints. */
    @Override
    public void writeLong(VirtualFrame frame, long value) {
        PhpArray array = place().arrayForWrite(frame);
        if (array != null && !isAppend()) {
            Object k = normalizedKey(frame);
            if (k instanceof Long index && array.holdsLongAt(index)) {
                array.setLongAt(index, value);
                return;
            }
            longMiss();
            array.putLong(k, value);
            return;
        }
        longMiss();
        if (array == null) {
            if (!seenObjectWrite) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seenObjectWrite = true;
            }
            writeObject(frame, value);
        } else if (isAppend()) {
            array.appendLong(value);
        } else {
            array.putLong(normalizedKey(frame), value);
        }
    }

    private void longMiss() {
        if (!seenLongMiss) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            seenLongMiss = true;
        }
    }

    private void writeObject(VirtualFrame frame, Object value) {
        Object target = container.execute(frame);
        if (target instanceof PhpObject object && object.isArrayAccess()) {
            object.callMethod("offsetset", isAppend() ? PhpNull.NULL : rawKey(frame), value);
            return;
        }
        CompilerDirectives.transferToInterpreter();
        throw new PhpError("Cannot use object of type " + target + " as array");
    }

    @Override
    public PhpRef makeRef(VirtualFrame frame) {
        PhpArray array = place().arrayForWrite(frame);
        if (array == null) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Indirect modification of overloaded element");
        }
        return isAppend() ? array.appendRef() : array.refAt(normalizedKey(frame));
    }

    @Override
    public void bindRef(VirtualFrame frame, PhpRef ref) {
        PhpArray array = place().arrayForWrite(frame);
        if (array == null) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Indirect modification of overloaded element");
        }
        array.putRef(isAppend() ? (Object) array.nextIndex() : normalizedKey(frame), ref);
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        PhpArray array = place().arrayForWrite(frame);
        if (array == null) {
            Object target = container.execute(frame);
            if (target instanceof PhpObject object && object.isArrayAccess()) {
                Object element = PhpRef.deref(object.callMethod("offsetget", rawKey(frame)));
                if (element instanceof PhpArray) {
                    CompilerDirectives.transferToInterpreter();
                    throw new PhpError("Indirect modification of overloaded element");
                }
            }
            return null;
        }
        if (isAppend()) {
            PhpArray created = new PhpArray();
            array.append(created);
            return created;
        }
        Object k = normalizedKey(frame);
        PhpArray nested = array.arrayForWrite(k);
        if (nested == null && !(array.get(k) instanceof PhpObject)) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Cannot use a scalar value as an array");
        }
        return nested;
    }

    @Override
    public void unset(VirtualFrame frame) {
        Object value = container.execute(frame);
        if (value instanceof PhpArray current) {
            Object k = normalizedKey(frame);
            if (current.containsKey(k)) {
                PhpArray array = place().arrayForWrite(frame);
                array.remove(k);
            }
        } else if (value instanceof PhpObject object && object.isArrayAccess()) {
            object.callMethod("offsetunset", rawKey(frame));
        }
    }

    @Override
    public boolean isSet(VirtualFrame frame) {
        Object value = containerType.profile(
                container instanceof LValueNode place ? place.readIfSet(frame) : container.execute(frame));
        if (value instanceof PhpArray array) {
            Object element = lookup(array, normalizedKey(frame));
            return element != null && element != PhpNull.NULL;
        }
        if (value instanceof TruffleString string) {
            Object offsetValue = rawKey(frame);
            if (offsetValue instanceof TruffleString s && Convert.numericValue(s) == null) {
                return false;
            }
            long offset = Convert.toLong(offsetValue);
            int length = string.byteLength(Str.ENC);
            return offset < 0 ? -offset <= length : offset < length;
        }
        if (value instanceof PhpObject object && object.isArrayAccess()) {
            return Convert.toBool(object.callMethod("offsetexists", rawKey(frame)));
        }
        return false;
    }

    @Override
    public Object readIfSet(VirtualFrame frame) {
        Object value = containerType.profile(
                container instanceof LValueNode place ? place.readIfSet(frame) : container.execute(frame));
        if (value instanceof PhpArray array) {
            Object element = lookup(array, normalizedKey(frame));
            return element == PhpNull.NULL ? null : element;
        }
        if (value == null || value == PhpNull.NULL) {
            return null;
        }
        return isSet(frame) ? execute(frame) : null;
    }

    @Override
    public boolean isPure() {
        boolean containerPure = container instanceof LValueNode place && place.isPure();
        return containerPure && (key == null || key instanceof LocalVarNode || key instanceof GlobalVarNode
                || key.constantValue() != null);
    }
}
