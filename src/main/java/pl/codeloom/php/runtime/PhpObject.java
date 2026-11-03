package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;

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
    /**
     * The magic property accesses in progress ("__get:name", ...): inside __get('x'), $this->x is the real one.
     * Also the states of typed properties besides initialized and uninitialized (IS_PROP_UNINIT, which a typed
     * property this object doesn't have is in): "unset:name" once unset() took one out of it, "reinit:name" while
     * __clone may set a readonly one again (IS_PROP_REINITABLE).
     */
    private java.util.Set<String> guards;

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
        return magic(lowerMethod, name, false, args);
    }

    /** __get for a write fetch (read_property(BP_VAR_W)): a reference `&__get` returns stays one. */
    @TruffleBoundary
    public Object magicGetRef(Node site, String name) {
        return Backtrace.callFrom(site, () -> magic("__get", name, true));
    }

    private Object magic(String lowerMethod, String name, boolean keepRef, Object... args) {
        if (phpClass.findMethod(lowerMethod) == null) {
            return NO_MAGIC;
        }
        String guard = lowerMethod + ":" + name;
        if (guards == null) {
            guards = new java.util.HashSet<>();
        }
        if (!guards.add(guard)) {
            return NO_MAGIC;
        }
        try {
            Object[] arguments = new Object[args.length + 1];
            arguments[0] = Str.ofKey(name);
            System.arraycopy(args, 0, arguments, 1, args.length);
            return keepRef ? phpClass.findMethod(lowerMethod).invokeRef(this, phpClass, arguments)
                    : callMethod(lowerMethod, arguments);
        } finally {
            guards.remove(guard);
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

    /** Adds a state to {@link #guards}. */
    @TruffleBoundary
    public void mark(String state) {
        if (guards == null) {
            guards = new java.util.HashSet<>();
        }
        guards.add(state);
    }

    /** Takes a state out of {@link #guards}: whether it was there. */
    @TruffleBoundary
    public boolean unmark(String state) {
        return guards != null && guards.remove(state);
    }

    /** A declared typed property this object doesn't have and never unset: IS_PROP_UNINIT, no magic method runs. */
    @TruffleBoundary
    public boolean isUninitialized(String name) {
        PhpClass.PropertyDecl decl = phpClass.findProperty(name);
        return decl != null && decl.type() != null && (guards == null || !guards.contains("unset:" + name))
                && !DynamicObjectLibrary.getUncached().containsKey(this, name);
    }

    /** Puts a property with its shape flags: a declared one's visibility and set checks, none for a dynamic one. */
    @TruffleBoundary
    public void initProperty(String name, Object value) {
        PhpClass.PropertyDecl decl = phpClass.findProperty(name);
        DynamicObjectLibrary.getUncached().putWithFlags(this, name, value,
                decl == null ? 0 : phpClass.propertyFlags(decl));
    }

    /**
     * Reading a property this object doesn't have (zend_std_read_property): __get's result, unless the property is
     * uninitialized; then for a typed one an Error, else null.
     */
    @TruffleBoundary
    public Object readMissing(Node site, String name) {
        if (badName(name, "__get")) {
            return PhpNull.NULL;
        }
        if (!isUninitialized(name)) {
            Object magic = magicProperty(site, "__get", name);
            if (magic != NO_MAGIC) {
                return magic;
            }
        }
        PhpClass.PropertyDecl decl = phpClass.findProperty(name);
        if (decl != null && decl.type() != null) {
            throw new PhpError("Typed property " + PhpClass.printed(phpClass.declaringClassName(decl)) + "::$" + Str.keyText(name)
                    + " must not be accessed before initialization");
        }
        return PhpNull.NULL;
    }

    /**
     * Writing a property this object doesn't have (zend_std_write_property): __set, unless the property is
     * uninitialized; else a declared one is put, checked as php-src's write_std_property, and true returned. False
     * for a dynamic property the caller puts, if the class allows them.
     */
    @TruffleBoundary
    public boolean writeMissing(Node site, String scope, String name, Object value) {
        if (badName(name, "__set")) {
            return true;
        }
        if (!isUninitialized(name) && magicProperty(site, "__set", name, value) != NO_MAGIC) {
            return true;
        }
        PhpClass.PropertyDecl decl = phpClass.findProperty(name);
        if (decl == null) {
            if (phpClass.forbidsDynamicProperties()) {
                throw new PhpError("Cannot create dynamic property " + PhpClass.printed(phpClass.getName()) + "::$"
                        + Str.keyText(name));
            }
            return false;
        }
        checkSetAccess(decl, scope, "modify");
        if (value instanceof PhpArray array) {
            array.addHolder();
        }
        initProperty(name, value);
        return true;
    }

    /**
     * zend_get_property_offset's check of a name that isn't declared: one starting with a NUL byte (a mangled
     * name) is an Error, or ignored (true) when the class has the magic method.
     */
    private boolean badName(String name, String magic) {
        if (name.isEmpty() || name.charAt(0) != 0 || phpClass.findProperty(name) != null) {
            return false;
        }
        if (phpClass.findMethod(magic) == null) {
            throw new PhpError("Cannot access property starting with \"\\0\"");
        }
        return true;
    }

    /** After unset() of a property it had: a typed one is no longer uninitialized, so magic methods apply. */
    @TruffleBoundary
    public void unsetDeclared(String name) {
        PhpClass.PropertyDecl decl = phpClass.findProperty(name);
        if (decl != null && decl.type() != null) {
            mark("unset:" + name);
        }
    }

    /** unset() of a declared property this object doesn't have and no __unset took: its set visibility. */
    @TruffleBoundary
    public void unsetMissing(String scope, String name) {
        PhpClass.PropertyDecl decl = phpClass.findProperty(name);
        if (decl != null) {
            checkSetAccess(decl, scope, "unset");
        }
    }

    /** unset() of a property this object doesn't have: true when it was uninitialized, which it no longer is. */
    @TruffleBoundary
    public boolean unsetUninitialized(String scope, String name) {
        if (!isUninitialized(name)) {
            return false;
        }
        checkSetAccess(phpClass.findProperty(name), scope, "unset");
        mark("unset:" + name);
        return true;
    }

    /**
     * A write (operation "modify") or unset ("unset") of a property with PROPERTY_SET_CHECKED from the scope: a
     * readonly one only while __clone may set it again, then its set visibility.
     */
    @TruffleBoundary
    public void checkModify(String name, String scope, String operation) {
        PhpClass.PropertyDecl decl = phpClass.findProperty(name);
        if (decl.isReadonly() && !unmark("reinit:" + name)) {
            throw new PhpError("Cannot " + operation + " readonly property " + PhpClass.printed(phpClass.declaringClassName(decl))
                    + "::$" + Str.keyText(name));
        }
        checkSetAccess(decl, scope, operation);
    }

    /** zend_readonly_property_indirect_modification_error, for a readonly property this object has or not. */
    @TruffleBoundary
    public PhpError indirectModification(String name) {
        PhpClass.PropertyDecl decl = phpClass.findProperty(name);
        return new PhpError("Cannot indirectly modify readonly property " + PhpClass.printed(phpClass.declaringClassName(decl))
                + "::$" + Str.keyText(name));
    }

    /**
     * A reference to, or a write into, a declared property (BP_VAR_W, RW, UNSET) holding old (null: none) from the
     * scope: true when it's readonly or the scope may not set it, which is an Error unless it holds an object
     * (zend_std_read_property's indirect modification errors).
     */
    @TruffleBoundary
    public boolean indirect(String name, Object old, String scope) {
        PhpClass.PropertyDecl decl = phpClass.findProperty(name);
        boolean restricted = decl != null && (decl.isReadonly() || !hasSetAccess(decl, scope));
        if (restricted && !(PhpRef.deref(old) instanceof PhpObject)) {
            throw indirectError(name, scope);
        }
        return restricted;
    }

    /** The indirect modification Error of a readonly property, or of one the scope may not set. */
    @TruffleBoundary
    public PhpError indirectError(String name, String scope) {
        PhpClass.PropertyDecl decl = phpClass.findProperty(name);
        return decl.isReadonly() ? indirectModification(name) : setAccessError(decl, scope, "indirectly modify");
    }

    /** Whether the property is a declared readonly one. */
    @TruffleBoundary
    public boolean isReadonly(String name) {
        PhpClass.PropertyDecl decl = phpClass.findProperty(name);
        return decl != null && decl.isReadonly();
    }

    /**
     * zend_asymmetric_property_has_set_access: the declaring class, or for protected(set) (a readonly property's
     * implied one) a scope related to the class that first declared the property (is_protected_compatible_scope).
     */
    private void checkSetAccess(PhpClass.PropertyDecl decl, String scope, String operation) {
        if (!hasSetAccess(decl, scope)) {
            throw setAccessError(decl, scope, operation);
        }
    }

    private boolean hasSetAccess(PhpClass.PropertyDecl decl, String scope) {
        if (!decl.restrictsSet() || decl.declaringClass().equals(scope)) {
            return true;
        }
        PhpClass scopeClass = scope == null ? null : PhpContext.get(null).findClass(scope);
        if ((decl.modifiers() & PhpClass.PropertyDecl.PRIVATE_SET) == 0 && scopeClass != null) {
            PhpClass root = phpClass;
            while (root.getParent() != null && root.getParent().isDeclaredProperty(decl.name())) {
                root = root.getParent();
            }
            return root.isSubclassOf(scope) || scopeClass.isSubclassOf(root.getLowerName());
        }
        return false;
    }

    private PhpError setAccessError(PhpClass.PropertyDecl decl, String scope, String operation) {
        PhpClass scopeClass = scope == null ? null : PhpContext.get(null).findClass(scope);
        String visibility = (decl.modifiers() & PhpClass.PropertyDecl.PRIVATE_SET) != 0 ? "private(set)"
                : decl.isReadonly() ? "protected(set) readonly" : "protected(set)";
        return new PhpError("Cannot " + operation + " " + visibility + " property " + PhpClass.printed(phpClass.declaringClassName(decl))
                + "::$" + Str.keyText(decl.name()) + " from " + (scopeClass == null ? "global scope"
                : "scope " + PhpClass.printed(scopeClass.getName())));
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

    /** callMethod keeping a reference the method returns (`&offsetGet` for a write fetch), called from site. */
    @TruffleBoundary
    public Object callMethodRef(Node site, String lowerName, Object... args) {
        return Backtrace.callFrom(site, () -> phpClass.findMethod(lowerName).invokeRef(this, phpClass, args));
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
