package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/**
 * `$obj->name` (or `$obj->$name`). Properties are read and written through a shape-dispatched object library,
 * so a site that always sees objects of one class compiles to a field access. A property slot may hold a PhpRef.
 * A protected or private property carries shape flags ({@link PhpClass#PROPERTY_PROTECTED}): only then does the
 * site check that the calling scope may access it, and from a scope that may not, the property is as php-src
 * treats it: the magic methods if the class has them, else an Error.
 */
public final class PropNode extends LValueNode {
    @Child private ExprNode object;
    @Child private ExprNode nameExpr;
    @Child private DynamicObjectLibrary objects = DynamicObjectLibrary.getFactory().createDispatched(4);
    private final String constantName;
    /** The class (lowercase) the code is written in, or null outside classes. */
    private final String lexicalClass;
    /** A class whose non-public property (the constant name) the site's lexical scope may access. */
    @CompilationFinal private PhpClass accessibleClass;

    /** constantName is an interned property name, or null with nameExpr computing it. */
    public PropNode(ExprNode object, String constantName, ExprNode nameExpr, String lexicalClass) {
        this.object = object;
        this.constantName = constantName;
        this.nameExpr = nameExpr;
        this.lexicalClass = lexicalClass;
    }

    private String name(VirtualFrame frame) {
        return constantName != null ? constantName : dynamicName(nameExpr.execute(frame));
    }

    @TruffleBoundary
    private static String dynamicName(Object value) {
        return Str.key(Convert.toStr(value));
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
        return new PhpError("Attempt to " + action + " property \"" + Str.keyText(name) + "\" on "
                + Convert.typeName(value));
    }

    // ---- visibility ----

    /** Whether the calling scope may access the property: public and undeclared ones need no check. */
    private boolean accessible(VirtualFrame frame, PhpObject phpObject, String name) {
        if (objects.getPropertyFlagsOrDefault(phpObject, name, 0) == 0) {
            return true;
        }
        Object scope = frame.getArguments()[PhpFunction.ARG_CLOSURE];
        boolean lexical = scope == null && constantName != null;
        if (lexical && phpObject.getPhpClass() == accessibleClass) {
            return true;
        }
        boolean allowed = phpObject.getPhpClass().canAccessProperty(name, callingScope(scope, lexicalClass));
        if (allowed && lexical && accessibleClass == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            accessibleClass = phpObject.getPhpClass();
        }
        return allowed;
    }

    /**
     * php-src's executed scope: a closure's bound scope, the scope a file was included into, else the class the
     * code is written in.
     */
    public static String callingScope(VirtualFrame frame, String lexicalClass) {
        return callingScope(frame.getArguments()[PhpFunction.ARG_CLOSURE], lexicalClass);
    }

    private static String callingScope(Object scope, String lexicalClass) {
        if (scope instanceof PhpClosure closure) {
            return closure.getScope() == null ? null : closure.getScope().getLowerName();
        }
        if (scope instanceof IncludeScope included) {
            return included.classScope();
        }
        return lexicalClass;
    }

    /** Reading a property the scope may not access: __get's result, else zend_bad_property_access. */
    @TruffleBoundary
    private Object inaccessibleRead(PhpObject phpObject, String name) {
        Object magic = phpObject.magicProperty(this, "__get", name);
        if (magic == PhpObject.NO_MAGIC) {
            throw inaccessible(phpObject, name);
        }
        return magic;
    }

    @TruffleBoundary
    private void inaccessibleWrite(PhpObject phpObject, String name, Object value) {
        if (phpObject.magicProperty(this, "__set", name, value) == PhpObject.NO_MAGIC) {
            throw inaccessible(phpObject, name);
        }
    }

    @TruffleBoundary
    private static PhpError inaccessible(PhpObject phpObject, String name) {
        PhpClass.PropertyDecl decl = phpObject.getPhpClass().findProperty(name);
        return new PhpError("Cannot access " + (decl.visibility() == PhpClass.PropertyDecl.PRIVATE ? "private"
                : "protected") + " property " + phpObject.getPhpClass().getName() + "::$" + Str.keyText(name));
    }

    // ---- reads ----

