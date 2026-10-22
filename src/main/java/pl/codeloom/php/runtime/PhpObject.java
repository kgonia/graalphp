package pl.codeloom.php.runtime;

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
    private static int nextId = 1;

    private final PhpClass phpClass;
    private final int id;

    public PhpObject(Shape shape, PhpClass phpClass) {
        super(shape);
        this.phpClass = phpClass;
        this.id = nextId++;
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
            throw new PhpError("Object of class " + phpClass.getName() + " could not be converted to string");
        }
        return Convert.toStr(toString.invoke(this, phpClass));
    }

    @Override
    public String toString() {
        return phpClass.getName() + "#" + id;
    }
}
