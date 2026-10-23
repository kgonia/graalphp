package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.strings.TruffleString;

/**
 * A PHP object. Properties live in the Truffle object model keyed by interned Java Strings, so property access
 * nodes get shape-based inline caches. Property order is insertion order: declared properties first, as PHP.
 */
public final class PhpObject extends DynamicObject {
    private final PhpClass phpClass;
    private final int id;
    /** Native state of a built-in class's instance (a mysqli connection, a query result), or null. */
    private Object internal;
    /** The magic property accesses in progress ("get:name", ...): inside __get('x'), $this->x is the real one. */
    private java.util.Set<String> magicGuards;

    /** id: var_dump's #n and spl_object_id(), from {@code PhpContext.nextObjectId()}. */
    public PhpObject(Shape shape, PhpClass phpClass, int id) {
        super(shape);
        this.phpClass = phpClass;
        this.id = id;
    }

    /**
     * Calls a magic property method (__get, __set, __isset, __unset) unless the class has none or one for this
     * property is already running on this object, as php-src's property guards; NO_MAGIC when it didn't call.
     */
    @TruffleBoundary
    public Object magicProperty(String lowerMethod, String name, Object... args) {
        if (phpClass.findMethod(lowerMethod) == null) {
            return NO_MAGIC;
        }
        String guard = lowerMethod + ":" + name;
        if (magicGuards == null) {
            magicGuards = new java.util.HashSet<>();
        }
        if (!magicGuards.add(guard)) {
            return NO_MAGIC;
        }
        try {
            Object[] arguments = new Object[args.length + 1];
            arguments[0] = Str.ofKey(name);
            System.arraycopy(args, 0, arguments, 1, args.length);
            return callMethod(lowerMethod, arguments);
        } finally {
            magicGuards.remove(guard);
        }
    }

    /** What magicProperty returns when it called no method. */
    public static final Object NO_MAGIC = new Object();

    public Object getInternal() {
        return internal;
    }

    public void setInternal(Object internal) {
        this.internal = internal;
    }

    public PhpClass getPhpClass() {
        return phpClass;
    }

    /** spl_object_id(). */
    public int id() {
        return id;
    }

    @TruffleBoundary
    public Object getProperty(String name) {
        return DynamicObjectLibrary.getUncached().getOrDefault(this, name, null);
    }

    @TruffleBoundary
    public void setProperty(String name, Object value) {
        DynamicObjectLibrary.getUncached().put(this, name, value);
    }

    @TruffleBoundary
    public boolean removeProperty(String name) {
        return DynamicObjectLibrary.getUncached().removeKey(this, name);
    }

    @TruffleBoundary
    public Object[] propertyNames() {
        return DynamicObjectLibrary.getUncached().getKeyArray(this);
    }

    /** Calls a method from runtime code (ArrayAccess, magic methods): no inline caching. */
    @TruffleBoundary
    public Object callMethod(String lowerName, Object... args) {
        PhpFunction method = phpClass.findMethod(lowerName);
        if (method == null) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Call to undefined method " + phpClass.getName() + "::" + lowerName + "()");
        }
        return method.invoke(this, phpClass, args);
    }

    public boolean isArrayAccess() {
        return phpClass.isSubclassOf("arrayaccess");
    }

    @TruffleBoundary
    public TruffleString toPhpString() {
        PhpFunction toString = phpClass.findMethod("__tostring");
        if (toString == null) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Object of class " + phpClass.getName() + " could not be converted to string");
        }
        return Convert.toStr(toString.invoke(this, phpClass));
    }

    @Override
    public String toString() {
        return phpClass.getName() + "#" + id;
    }
}