    /** An int property read without boxing when the object stores it in a long location. */
    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object value = object.execute(frame);
        if (value instanceof PhpObject phpObject) {
            String name = name(frame);
            if (!accessible(frame, phpObject, name)) {
                throw new UnexpectedResultException(inaccessibleRead(phpObject, name));
            }
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
    private Object missing(PhpObject phpObject, String name) {
        Object magic = phpObject.magicProperty(this, "__get", name);
        return magic == PhpObject.NO_MAGIC ? PhpNull.NULL : magic;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = object.execute(frame);
        String name = name(frame);
        if (value instanceof PhpObject phpObject) {
            if (!accessible(frame, phpObject, name)) {
                return inaccessibleRead(phpObject, name);
            }
            Object property = objects.getOrDefault(phpObject, name, null);
            if (property instanceof PhpRef ref) {
                return ref.get();
            }
            return property == null ? missing(phpObject, name) : property;
        }
        return PhpNull.NULL;
    }

    // ---- writes ----

    /** Stores an int unboxed (a long location), unless the property is a reference. */
    @Override
    public void writeLong(VirtualFrame frame, long value) {
        String name = name(frame);
        PhpObject phpObject = target(frame, name, "assign");
        if (!accessible(frame, phpObject, name)) {
            inaccessibleWrite(phpObject, name, value);
            return;
        }
        Object old = objects.getOrDefault(phpObject, name, null);
        if (old instanceof PhpRef ref) {
            ref.set(value);
            return;
        }
        if (old == null && phpObject.magicProperty(this, "__set", name, value) != PhpObject.NO_MAGIC) {
            return;
        }
        objects.putLong(phpObject, name, value);
        if (old instanceof PhpArray array) {
            array.removeHolder();
        }
    }

    @Override
    public void write(VirtualFrame frame, Object value) {
        String name = name(frame);
        PhpObject phpObject = target(frame, name, "assign");
        if (!accessible(frame, phpObject, name)) {
            inaccessibleWrite(phpObject, name, value);
            return;
        }
        Object old = objects.getOrDefault(phpObject, name, null);
        if (old instanceof PhpRef ref) {
            ref.set(value);
            return;
        }
        if (old == null && phpObject.magicProperty(this, "__set", name, value) != PhpObject.NO_MAGIC) {
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

    // ---- references and in-place updates ----

    /**
     * From a scope that may not access the property, __get's value stands in: a modification through it has no
     * effect, as php-src's "Indirect modification of overloaded property".
     */
    @Override
    public PhpRef makeRef(VirtualFrame frame) {
        String name = name(frame);
        PhpObject phpObject = target(frame, name, "modify");
        if (!accessible(frame, phpObject, name)) {
            return new PhpRef(inaccessibleRead(phpObject, name));
        }
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
        if (!accessible(frame, phpObject, name)) {
            throw boundToInaccessible(phpObject, name);
        }
        Object old = objects.getOrDefault(phpObject, name, null);
        if (old instanceof PhpArray array) {
            array.removeHolder();
        } else if (old instanceof PhpRef previous) {
            previous.unbind();
        }
        ref.bind();
        objects.put(phpObject, name, ref);
    }

    @TruffleBoundary
    private static PhpError boundToInaccessible(PhpObject phpObject, String name) {
        return phpObject.getPhpClass().findMethod("__set") != null
                ? new PhpError("Cannot assign by reference to overloaded object") : inaccessible(phpObject, name);
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        String name = name(frame);
        PhpObject phpObject = target(frame, name, "modify");
        if (!accessible(frame, phpObject, name)) {
            return overloadedArray(inaccessibleRead(phpObject, name));
        }
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

    /** An array write through __get's value: to a copy, which the write leaves behind (null for an object). */
    @TruffleBoundary
    private static PhpArray overloadedArray(Object value) {
        if (value instanceof PhpObject) {
            return null;
        }
        return value instanceof PhpArray array ? array.copy() : new PhpArray();
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
            if (!accessible(frame, phpObject, name)) {
                inaccessibleUnset(phpObject, name);
                return;
            }
            Object old = objects.getOrDefault(phpObject, name, null);
            if (old == null) {
                phpObject.magicProperty(this, "__unset", name);
                return;
            }
            if (old instanceof PhpArray array) {
                array.removeHolder();
            }
            objects.removeKey(phpObject, name);
        }
    }

    @TruffleBoundary
    private void inaccessibleUnset(PhpObject phpObject, String name) {
        if (phpObject.magicProperty(this, "__unset", name) == PhpObject.NO_MAGIC) {
            throw inaccessible(phpObject, name);
        }
    }

    // ---- isset ----

    @Override
    public boolean isSet(VirtualFrame frame) {
        Object value = object.execute(frame);
        if (value instanceof PhpObject phpObject) {
            String name = name(frame);
            if (!accessible(frame, phpObject, name)) {
                return magicIsset(phpObject, name);
            }
            Object property = PhpRef.deref(objects.getOrDefault(phpObject, name, null));
            if (property == null) {
                return magicIsset(phpObject, name);
            }
            return property != PhpNull.NULL;
        }
        return false;
    }

    /** isset() of a property that doesn't exist or the scope may not access: __isset's answer if there is one. */
    @TruffleBoundary
    private boolean magicIsset(PhpObject phpObject, String name) {
        Object magic = phpObject.magicProperty(this, "__isset", name);
        return magic != PhpObject.NO_MAGIC && Convert.toBool(magic);
    }

    @Override
    public Object readIfSet(VirtualFrame frame) {
        Object value = object instanceof LValueNode place ? place.readIfSet(frame) : object.execute(frame);
        if (value instanceof PhpObject phpObject) {
            String name = name(frame);
            Object property;
            if (!accessible(frame, phpObject, name)) {
                property = null;
            } else {
                property = PhpRef.deref(objects.getOrDefault(phpObject, name, null));
            }
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
