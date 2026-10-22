package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.frame.VirtualFrame;
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
        throw new PhpError("Cannot use temporary expression in write context");
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (isAppend()) {
            throw new PhpError("Cannot use [] for reading");
        }
        Object value = containerType.profile(container.execute(frame));
        if (value instanceof PhpArray array) {
            Object element = array.get(normalizedKey(frame));
            return element == null ? PhpNull.NULL : element;
        }
        if (value instanceof TruffleString string) {
            return stringOffset(string, rawKey(frame));
        }
        if (value instanceof PhpObject object && object.isArrayAccess()) {
            return PhpRef.deref(object.callMethod("offsetget", rawKey(frame)));
        }
        return PhpNull.NULL;
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

    private void writeObject(VirtualFrame frame, Object value) {
        Object target = container.execute(frame);
        if (target instanceof PhpObject object && object.isArrayAccess()) {
            object.callMethod("offsetset", isAppend() ? PhpNull.NULL : rawKey(frame), value);
            return;
        }
        throw new PhpError("Cannot use object of type " + target + " as array");
    }

    @Override
    public PhpRef makeRef(VirtualFrame frame) {
        PhpArray array = place().arrayForWrite(frame);
        if (array == null) {
            throw new PhpError("Indirect modification of overloaded element");
        }
        return isAppend() ? array.appendRef() : array.refAt(normalizedKey(frame));
    }

    @Override
    public void bindRef(VirtualFrame frame, PhpRef ref) {
        PhpArray array = place().arrayForWrite(frame);
        if (array == null) {
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
        if (container instanceof LValueNode place && !place.isSet(frame)) {
            return false;
        }
        Object value = container.execute(frame);
        if (value instanceof PhpArray array) {
            Object element = array.get(normalizedKey(frame));
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
        Object value = container instanceof LValueNode place ? place.readIfSet(frame) : container.execute(frame);
        if (value instanceof PhpArray array) {
            Object element = array.get(normalizedKey(frame));
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
