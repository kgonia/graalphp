package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.strings.TruffleString;

import java.util.ArrayList;
import java.util.List;

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

    /** magicProperty for a node's access: the node is where traces show the call made. */
    @TruffleBoundary
    public Object magicProperty(Node site, String lowerMethod, String name, Object... args) {
        return Backtrace.callFrom(site, () -> magicProperty(lowerMethod, name, args));
    }

    /**
     * get_object_vars(), and foreach over a plain object: the properties code in the scope (a class's lowercase
     * name, or null outside classes) may access, dereferenced, in order.
     */
    @TruffleBoundary
    public PhpArray visibleProperties(String scope) {
        PhpArray result = new PhpArray();
        for (String property : visiblePropertyNames(scope)) {
            result.put(ArrayKey.of(Str.ofKey(property)), PhpRef.deref(getProperty(property)));
        }
        return result;
    }

    /**
     * The properties as php-src's property table holds them: the declared ones in declaration order (a parent's
     * first) under their mangled keys ({@link PhpClass#mangledName}), then the dynamic ones in the order they
     * were added; as key, raw value (a PhpRef stays one), key, raw value... An unset declared property isn't
     * there.
     */
    @TruffleBoundary
    public Object[] propertyTable() {
        Object[] names = propertyNames();
        Object[] table = new Object[names.length * 2];
        int i = 0;
        for (PhpClass.PropertyDecl decl : phpClass.getProperties()) {
            Object value = getProperty(decl.name());
            if (value != null) {
                table[i++] = phpClass.mangledName(decl.name());
                table[i++] = value;
            }
        }
        for (Object name : names) {
            String property = (String) name;
            if (!phpClass.isDeclaredProperty(property)) {
                table[i++] = property;
                table[i++] = getProperty(property);
            }
        }
        return i == table.length ? table : java.util.Arrays.copyOf(table, i);
    }

    /**
     * (array) and get_mangled_object_vars(): the property table (see {@link #propertyTable}) as an array, its
     * numeric keys as ints; a reference something else also holds stays one (zend_proptable_to_symtable).
     */
    @TruffleBoundary
    public PhpArray toArray() {
        Object[] table = propertyTable();
        PhpArray array = PhpArray.ofHashCapacity(table.length / 2);
        for (int i = 0; i < table.length; i += 2) {
            Object key = ArrayKey.of(Str.ofKey((String) table[i]));
            Object value = table[i + 1];
            if (value instanceof PhpRef ref && ref.isShared()) {
                array.putRef(key, ref);
            } else if (((String) table[i]).startsWith("\0")) {
                // a dynamic property's name may look like a mangled one: put() keeps the keys distinct
                array.put(key, PhpRef.deref(value));
            } else {
                array.addEntry(key, PhpArray.hashOf(key), PhpRef.deref(value));
            }
        }
        return array;
    }

    /**
     * The names of the properties code in the scope may access, in order (zend_check_property_access): a parent's
     * private one only from that parent (canAccessProperty lets other scopes take its name for a dynamic property,
     * which this object doesn't have).
     */
    @TruffleBoundary
    public List<String> visiblePropertyNames(String scope) {
        List<String> names = new ArrayList<>();
        for (Object name : propertyNames()) {
            String property = (String) name;
            PhpClass.PropertyDecl decl = phpClass.findProperty(property);
            if (decl != null && decl.visibility() == PhpClass.PropertyDecl.PRIVATE
                    && !decl.declaringClass().equals(scope) && !decl.declaringClass().equals(phpClass.getLowerName())) {
                continue;
            }
            if (phpClass.canAccessProperty(property, scope)) {
                names.add(property);
            }
        }
        return names;
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
            throw new PhpError("Call to undefined method " + phpClass.getName() + "::" + lowerName + "()");
        }
        return method.invoke(this, phpClass, args);
    }

    /** callMethod for a node's operation (ArrayAccess, iteration): the node is where traces show the call made. */
    @TruffleBoundary
    public Object callMethod(Node site, String lowerName, Object... args) {
        return Backtrace.callFrom(site, () -> callMethod(lowerName, args));
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
