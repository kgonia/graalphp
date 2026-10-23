package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;

/**
 * `$obj->name` (or `$obj->$name`). Properties are read and written through a shape-dispatched object library,
 * so a site that always sees objects of one class compiles to a field access. A property slot may hold a PhpRef.
 */
public final class PropNode extends LValueNode {
    @Child private ExprNode object;
    @Child private ExprNode nameExpr;
    @Child private DynamicObjectLibrary objects = DynamicObjectLibrary.getFactory().createDispatched(4);
    private final String constantName;

    /** constantName is an interned property name, or null with nameExpr computing it. */
    public PropNode(ExprNode object, String constantName, ExprNode nameExpr) {
        this.object = object;
        this.constantName = constantName;
        this.nameExpr = nameExpr;
    }

    private String name(VirtualFrame frame) {
        return constantName != null ? constantName : dynamicName(nameExpr.execute(frame));
    }

    @TruffleBoundary
    private static String dynamicName(Object value) {
        return Convert.toStr(value).toJavaStringUncached().intern();
    }

    private PhpObject target(VirtualFrame frame, String name, String action) {
        Object value = object.execute(frame);
        if (value instanceof PhpObject phpObject) {
            return phpObject;
        }
        throw notAnObject(action, name, value);
    }

    @TruffleBoundary
    private static PhpError notAnObject(String action, String name, Object value) {
        return new PhpError("Attempt to " + action + " property \"" + name + "\" on " + Convert.typeName(value));
    }

