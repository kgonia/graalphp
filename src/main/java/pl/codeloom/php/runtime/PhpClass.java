package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Shape;
import pl.codeloom.php.PhpContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A PHP class, interface or enum after linking: inherited methods, constants and properties are flattened in, so
 * lookups never walk the parent chain. Names are kept as written; maps are keyed by lowercase method names and
 * case-sensitive constant and property names, as PHP.
 */
public final class PhpClass {
    /**
     * A declared instance property: its default (or a thunk evaluating the constant expression), its visibility,
     * and the class that declared it (lowercase; set when a class adds it).
     */
    public record PropertyDecl(String name, Object defaultValue, int visibility, String declaringClass) {
        public static final int PUBLIC = 0;
        public static final int PROTECTED = 1;
        public static final int PRIVATE = 2;

        public PropertyDecl(String name, Object defaultValue, int visibility) {
            this(name, defaultValue, visibility, null);
        }

        public boolean isPublic() {
            return visibility == PUBLIC;
        }

        /** The visibility from php-ast's modifier flags (public 1, protected 2, private 4; none is public). */
        public static int visibilityOf(int flags) {
            return (flags & 4) != 0 ? PRIVATE : (flags & 2) != 0 ? PROTECTED : PUBLIC;
        }
    }

    /**
     * The flags a declared property has in an object's shape: which properties a site has to check access to. A
     * private property of an ancestor has none: php-src treats it from other scopes as an undeclared property,
     * which this runtime's one slot per name approximates by the inherited one.
     */
    public static final int PROPERTY_PROTECTED = 1;
    public static final int PROPERTY_PRIVATE = 2;

    private final String name;
    private final String lowerName;
    private final PhpClass parent;
    private final boolean isInterface;
    private final boolean isAbstract;
    private final Set<String> ancestors = new HashSet<>();
    private final Map<String, PhpFunction> methods = new HashMap<>();
    private final Map<String, Object> constants = new LinkedHashMap<>();
    private final Map<String, PhpRef> staticProperties = new LinkedHashMap<>();
    private final Map<String, PropertyDecl> properties = new LinkedHashMap<>();
    private final Shape rootShape;
    /** What declared this class: its ClassTemplate, or a built-in class's name (see {@link ClassKey}). */
    private final Object declaration;
    private final List<PhpClass> interfaces = new ArrayList<>();
    /** The traits whose members this class imported, nested ones included, in import order. */
    private final List<PhpClass> traits = new ArrayList<>();
    /** The language-level identity inline caches key on; made on first use, once the class is complete. */
    private ClassKey key;
    /** The property defaults, evaluated once (declaredDefaults order). */
    private Object[] defaults;

    @CompilationFinal private PhpFunction constructor;

    /**
     * The zend_object_handlers a built-in class replaces, as ext/curl's classes do: get_constructor's Error for
     * `new` (null: `new` works), clone_obj (null: the standard copy, unless uncloneable: clone_obj NULL),
     * cast_object's (int) cast to the object's id, whether serialize() and unserialize() refuse it
     * (@not-serializable), and compare as zend_objects_not_comparable (equal to nothing but itself).
     */
    public record ObjectHandlers(String constructError, java.util.function.UnaryOperator<PhpObject> cloneObject,
                                 boolean uncloneable, boolean castsToId, boolean notSerializable,
                                 boolean notComparable) {
    }

    /** Set once, when a built-in class is made; `new` sites read it in their guards, so it folds. */
    @CompilationFinal private ObjectHandlers handlers;

    public PhpClass(String name, PhpClass parent, boolean isInterface, boolean isAbstract, Shape rootShape,
                    Object declaration) {
        this.declaration = declaration;
        this.name = name;
        this.lowerName = Str.lower(name);
        this.parent = parent;
        this.isInterface = isInterface;
        this.isAbstract = isAbstract;
        this.rootShape = rootShape;
        ancestors.add(lowerName);
        if (parent != null) {
            ancestors.addAll(parent.ancestors);
            methods.putAll(parent.methods);
            constants.putAll(parent.constants);
            staticProperties.putAll(parent.staticProperties);
            properties.putAll(parent.properties);
            constructor = parent.constructor;
        }
    }

