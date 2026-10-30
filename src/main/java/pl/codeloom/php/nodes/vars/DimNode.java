package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.vars.ElementNodes.ArrayKeyNode;
import pl.codeloom.php.nodes.vars.ElementNodes.ElementReadNode;
import pl.codeloom.php.nodes.vars.ElementNodes.ElementWriteNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/**
 * `$container[key]` and `$container[]`: array elements, string offsets (read) and ArrayAccess objects.
 * A literal key is normalized once by the translator (constantKey); other keys are normalized per access. Element
 * reads and writes are {@link ElementNodes}: DSL nodes that dispatch on the container and the array's storage.
 */
public final class DimNode extends LValueNode {
    @Child private ExprNode container;
    @Child private ExprNode key;
    @Child private ElementReadNode read = ElementNodesFactory.ElementReadNodeGen.create();
    @Child private ElementWriteNode writeElement = ElementNodesFactory.ElementWriteNodeGen.create();
    @Child private ArrayKeyNode toKey = ElementNodesFactory.ArrayKeyNodeGen.create();
    private final Object constantKey;
    /** Until a write here meets an ArrayAccess object, compiled code leaves that path out. */
    private final BranchProfile objectWrite = BranchProfile.create();

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
        return constantKey != null ? constantKey : toKey.execute(key.execute(frame));
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
        Object value = container.execute(frame);
        return read.execute(value, rawKey(frame));
    }

    /** An int element of an int-strategy array is read without boxing. */
    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        if (isAppend()) {
            return super.executeLong(frame);
        }
        Object value = container.execute(frame);
        return read.executeLong(value, rawKey(frame));
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
                writeElement.execute(array, rawKey(frame), value);
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
        if (array == null) {
            writeObject(frame, value);
        } else if (isAppend()) {
            array.appendLong(value);
        } else {
            writeElement.executeLong(array, rawKey(frame), value);
        }
    }

    private void writeObject(VirtualFrame frame, Object value) {
        objectWrite.enter();
        Object target = container.execute(frame);
        writeToObject(this, target, isAppend() ? PhpNull.NULL : rawKey(frame), value);
    }

    /**
     * `$o[key] = value` (key null for `$o[]`) where the container's arrayForWrite gave no array: an ArrayAccess
     * object's offsetSet(), called from site; anything else is an error.
     */
    public static void writeToObject(Node site, Object target, Object key, Object value) {
        if (target instanceof PhpObject object && object.isArrayAccess()) {
            object.callMethod(site, "offsetset", key, value);
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
                Object element = PhpRef.deref(object.callMethod(this, "offsetget", rawKey(frame)));
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
            object.callMethod(this, "offsetunset", rawKey(frame));
        }
    }

    @Override
    public boolean isSet(VirtualFrame frame) {
        Object value = container instanceof LValueNode place ? place.readIfSet(frame) : container.execute(frame);
        if (value instanceof PhpArray array) {
            return read.execute(array, rawKey(frame)) != PhpNull.NULL;
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
            return Convert.toBool(object.callMethod(this, "offsetexists", rawKey(frame)));
        }
        return false;
    }

    @Override
    public Object readIfSet(VirtualFrame frame) {
        Object value = container instanceof LValueNode place ? place.readIfSet(frame) : container.execute(frame);
        if (value instanceof PhpArray array) {
            Object element = read.execute(array, rawKey(frame));
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
                || key instanceof FileVarNode
                || key.constantValue() != null);
    }
}