    /** An int property read without boxing when the object stores it in a long location. */
    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object value = object.execute(frame);
        if (value instanceof PhpObject phpObject) {
            String name = name(frame);
            try {
                return objects.getLongOrDefault(phpObject, name, ABSENT);
            } catch (UnexpectedResultException e) {
                Object result = e.getResult();
                throw new UnexpectedResultException(result == ABSENT ? missing(phpObject, name)
                        : PhpRef.deref(result));
            }
        }
        throw new UnexpectedResultException(PhpNull.NULL);
    }

    /** The default of a property lookup: the property doesn't exist (declared ones exist until unset). */
    private static final Object ABSENT = new Object();

    /** Reading a property that doesn't exist: __get's result if the class has one, else null. */
    @TruffleBoundary
    private static Object missing(PhpObject phpObject, String name) {
        Object magic = phpObject.magicProperty("__get", name);
        return magic == PhpObject.NO_MAGIC ? PhpNull.NULL : magic;
    }

    /** Stores an int unboxed (a long location), unless the property is a reference. */
    @Override
    public void writeLong(VirtualFrame frame, long value) {
        String name = name(frame);
        PhpObject phpObject = target(frame, name, "assign");
        Object old = objects.getOrDefault(phpObject, name, null);
        if (old instanceof PhpRef ref) {
            ref.set(value);
            return;
        }
        if (old == null && phpObject.magicProperty("__set", name, value) != PhpObject.NO_MAGIC) {
            return;
        }
        objects.putLong(phpObject, name, value);
        if (old instanceof PhpArray array) {
            array.removeHolder();
        }
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = object.execute(frame);
        String name = name(frame);
        if (value instanceof PhpObject phpObject) {
            Object property = objects.getOrDefault(phpObject, name, null);
            if (property instanceof PhpRef ref) {
                return ref.get();
            }
            return property == null ? missing(phpObject, name) : property;
        }
        return PhpNull.NULL;
    }

    @Override
    public void write(VirtualFrame frame, Object value) {
        String name = name(frame);
        PhpObject phpObject = target(frame, name, "assign");
        Object old = objects.getOrDefault(phpObject, name, null);
        if (old instanceof PhpRef ref) {
            ref.set(value);
            return;
        }
        if (old == null && phpObject.magicProperty("__set", name, value) != PhpObject.NO_MAGIC) {
            return;
        }
        if (value instanceof Long l) {
            objects.putLong(phpObject, name, l);
        } else {
            if (value instanceof PhpArray array) {
                array.addHolder();
            }
            objects.put(phpObject, name, value);
        }
        if (old instanceof PhpArray array && old != value) {
            array.removeHolder();
        }
    }

    @Override
    public PhpRef makeRef(VirtualFrame frame) {
        String name = name(frame);
        PhpObject phpObject = target(frame, name, "modify");
        Object old = objects.getOrDefault(phpObject, name, null);
        if (old instanceof PhpRef ref) {
            return ref;
        }
        PhpRef ref = new PhpRef(old == null ? PhpNull.NULL : old);
        if (old instanceof PhpArray array) {
            array.removeHolder();
        }
        ref.bind();
        objects.put(phpObject, name, ref);
        return ref;
    }

    @Override
    public void bindRef(VirtualFrame frame, PhpRef ref) {
        String name = name(frame);
        PhpObject phpObject = target(frame, name, "modify");
        Object old = objects.getOrDefault(phpObject, name, null);
        if (old instanceof PhpArray array) {
            array.removeHolder();
        } else if (old instanceof PhpRef previous) {
            previous.unbind();
        }
        ref.bind();
        objects.put(phpObject, name, ref);
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        String name = name(frame);
        PhpObject phpObject = target(frame, name, "modify");
        Object old = objects.getOrDefault(phpObject, name, null);
        if (old instanceof PhpArray array) {
            if (array.isShared()) {
                PhpArray copy = array.copy();
                copy.addHolder();
                array.removeHolder();
                objects.put(phpObject, name, copy);
                return copy;
            }
            return array;
        }
        if (old instanceof PhpRef ref) {
            return Places.refArrayForWrite(ref);
        }
        if (old == null || old == PhpNull.NULL || old == Boolean.FALSE) {
            PhpArray created = new PhpArray();
            created.addHolder();
            objects.put(phpObject, name, created);
            return created;
        }
        if (old instanceof PhpObject) {
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
        Object value = object.execute(frame);
        if (value instanceof PhpObject phpObject) {
            String name = name(frame);
            Object old = objects.getOrDefault(phpObject, name, null);
            if (old == null) {
                phpObject.magicProperty("__unset", name);
                return;
            }
            if (old instanceof PhpArray array) {
                array.removeHolder();
            }
            objects.removeKey(phpObject, name);
        }
    }

    @Override
    public boolean isSet(VirtualFrame frame) {
        Object value = object.execute(frame);
        if (value instanceof PhpObject phpObject) {
            String name = name(frame);
            Object property = PhpRef.deref(objects.getOrDefault(phpObject, name, null));
            if (property == null) {
                return magicIsset(phpObject, name);
            }
            return property != PhpNull.NULL;
        }
        return false;
    }

    /** isset() of a property that doesn't exist: __isset's answer if the class has one. */
    @TruffleBoundary
    private static boolean magicIsset(PhpObject phpObject, String name) {
        Object magic = phpObject.magicProperty("__isset", name);
        return magic != PhpObject.NO_MAGIC && Convert.toBool(magic);
    }

    @Override
    public Object readIfSet(VirtualFrame frame) {
        Object value = object instanceof LValueNode place ? place.readIfSet(frame) : object.execute(frame);
        if (value instanceof PhpObject phpObject) {
            String name = name(frame);
            Object property = PhpRef.deref(objects.getOrDefault(phpObject, name, null));
            if (property == null) {
                // isset($o->x[...]) / $o->x ?? ...: __isset decides, then __get gives the value
                if (!magicIsset(phpObject, name)) {
                    return null;
                }
                property = missing(phpObject, name);
            }
            return property == PhpNull.NULL ? null : property;
        }
        return null;
    }

    @Override
    public boolean isPure() {
        boolean objectPure = object instanceof ThisNode || object instanceof LValueNode place && place.isPure();
        return objectPure && (nameExpr == null || nameExpr instanceof LocalVarNode);
    }
}
