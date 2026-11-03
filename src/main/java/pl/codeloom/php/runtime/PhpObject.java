package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.strings.TruffleString;

import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.Args;

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
    /** A lazy object's state, or null for an object that isn't lazy (any more). */
    private Lazy lazy;

    /**
     * A lazy object's state (zend_lazy_object_info, PHP 8.4's ReflectionClass::newLazyGhost() and newLazyProxy()):
     * its initializer, and once a proxy is initialized its real instance. An uninitialized one's shape has only
     * the properties made non-lazy (skipLazyInitialization()...), an initialized proxy's none, so every other
     * access takes the property sites' slow paths, which initialize it or forward to the instance.
     */
    public static final class Lazy {
        /** ReflectionClass's options: SKIP_INITIALIZATION_ON_SERIALIZE, SKIP_DESTRUCTOR. */
        public static final int SKIP_INITIALIZATION_ON_SERIALIZE = 8;
        public static final int SKIP_DESTRUCTOR = 16;

        private final Object initializer;
        private final boolean proxy;
        private final int options;
        /** An initialized proxy's real instance. */
        private PhpObject instance;
        /** While the initializer runs, when the object isn't uninitialized (IS_OBJ_LAZY_UNINITIALIZED is off). */
        private boolean initializing;

        Lazy(Object initializer, boolean proxy, int options) {
            this.initializer = initializer;
            this.proxy = proxy;
            this.options = options;
        }

        public Object initializer() {
            return initializer;
        }

        public boolean isProxy() {
            return proxy;
        }

        public int options() {
            return options;
        }

        public PhpObject instance() {
            return instance;
        }
    }

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
            return keepRef ? phpClass.findMethod(lowerMethod).invokeRef(this, phpClass, arguments)
                    : callMethod(lowerMethod, arguments);
        } finally {
            magicGuards.remove(guard);
        }
    }

    /** Whether the magic method (lower case) for the property is running on this object: its guard is set. */
    @TruffleBoundary
    public boolean inMagic(String lowerMethod, String name) {
        return magicGuards != null && magicGuards.contains(lowerMethod + ":" + name);
    }

    /**
     * The access with the magic method's guard for the property set, as php-src sets the guard a lazy object's
     * access had on the instance it forwards that access to.
     */
    @TruffleBoundary
    public <T> T guarded(String lowerMethod, String name, java.util.function.Supplier<T> access) {
        String guard = lowerMethod + ":" + name;
        if (magicGuards == null) {
            magicGuards = new java.util.HashSet<>();
        }
        boolean added = magicGuards.add(guard);
        try {
            return access.get();
        } finally {
            if (added) {
                magicGuards.remove(guard);
            }
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
        PhpObject real = initialized();
        if (real != this) {
            return real.visibleProperties(scope);
        }
        PhpArray result = new PhpArray();
        for (String property : visiblePropertyNames(scope)) {
            Object value = visibleValue(property);
            if (value != null) {
                result.put(ArrayKey.of(Str.ofKey(property)), value);
            }
        }
        return result;
    }

    /**
     * A property's value as get_object_vars() and foreach see it, dereferenced: a hooked one's get hook's
     * (zend_read_property_ex), else its own; null when unset.
     */
    @TruffleBoundary
    public Object visibleValue(String name) {
        PhpClass.Hooks hooks = phpClass.findHooks(name);
        if (hooks != null && hooks.get() != null) {
            return hooks.get().invoke(this, phpClass);
        }
        return PhpRef.deref(getProperty(name));
    }

    /**
     * The properties as php-src's property table holds them: the declared ones in declaration order (a parent's
     * first) under their mangled keys ({@link PhpClass#mangledName}), then the dynamic ones in the order they
     * were added; as key, raw value (a PhpRef stays one), key, raw value... An unset declared property isn't
     * there.
     */
    @TruffleBoundary
    public Object[] propertyTable() {
        if (lazy != null && lazy.instance != null) {
            // an initialized proxy's are its instance's (zend_std_get_properties)
            return lazy.instance.propertyTable();
        }
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
        for (Object name : phpClass.hasHooks() ? hookedPropertyNames() : propertyNames()) {
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

    /**
     * foreach's walk over the properties code in scope may access: the name at index of those met so far, null past
     * the last. At the end the ones added meanwhile are appended, as php-src walks the live property table.
     */
    @TruffleBoundary
    public String visiblePropertyAt(List<String> names, int index, String scope) {
        if (index == names.size()) {
            java.util.Set<String> seen = new java.util.HashSet<>(names);
            for (String name : visiblePropertyNames(scope)) {
                if (seen.add(name)) {
                    names.add(name);
                }
            }
        }
        return index < names.size() ? names.get(index) : null;
    }

    /**
     * The properties of an object whose class has hooks, as zho_build_properties lists them: the declared ones in
     * declaration order (a virtual one with a get hook too), then the dynamic ones.
     */
    private Object[] hookedPropertyNames() {
        List<Object> names = new ArrayList<>();
        for (PhpClass.PropertyDecl decl : phpClass.getProperties()) {
            PhpClass.Hooks hooks = phpClass.findHooks(decl.name());
            if (hooks != null ? !hooks.virtual() || hooks.get() != null : getProperty(decl.name()) != null) {
                names.add(decl.name());
            }
        }
        for (Object name : propertyNames()) {
            if (!phpClass.isDeclaredProperty((String) name)) {
                names.add(name);
            }
        }
        return names.toArray();
    }

    /**
     * The properties var_export() and json_encode() (publicOnly) read, as name, value pairs: a class with hooks
     * has its get hooks called (zend_hooked_object_build_properties), the others' values as they are.
     */
    @TruffleBoundary
    public List<Object> readableProperties(boolean publicOnly) {
        boolean hooked = phpClass.hasHooks();
        List<Object> pairs = new ArrayList<>();
        for (Object name : hooked ? hookedPropertyNames() : propertyNames()) {
            if (publicOnly && !phpClass.isPublicProperty((String) name)) {
                continue;
            }
            Object value = hooked ? visibleValue((String) name) : getProperty((String) name);
            if (value != null) {
                pairs.add(name);
                pairs.add(value);
            }
        }
        return pairs;
    }

    /**
     * A property as foreach by reference takes it (zho_declared_it_fetch_current): made a reference in place; a
     * hooked one's by-reference get hook's reference, another hooked one the Error; null for one unset, or a
     * virtual one without a get hook.
     */
    @TruffleBoundary
    public PhpRef propertyRef(String name) {
        PhpClass.Hooks hooks = phpClass.findHooks(name);
        if (hooks != null) {
            if (hooks.get() == null && hooks.virtual()) {
                return null;
            }
            if (hooks.get() == null || !hooks.get().returnsRef()) {
                throw new PhpError("Cannot create reference to property " + phpClass.getName() + "::$"
                        + Str.keyText(name));
            }
            Object value = hooks.get().invokeRef(this, phpClass);
            return value instanceof PhpRef ref ? ref : new PhpRef(value);
        }
        Object old = getProperty(name);
        if (old == null || old instanceof PhpRef) {
            return (PhpRef) old;
        }
        PhpRef ref = new PhpRef(old);
        if (old instanceof PhpArray array) {
            array.removeHolder();
        }
        ref.bind();
        DynamicObjectLibrary.getUncached().put(this, name, ref);
        return ref;
    }

    /** The lazy state: null for an object that isn't lazy, an initialized ghost included. */
    public Lazy lazy() {
        return lazy;
    }

    /** ReflectionClass::isUninitializedLazyObject(): lazy, and neither initialized nor initializing. */
    public boolean isUninitializedLazy() {
        return lazy != null && lazy.instance == null && !lazy.initializing;
    }

    /**
     * Makes the object lazy (zend_object_make_lazy): every declared property loses its value, but a skipped
     * readonly one... (not modelled); its dynamic ones go. An object without declared properties stays as it is.
     */
    @TruffleBoundary
    public void makeLazy(Object initializer, boolean proxy, int options) {
        for (Object name : propertyNames()) {
            Object old = getProperty((String) name);
            if (old instanceof PhpArray array) {
                array.removeHolder();
            }
            removeProperty((String) name);
        }
        lazy = phpClass.declaredNames().length == 0 ? null : new Lazy(initializer, proxy, options);
    }

    /** Whether reset can't apply: the object's initializer is running (a stale info, in php-src). */
    public boolean isInitializingLazy() {
        return lazy != null && lazy.initializing;
    }

    /**
     * The object a property access on this lazy one goes to (zend_lazy_object_init): an uninitialized ghost is
     * initialized in place, a proxy's factory makes its real instance; an initialized proxy's instance. An
     * initializer's exception leaves the object as it was.
     */
    @TruffleBoundary
    public PhpObject initializeLazy() {
        Lazy state = lazy;
        if (state.instance != null) {
            return state.instance.lazy != null ? state.instance.initializeLazy() : state.instance;
        }
        PhpContext context = PhpContext.get(null);
        state.initializing = true;
        if (state.proxy) {
            Object made;
            try {
                made = Callables.call(context, state.initializer, this);
            } finally {
                state.initializing = false;
            }
            if (!(made instanceof PhpObject real)) {
                throw new PhpError("TypeError", "Lazy proxy factory must return an instance of a class compatible with "
                        + phpClass.getName() + ", " + Args.valueName(made) + " returned");
            }
            if (!compatibleInstance(real)) {
                throw new PhpError("TypeError", "The real instance class " + real.phpClass.getName() + " is not "
                        + "compatible with the proxy class " + phpClass.getName() + ". The proxy must be a instance of "
                        + "the same class as the real instance, or a sub-class with no additional properties, and no "
                        + "overrides of the __destructor or __clone methods.");
            }
            if (real == this || real.lazy != null) {
                throw new PhpError("Lazy proxy factory must return a non-lazy object");
            }
            state.instance = real;
            makeLazy(null, true, 0);
            lazy = state;
            return real;
        }
        // the properties as they are (the skipped ones), then each lazy one at its default
        Object[] snapshot = snapshot();
        String[] names = phpClass.declaredNames();
        Object[] defaults = phpClass.defaults();
        Object[] values = new Object[names.length];
        for (int i = 0; i < names.length; i++) {
            Object value = getProperty(names[i]);
            if (value == null) {
                value = defaults[i];
                if (value instanceof PhpArray array) {
                    array.addHolder();
                }
            }
            values[i] = value;
        }
        DynamicObjectLibrary library = DynamicObjectLibrary.getUncached();
        for (String name : names) {
            library.removeKey(this, name);
        }
        for (int i = 0; i < names.length; i++) {
            library.putWithFlags(this, names[i], values[i], phpClass.propertyFlags(names[i]));
        }
        Object result;
        try {
            result = Callables.call(context, state.initializer, this);
        } catch (RuntimeException e) {
            restore(snapshot, state);
            throw e;
        }
        if (result != PhpNull.NULL) {
            restore(snapshot, state);
            throw new PhpError("TypeError", "Lazy object initializer must return NULL or no value");
        }
        lazy = null;
        return this;
    }

    /** A proxy's real instance must be of its class, or of a parent with the same properties (zend_lazy_object_compatible). */
    private boolean compatibleInstance(PhpObject real) {
        PhpClass realClass = real.phpClass;
        if (realClass == phpClass) {
            return true;
        }
        if (!phpClass.isSubclassOf(realClass.getLowerName())) {
            return false;
        }
        int own = 0;
        int theirs = 0;
        for (PhpClass.PropertyDecl ignored : phpClass.getProperties()) {
            own++;
        }
        for (PhpClass.PropertyDecl ignored : realClass.getProperties()) {
            theirs++;
        }
        return own == theirs && phpClass.findMethod("__destruct") == realClass.findMethod("__destruct")
                && phpClass.findMethod("__clone") == realClass.findMethod("__clone");
    }

    /** The properties with their flags, in order: name, value, flags... */
    private Object[] snapshot() {
        DynamicObjectLibrary library = DynamicObjectLibrary.getUncached();
        Object[] names = propertyNames();
        Object[] snapshot = new Object[names.length * 3];
        for (int i = 0; i < names.length; i++) {
            Object value = getProperty((String) names[i]);
            if (value instanceof PhpArray array) {
                array.addHolder();
            }
            snapshot[3 * i] = names[i];
            snapshot[3 * i + 1] = value;
            snapshot[3 * i + 2] = library.getPropertyFlagsOrDefault(this, names[i], 0);
        }
        return snapshot;
    }

    /** zend_lazy_object_revert_init: the properties as the snapshot has them, the object lazy again. */
    private void restore(Object[] snapshot, Lazy state) {
        DynamicObjectLibrary library = DynamicObjectLibrary.getUncached();
        for (Object name : propertyNames()) {
            if (getProperty((String) name) instanceof PhpArray array) {
                array.removeHolder();
            }
            library.removeKey(this, name);
        }
        for (int i = 0; i < snapshot.length; i += 3) {
            library.putWithFlags(this, snapshot[i], snapshot[i + 1], (int) snapshot[i + 2]);
        }
        state.initializing = false;
        lazy = state;
    }

    /**
     * zend_lazy_object_mark_as_initialized: the lazy properties at their defaults, without calling the initializer;
     * also when the last lazy property is initialized another way (zend_lazy_object_realize).
     */
    @TruffleBoundary
    public void markLazyInitialized() {
        String[] names = phpClass.declaredNames();
        Object[] defaults = phpClass.defaults();
        Object[] values = new Object[names.length];
        DynamicObjectLibrary library = DynamicObjectLibrary.getUncached();
        for (int i = 0; i < names.length; i++) {
            Object value = getProperty(names[i]);
            if (value == null) {
                value = defaults[i];
                if (value instanceof PhpArray array) {
                    array.addHolder();
                }
            }
            values[i] = value;
            library.removeKey(this, names[i]);
        }
        for (int i = 0; i < names.length; i++) {
            library.putWithFlags(this, names[i], values[i], phpClass.propertyFlags(names[i]));
        }
        lazy = null;
    }

    /**
     * A declared property of an uninitialized lazy object made non-lazy (skipLazyInitialization(),
     * setRawValueWithoutLazyInitialization()): when none is left lazy, the object isn't lazy any more.
     */
    @TruffleBoundary
    public void lazyPropertyInitialized() {
        for (String name : phpClass.declaredNames()) {
            if (getProperty(name) == null) {
                return;
            }
        }
        lazy = null;
    }

    /** The object whose properties serialize() writes: a lazy one initialized, unless its options skip that. */
    public PhpObject forSerialize() {
        return lazy != null && lazy.instance == null && (lazy.options & Lazy.SKIP_INITIALIZATION_ON_SERIALIZE) != 0
                ? this : initialized();
    }

    /** A clone of an initialized lazy proxy (zend_lazy_object_clone): a proxy of its own, of the instance's clone. */
    public void proxyFor(PhpObject instance) {
        Lazy state = new Lazy(null, true, 0);
        state.instance = instance;
        lazy = state;
    }

    /** The object whose properties reads see: an uninitialized lazy one initialized, a proxy's instance. */
    @TruffleBoundary
    public PhpObject initialized() {
        return lazy == null || lazy.initializing ? this : initializeLazy();
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

    /**
     * Whether the typed property was unset (php-src clears its IS_PROP_UNINIT): while it stays uninitialized, its
     * reads and writes go to __get and __set. A hidden key of the class marks it, out of the property table.
     */
    @TruffleBoundary
    public boolean wasUnset(String name) {
        return DynamicObjectLibrary.getUncached().containsKey(this, phpClass.unsetKey(name));
    }

    @TruffleBoundary
    public void setUnset(String name, boolean unset) {
        if (unset) {
            DynamicObjectLibrary.getUncached().put(this, phpClass.unsetKey(name), Boolean.TRUE);
        } else {
            DynamicObjectLibrary.getUncached().removeKey(this, phpClass.unsetKey(name));
        }
    }

    /** Stores the property; an uninitialized typed one gets its declaration's flags. */
    @TruffleBoundary
    public void setProperty(String name, Object value) {
        DynamicObjectLibrary library = DynamicObjectLibrary.getUncached();
        PhpClass.PropertyDecl decl = phpClass.findProperty(name);
        if (decl != null && decl.type() != null && !library.containsKey(this, name)) {
            library.putWithFlags(this, name, value, phpClass.propertyFlags(decl));
        } else {
            library.put(this, name, value);
        }
    }

    /**
     * A write of the property's own value, as a plain one (a hooked property's backing value): through a reference
     * it holds, else stored with the slot's flags, the holders of the arrays moved.
     */
    @TruffleBoundary
    public void writeRaw(String name, Object value) {
        Object old = getProperty(name);
        if (old instanceof PhpRef ref) {
            ref.set(value);
            return;
        }
        if (value instanceof PhpArray array) {
            array.addHolder();
        }
        DynamicObjectLibrary.getUncached().putWithFlags(this, name, value, phpClass.propertyFlags(name));
        if (old instanceof PhpArray array && old != value) {
            array.removeHolder();
        }
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