    public String getName() {
        return name;
    }

    public String getLowerName() {
        return lowerName;
    }

    public PhpClass getParent() {
        return parent;
    }

    public boolean isInterface() {
        return isInterface;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    /** The built-in class's own object handlers, or null for the standard ones. */
    public ObjectHandlers getHandlers() {
        return handlers;
    }

    public void setHandlers(ObjectHandlers handlers) {
        this.handlers = handlers;
    }

    /** instanceof: this class, a parent, or an implemented interface (lowercase name). */
    @TruffleBoundary
    public boolean isSubclassOf(String lowerClassName) {
        return ancestors.contains(lowerClassName);
    }

    public void addInterface(PhpClass iface) {
        interfaces.add(iface);
        ancestors.addAll(iface.ancestors);
        for (Map.Entry<String, Object> constant : iface.constants.entrySet()) {
            constants.putIfAbsent(constant.getKey(), constant.getValue());
        }
        for (Map.Entry<String, PhpFunction> method : iface.methods.entrySet()) {
            methods.putIfAbsent(method.getKey(), method.getValue());
        }
    }

    /** Records a trait this class imports members from: the trait is part of the class's key. */
    public void addTrait(PhpClass trait) {
        traits.add(trait);
    }

    /** What declared this class: its ClassTemplate, or a built-in class's name. */
    public Object getDeclaration() {
        return declaration;
    }

    public void addMethod(PhpFunction method) {
        addMethod(method.getName(), method);
    }

    /** Adds a method under a name: its own, or a trait alias (`use T { m as n; }`). */
    public void addMethod(String name, PhpFunction method) {
        String key = Str.lower(name);
        methods.put(key, method);
        if (key.equals("__construct")) {
            constructor = method;
        }
    }

    @TruffleBoundary
    public PhpFunction findMethod(String lowerMethodName) {
        return methods.get(lowerMethodName);
    }

    public PhpFunction getConstructor() {
        return constructor;
    }

    public void addConstant(String constantName, Object valueOrThunk) {
        constants.put(constantName, valueOrThunk);
    }

    /** A class constant's value, evaluating its initializer on first use; null if the constant doesn't exist. */
    @TruffleBoundary
    public Object getConstant(String constantName) {
        Object value = constants.get(constantName);
        if (value instanceof Thunk thunk) {
            value = thunk.evaluate();
            constants.put(constantName, value);
        }
        return value;
    }

    public void addStaticProperty(String propertyName, Object initial) {
        PhpRef ref = new PhpRef(initial);
        ref.bind();
        staticProperties.put(propertyName, ref);
    }

    @TruffleBoundary
    public PhpRef getStaticProperty(String propertyName) {
        PhpRef ref = staticProperties.get(propertyName);
        if (ref != null && ref.get() instanceof Thunk thunk) {
            ref.set(thunk.evaluate());
        }
        return ref;
    }

    /** The declaration holds its default array, so objects created from it copy before writing. */
    public void addProperty(PropertyDecl decl) {
        if (decl.defaultValue() instanceof PhpArray array) {
            array.addHolder();
        }
        properties.put(decl.name(), decl.declaringClass() != null ? decl
                : new PropertyDecl(decl.name(), decl.defaultValue(), decl.visibility(), lowerName));
    }

    /** The declared (or inherited) instance property, or null for an undeclared one. */
    @TruffleBoundary
    public PropertyDecl findProperty(String propertyName) {
        return properties.get(propertyName);
    }

    /**
     * Whether code in the scope (a class's lowercase name, or null outside classes) may access the declared
     * property (zend_get_property_offset): a private one only from its class, a protected one from a class related
     * to the one that first declared it (is_protected_compatible_scope).
     */
    @TruffleBoundary
    public boolean canAccessProperty(String propertyName, String scope) {
        PropertyDecl decl = properties.get(propertyName);
        if (decl == null || decl.isPublic()) {
            return true;
        }
        if (scope == null) {
            return decl.visibility() == PropertyDecl.PRIVATE && !decl.declaringClass().equals(lowerName);
        }
        if (decl.visibility() == PropertyDecl.PRIVATE) {
            if (decl.declaringClass().equals(scope) || !decl.declaringClass().equals(lowerName)) {
                return true;
            }
            // the scope's own private property, which this subclass declares again (ZEND_ACC_CHANGED): php-src
            // gives the scope its own (zend_get_parent_private_property); here both share the one slot
            PhpClass scopeClass = PhpContext.get(null).findClass(scope);
            return scopeClass != null && isSubclassOf(scope)
                    && scopeClass.properties.get(propertyName) instanceof PropertyDecl own
                    && own.visibility() == PropertyDecl.PRIVATE && scope.equals(own.declaringClass());
        }
        PhpClass root = protectedRoot(propertyName);
        PhpClass scopeClass = PhpContext.get(null).findClass(scope);
        return root.isSubclassOf(scope) || scopeClass != null && scopeClass.isSubclassOf(root.lowerName);
    }

    /** The topmost class declaring a protected property (zend_get_property_info's prototype for the check). */
    private PhpClass protectedRoot(String propertyName) {
        PhpClass root = this;
        while (root.parent != null && root.parent.properties.get(propertyName) instanceof PropertyDecl inherited
                && inherited.visibility() == PropertyDecl.PROTECTED) {
            root = root.parent;
        }
        return root;
    }

    /**
     * Whether canAccessProperty's answer from the scope (the class the code is written in) follows from this
     * class's ClassKey, so a site may keep it for every class with the key, in every context. It does unless a
     * protected property is reached through the scope's own ancestors: they depend on the parent the scope's
     * class was declared with in each context, which this key doesn't fix.
     */
    @TruffleBoundary
    public boolean isPropertyAccessKeyed(String propertyName, String scope) {
        PropertyDecl decl = properties.get(propertyName);
        if (decl == null || decl.visibility() != PropertyDecl.PROTECTED || scope == null) {
            return true;
        }
        return protectedRoot(propertyName).isSubclassOf(scope);
    }

    public Iterable<PropertyDecl> getProperties() {
        return properties.values();
    }

    /** A declared (or inherited) instance property, as opposed to a dynamic one. */
    @TruffleBoundary
    public boolean isDeclaredProperty(String propertyName) {
        return properties.containsKey(propertyName);
    }

    @TruffleBoundary
    public boolean isPublicProperty(String propertyName) {
        PropertyDecl decl = properties.get(propertyName);
        return decl == null || decl.isPublic();
    }

    /**
     * A new object with declared properties at their defaults, in declaration order (no constructor call). A
     * Throwable records the PHP code running (a built-in's caller) as where it was made.
     */
    @TruffleBoundary
    public PhpObject instantiate() {
        PhpObject object = instantiateBare(false);
        if (isThrowable()) {
            initThrowable(object, SourceLocation.current(), Backtrace.forThrowable());
        }
        return object;
    }

    /** As instantiate(); a Throwable is made at location with trace. */
    @TruffleBoundary
    public PhpObject instantiate(SourceLocation location, PhpArray trace) {
        PhpObject object = instantiateBare(false);
        if (isThrowable()) {
            initThrowable(object, location, trace);
        }
        return object;
    }

    public boolean isThrowable() {
        return ancestors.contains("throwable");
    }

    /**
     * zend_default_exception_new: a Throwable's file, line and trace are where it was made, whatever its
     * constructor does.
     */
    @TruffleBoundary
    public static void initThrowable(PhpObject throwable, SourceLocation location, PhpArray trace) {
        throwable.setProperty("file", Str.of(location.file()));
        throwable.setProperty("line", (long) location.line());
        trace.addHolder();
        throwable.setProperty("trace", trace);
    }

    /**
     * object_init_ex for a built-in class whose get_constructor refuses `new` (curl_init's CurlHandle): the object,
     * which the built-in then gives its native state.
     */
    @TruffleBoundary
    public PhpObject instantiateInternal() {
        return instantiateBare(true);
    }

    private PhpObject instantiateBare(boolean internal) {
        if (!internal && handlers != null && handlers.constructError() != null) {
            throw new PhpError(handlers.constructError());
        }
        if (isAbstract || isInterface) {
            throw new PhpError("Cannot instantiate " + (isInterface ? "interface " : "abstract class ") + name);
        }
        PhpObject object = new PhpObject(rootShape, this, PhpContext.get(null).nextObjectId());
        DynamicObjectLibrary library = DynamicObjectLibrary.getUncached();
        String[] names = declaredNames();
        Object[] defaults = declaredDefaults();
        int[] flags = declaredFlags();
        for (int i = 0; i < names.length; i++) {
            Object value = defaults[i];
            if (value instanceof PhpArray array) {
                array.addHolder();
            }
            if (flags[i] != 0) {
                library.putWithFlags(object, names[i], value, flags[i]);
            } else if (value instanceof Long l) {
                library.putLong(object, names[i], l);
            } else {
                library.put(object, names[i], value);
            }
        }
        return object;
    }

    /**
     * The language-level identity of this class: equal for the same declarations in every context. Made on first
     * use without leaving compiled code: each request declares its classes anew, so a transfer to the interpreter
     * here deoptimized every compiled `new` and static call once per request, and they were compiled again.
     */
    public ClassKey getKey() {
        ClassKey k = key;
        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, k == null)) {
            k = makeKey();
        }
        return k;
    }

    @TruffleBoundary
    private ClassKey makeKey() {
        key = PhpContext.get(null).getLanguage().classKey(new ClassKey.Id(declaration,
                parent == null ? null : parent.getKey(), keys(interfaces), keys(traits)));
        return key;
    }

    private static List<ClassKey> keys(List<PhpClass> classes) {
        List<ClassKey> keys = new ArrayList<>(classes.size());
        for (PhpClass phpClass : classes) {
            keys.add(phpClass.getKey());
        }
        return List.copyOf(keys);
    }

    /**
     * The property defaults in declaredNames order, evaluated on first use and kept (don't modify). Like getKey,
     * this stays in compiled code: compiled `new` reaches it once per request with the request's own class.
     */
    public Object[] defaults() {
        Object[] values = defaults;
        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, values == null)) {
            values = declaredDefaults();
            defaults = values;
        }
        return values;
    }

    public boolean isInstantiable() {
        return !isAbstract && !isInterface && (handlers == null || handlers.constructError() == null);
    }

    public Shape getRootShape() {
        return rootShape;
    }

    /** Declared (and inherited) instance property names, in declaration order. */
    @TruffleBoundary
    public String[] declaredNames() {
        return properties.keySet().toArray(new String[0]);
    }

    /** The properties' shape flags (PROPERTY_PROTECTED, PROPERTY_PRIVATE) in an object of this class. */
    @TruffleBoundary
    public int[] declaredFlags() {
        int[] flags = new int[properties.size()];
        int i = 0;
        for (PropertyDecl decl : properties.values()) {
            flags[i++] = decl.visibility() == PropertyDecl.PROTECTED ? PROPERTY_PROTECTED
                    : decl.visibility() == PropertyDecl.PRIVATE && lowerName.equals(decl.declaringClass())
                    ? PROPERTY_PRIVATE : 0;
        }
        return flags;
    }

    /**
     * The properties' default values, in declaredNames order. Deferred defaults (constant expressions) are
     * evaluated on first use and kept; an array default holds one reference for the declaration itself.
     */
    @TruffleBoundary
    public Object[] declaredDefaults() {
        Object[] values = new Object[properties.size()];
        int i = 0;
        for (Map.Entry<String, PropertyDecl> entry : properties.entrySet()) {
            Object value = entry.getValue().defaultValue();
            if (value instanceof Thunk thunk) {
                value = thunk.evaluate();
                if (value instanceof PhpArray array) {
                    array.addHolder();
                }
                entry.setValue(new PropertyDecl(entry.getKey(), value, entry.getValue().visibility(),
                        entry.getValue().declaringClass()));
            }
            values[i++] = value;
        }
        return values;
    }

    @Override
    public String toString() {
        return "class " + name;
    }

    /** A deferred constant expression (class constants, property and parameter defaults). */
    public interface Thunk {
        Object evaluate();
    }
}
