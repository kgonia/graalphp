package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Shape;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;

import java.util.ArrayList;
import com.oracle.truffle.api.object.HiddenKey;
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
     * A declared instance property: its default (or a thunk evaluating the constant expression; null for a typed
     * property without one, which starts uninitialized: not in the object), its visibility, the class that
     * declared it (lowercase; set when a class adds it), and its type, or null.
     */
    public record PropertyDecl(String name, Object defaultValue, int visibility, String declaringClass,
                               PhpType type) {
        public static final int PUBLIC = 0;
        public static final int PROTECTED = 1;
        public static final int PRIVATE = 2;

        public PropertyDecl(String name, Object defaultValue, int visibility) {
            this(name, defaultValue, visibility, null, null);
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
    /** A typed property: its writes are checked against its type, and it may be uninitialized (absent). */
    public static final int PROPERTY_TYPED = 4;

    private final String name;
    private final String lowerName;
    private final PhpClass parent;
    private final boolean isInterface;
    private final boolean isAbstract;
    private final Set<String> ancestors = new HashSet<>();
    private final Map<String, PhpFunction> methods = new LinkedHashMap<>();
    private final Map<String, Object> constants = new LinkedHashMap<>();
    /** The constants' types and declaring classes. */
    private final Map<String, ConstantType> constantTypes = new HashMap<>();

    /** A class constant's type (null when it has none), the class that declared it, whether it is private. */
    public record ConstantType(PhpType type, PhpClass declaringClass, boolean isPrivate) {
    }

    /** The hidden keys marking an object's unset typed properties (PhpObject.wasUnset), by property name. */
    private Map<String, HiddenKey> unsetKeys;

    @TruffleBoundary
    public HiddenKey unsetKey(String propertyName) {
        if (unsetKeys == null) {
            unsetKeys = new HashMap<>();
        }
        return unsetKeys.computeIfAbsent(propertyName, HiddenKey::new);
    }

    /** The properties declared with hooks, inherited ones included (their inheritance isn't checked yet). */
    private final Set<String> hooked = new HashSet<>();

    public void addHooked(String propertyName) {
        hooked.add(propertyName);
    }

    public boolean isHooked(String propertyName) {
        return hooked.contains(propertyName);
    }

    public ConstantType constantType(String constantName) {
        return constantTypes.get(constantName);
    }

    /** The constant types by name, inherited ones included; read only. */
    public Map<String, ConstantType> constantTypes() {
        return java.util.Collections.unmodifiableMap(constantTypes);
    }
    /**
     * The constants that aren't public: their visibility (PropertyDecl's) and the class that declared them
     * (lowercase), which zend_verify_const_access checks against.
     */
    private final Map<String, PropertyDecl> restrictedConstants = new HashMap<>();
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
    /**
     * The class aliases of the context that declared this class (class_alias(): lowercase alias to its class),
     * or null; a name test also matches an alias of an ancestor. Kept apart from {@link #ancestors}, which the
     * class's key fixes for every context.
     */
    private Map<String, PhpClass> contextAliases;
    /** The property defaults, evaluated once (declaredDefaults order). */
    private Object[] defaults;
    /** mangledName's results for the non-public properties, made on first use. */
    private Map<String, String> mangledNames;

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
            constantTypes.putAll(parent.constantTypes);
            hooked.addAll(parent.hooked);
            for (Map.Entry<String, PropertyDecl> restricted : parent.restrictedConstants.entrySet()) {
                // a private constant isn't inherited (do_inherit_constant_check)
                if (restricted.getValue().visibility() == PropertyDecl.PRIVATE) {
                    constants.remove(restricted.getKey());
                } else {
                    restrictedConstants.put(restricted.getKey(), restricted.getValue());
                }
            }
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

    /**
     * instanceof: this class, a parent, or an implemented interface (lowercase name), under its own name or a
     * class alias of this context.
     */
    @TruffleBoundary
    public boolean isSubclassOf(String lowerClassName) {
        return ancestors.contains(lowerClassName) || isAliasedSubclassOf(lowerClassName);
    }

    /** {@link #isSubclassOf} by the classes' own names only: what the class's key fixes, in every context. */
    @TruffleBoundary
    public boolean isDeclaredSubclassOf(String lowerClassName) {
        return ancestors.contains(lowerClassName);
    }

    /** Whether the name is a class alias, in this class's context, of this class or an ancestor. */
    public boolean isAliasedSubclassOf(String lowerClassName) {
        Map<String, PhpClass> aliases = contextAliases;
        return aliases != null && aliasedAncestor(aliases, lowerClassName);
    }

    /** A HashMap's methods stay out of compiled code (native image's runtime compilation refuses them). */
    @TruffleBoundary
    private boolean aliasedAncestor(Map<String, PhpClass> aliases, String lowerClassName) {
        if (aliases.isEmpty()) {
            return false;
        }
        PhpClass target = aliases.get(lowerClassName);
        return target != null && ancestors.contains(target.lowerName);
    }

    /** Set when the class is declared in a context: that context's class aliases. */
    public void setContextAliases(Map<String, PhpClass> aliases) {
        this.contextAliases = aliases;
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

    /**
     * The method `$obj->name()` calls from a scope (zend_std_get_method; scope: the calling class, lowercase, or
     * null): the scope's own private method where a subclass declares the name again (ZEND_ACC_CHANGED,
     * zend_get_parent_private_method); null when __call stands in for a missing or inaccessible one; else
     * zend_undefined_method's or zend_bad_method_call's Error. name: as written.
     */
    @TruffleBoundary
    public PhpFunction methodFrom(String name, String scope) {
        PhpFunction method = accessibleMethod(Str.lower(name), scope);
        if (method != null || methods.containsKey("__call")) {
            return method;
        }
        throw callError(name, scope);
    }

    /** methodFrom's method, or null for a missing or inaccessible one (whatever stands in for it). */
    @TruffleBoundary
    public PhpFunction accessibleMethod(String lower, String scope) {
        PhpFunction method = methods.get(lower);
        if (method != null && scope != null && !scope.equals(declaringClass(method))) {
            PhpFunction own = scopePrivate(lower, scope);
            if (own != null) {
                return own;
            }
        }
        return method != null && canCall(method, lower, scope) ? method : null;
    }

    /**
     * The method `Cls::name()` calls from a scope (zend_std_get_static_method): null when __call or __callStatic
     * may stand in for a missing or inaccessible one (the call picks one by its $this); else the Error.
     */
    @TruffleBoundary
    public PhpFunction staticMethodFrom(String name, String scope) {
        PhpFunction method = accessibleStaticMethod(Str.lower(name), scope);
        if (method != null || methods.containsKey("__call") || methods.containsKey("__callstatic")) {
            return method;
        }
        throw callError(name, scope);
    }

    /** staticMethodFrom's method, or null for a missing or inaccessible one. */
    @TruffleBoundary
    public PhpFunction accessibleStaticMethod(String lower, String scope) {
        PhpFunction method = methods.get(lower);
        return method != null && canCall(method, lower, scope) ? method : null;
    }

    /** zend_bad_constructor_call: a constructor the scope may not call. */
    @TruffleBoundary
    public static PhpError constructorError(PhpFunction constructor, String scope) {
        return new PhpError("Call to " + visibility(constructor) + " " + constructor.getClassName() + "::"
                + constructor.getName() + "() from " + scopeName(scope));
    }

    /** zend_non_static_method_call. */
    @TruffleBoundary
    public static PhpError nonStaticCall(PhpFunction method) {
        return new PhpError("Non-static method " + method.getClassName() + "::" + method.getName()
                + "() cannot be called statically");
    }

    /**
     * Why a call of name from scope finds nothing: zend_undefined_method for a missing method, zend_bad_method_call
     * for an inaccessible one.
     */
    @TruffleBoundary
    public PhpError callError(String name, String scope) {
        PhpFunction method = methods.get(Str.lower(name));
        if (method == null) {
            return new PhpError("Call to undefined method " + this.name + "::" + name + "()");
        }
        return new PhpError("Call to " + visibility(method) + " method " + method.getClassName() + "::" + name
                + "() from " + scopeName(scope));
    }

    /** zend_bad_method_call's "scope X" or "global scope". */
    public static String scopeName(String scope) {
        PhpClass scopeClass = scope == null ? null : PhpContext.get(null).findClass(scope);
        return scopeClass == null ? "global scope" : "scope " + scopeClass.getName();
    }

    public static String visibility(PhpFunction method) {
        return (method.getModifiers() & PhpFunction.ACC_PRIVATE) != 0 ? "private" : "protected";
    }

    /** zend_check_method_accessible: public, or the scope's own, or protected and the scope related to its root. */
    @TruffleBoundary
    public boolean canCall(PhpFunction method, String lower, String scope) {
        int modifiers = method.getModifiers();
        if ((modifiers & (PhpFunction.ACC_PRIVATE | PhpFunction.ACC_PROTECTED)) == 0
                || scope != null && scope.equals(declaringClass(method))) {
            return true;
        }
        if ((modifiers & PhpFunction.ACC_PRIVATE) != 0 || scope == null) {
            return false;
        }
        PhpClass root = methodRoot(lower);
        if (root.ancestors.contains(scope)) {
            return true;
        }
        PhpClass scopeClass = PhpContext.get(null).findClass(scope);
        return scopeClass != null && scopeClass.isDeclaredSubclassOf(root.lowerName);
    }

    /**
     * Whether methodFrom's and staticMethodFrom's answer for scope follows from this class's key, so a site may
     * keep it for every class with the key: it does unless a protected method is reached from a scope outside its
     * root's ancestry and this class's, whose own ancestors decide (as isPropertyAccessKeyed).
     */
    @TruffleBoundary
    public boolean isMethodAccessKeyed(String lower, String scope) {
        PhpFunction method = methods.get(lower);
        if (method == null || scope == null || (method.getModifiers() & PhpFunction.ACC_PROTECTED) == 0
                || scope.equals(declaringClass(method)) || ancestors.contains(scope)) {
            return true;
        }
        return methodRoot(lower).ancestors.contains(scope);
    }

    /** Whether the method a call of lower finds depends on the calling scope at all. */
    @TruffleBoundary
    public boolean methodDependsOnScope(String lower) {
        PhpFunction method = methods.get(lower);
        if (method == null) {
            return false;
        }
        if ((method.getModifiers() & (PhpFunction.ACC_PRIVATE | PhpFunction.ACC_PROTECTED)) != 0) {
            return true;
        }
        for (PhpClass ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
            PhpFunction own = ancestor.methods.get(lower);
            if (own != null && own != method && (own.getModifiers() & PhpFunction.ACC_PRIVATE) != 0) {
                return true;
            }
        }
        return false;
    }

    /** The scope's own private method of the name, when the scope is a proper ancestor (zend_get_parent_private_method). */
    private PhpFunction scopePrivate(String lower, String scope) {
        for (PhpClass ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
            if (ancestor.lowerName.equals(scope)) {
                PhpFunction own = ancestor.methods.get(lower);
                return own != null && (own.getModifiers() & PhpFunction.ACC_PRIVATE) != 0
                        && scope.equals(declaringClass(own)) ? own : null;
            }
        }
        return null;
    }

    /**
     * The class a protected method is checked against (zend_get_function_root_class): the topmost ancestor its
     * declaration overrides, through non-private declarations.
     */
    private PhpClass methodRoot(String lower) {
        PhpClass root = this;
        while (root.parent != null && root.parent.methods.get(lower) instanceof PhpFunction inherited
                && (inherited.getModifiers() & PhpFunction.ACC_PRIVATE) == 0) {
            root = root.parent;
        }
        return root;
    }

    /** The class (lowercase) that declared a method, or null for a built-in class's shared one. */
    private static String declaringClass(PhpFunction method) {
        return method.getClassName() == null ? null : Str.lower(method.getClassName());
    }

    /** The methods by lowercase name (own, imported and inherited), for Reflection; read only. */
    public Map<String, PhpFunction> methods() {
        return java.util.Collections.unmodifiableMap(methods);
    }

    /** The interfaces the class implements directly (an interface: those it extends), in declaration order. */
    public List<PhpClass> directInterfaces() {
        return java.util.Collections.unmodifiableList(interfaces);
    }

    /** The traits whose members the class imported, nested ones included, in import order. */
    public List<PhpClass> traits() {
        return java.util.Collections.unmodifiableList(traits);
    }

    /** The class constants by name, in declaration order, unevaluated initializers included; read only. */
    public java.util.Set<String> constantNames() {
        return java.util.Collections.unmodifiableSet(constants.keySet());
    }

    public PhpFunction getConstructor() {
        return constructor;
    }

    public void addConstant(String constantName, Object valueOrThunk) {
        addConstant(constantName, valueOrThunk, PropertyDecl.PUBLIC, null);
    }

    /** A constant this class declares, with its visibility (PropertyDecl's) and type (or null). */
    public void addConstant(String constantName, Object valueOrThunk, int visibility, PhpType type) {
        constantTypes.put(constantName, new ConstantType(type, this, visibility == PropertyDecl.PRIVATE));
        constants.put(constantName, valueOrThunk);
        if (visibility == PropertyDecl.PUBLIC) {
            restrictedConstants.remove(constantName);
        } else {
            restrictedConstants.put(constantName, new PropertyDecl(constantName, null, visibility, lowerName, null));
        }
    }

    /**
     * Whether scope (lowercase, or null) may access the constant (zend_verify_const_access): a private one from its
     * class only, a protected one from a class related to the class that declared it.
     */
    @TruffleBoundary
    public boolean canAccessConstant(String constantName, String scope) {
        PropertyDecl decl = restrictedConstants.get(constantName);
        if (decl == null || decl.declaringClass().equals(scope)) {
            return true;
        }
        if (decl.visibility() == PropertyDecl.PRIVATE || scope == null) {
            return false;
        }
        PhpClass declaring = this;
        while (!declaring.lowerName.equals(decl.declaringClass())) {
            declaring = declaring.parent;
        }
        if (declaring.ancestors.contains(scope)) {
            return true;
        }
        PhpClass scopeClass = PhpContext.get(null).findClass(scope);
        return scopeClass != null && scopeClass.isDeclaredSubclassOf(declaring.lowerName);
    }

    /** "Cannot access private constant A::P" (FETCH_CLASS_CONSTANT's Error). */
    @TruffleBoundary
    public PhpError constantAccessError(String constantName) {
        return new PhpError("Cannot access " + (restrictedConstants.get(constantName).visibility()
                == PropertyDecl.PRIVATE ? "private" : "protected") + " constant " + name + "::" + constantName);
    }

    /** Whether the constant isn't public: a site's cached value then holds for its scope only. */
    @TruffleBoundary
    public boolean isRestrictedConstant(String constantName) {
        return restrictedConstants.containsKey(constantName);
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

    /**
     * `Cls::$name = &ref` (zend_assign_to_variable_reference on the static member): every class sharing the
     * property's slot (the declaring class, the subclasses that inherit it) holds ref from now on; references taken
     * of the old one keep it. Sites that kept the old reference look it up again.
     */
    @TruffleBoundary
    public void bindStaticProperty(PhpContext context, String propertyName, PhpRef ref) {
        PhpRef old = staticProperties.get(propertyName);
        for (PhpClass phpClass : context.classValues()) {
            if (phpClass.staticProperties.get(propertyName) == old) {
                phpClass.staticProperties.put(propertyName, ref);
            }
        }
        staticProperties.put(propertyName, ref);
        ref.bind();
        old.unbind();
        context.getLanguage().staticPropertyRefs().invalidate();
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
                : new PropertyDecl(decl.name(), decl.defaultValue(), decl.visibility(), lowerName, decl.type()));
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

    /** Whether the class declares (or inherits) a static property of the name. */
    @TruffleBoundary
    public boolean isStaticProperty(String propertyName) {
        return staticProperties.containsKey(propertyName);
    }

    /** Whether the class declares or inherits any property, static or not: php-src's properties_info isn't empty. */
    public boolean hasPropertyInfo() {
        return !properties.isEmpty() || !staticProperties.isEmpty();
    }

    /**
     * A property's key in php-src's property table (and in serialize(), (array) and get_mangled_object_vars()):
     * "\0Class\0name" for a private property (Class is the one declaring it), "\0*\0name" for a protected one, the
     * name itself for a public or undeclared one. Keys are property keys: the bytes as ISO-8859-1 chars.
     */
    @TruffleBoundary
    public String mangledName(String propertyName) {
        PropertyDecl decl = properties.get(propertyName);
        if (decl == null || decl.isPublic()) {
            return propertyName;
        }
        if (mangledNames == null) {
            mangledNames = new HashMap<>();
        }
        String mangled = mangledNames.get(propertyName);
        if (mangled == null) {
            String scope = decl.visibility() == PropertyDecl.PROTECTED ? "*" : nameKey(declaringClassName(decl));
            mangled = "\0" + scope + "\0" + propertyName;
            mangledNames.put(propertyName, mangled);
        }
        return mangled;
    }

    /**
     * The declared property whose key in php-src's property table is key (see {@link #mangledName}): its name,
     * or null when key names no declared property's slot.
     */
    @TruffleBoundary
    public String propertyOfMangledName(String key) {
        if (key.isEmpty() || key.charAt(0) != '\0') {
            PropertyDecl decl = properties.get(key);
            return decl != null && decl.isPublic() ? key : null;
        }
        String propertyName = key.substring(key.lastIndexOf('\0') + 1);
        PropertyDecl decl = properties.get(propertyName);
        return decl != null && !decl.isPublic() && mangledName(propertyName).equals(key) ? propertyName : null;
    }

    /** The name, as written, of the class declaring the property: this class or an ancestor. */
    private String declaringClassName(PropertyDecl decl) {
        for (PhpClass c = this; c != null; c = c.parent) {
            if (c.lowerName.equals(decl.declaringClass())) {
                return c.name;
            }
        }
        return name;
    }

    /** A class name (UTF-8 text) as a property key holds it: its bytes as ISO-8859-1 chars. */
    public static String nameKey(String className) {
        return new String(className.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.ISO_8859_1);
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
            if (value == null) {
                continue;
            }
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
     * The language-level identity of this class: equal for the same declarations in every context. A declared
     * class has it ({@link #makeKey}, from PhpContext.declareClass), so compiled code reads one field: inline
     * caches test it at every method call, `new` and static call, and a lazy making's call at each of those
     * sites was about 15 IR nodes and a call's code. A class not declared yet makes it here, once, in the
     * interpreter.
     */
    public ClassKey getKey() {
        ClassKey k = key;
        if (k == null) {
            CompilerDirectives.transferToInterpreter();
            k = makeKey(PhpContext.get(null).getLanguage());
        }
        return k;
    }

    /** Makes the key once the class is complete: its parent, interfaces and traits are set. */
    @TruffleBoundary
    public ClassKey makeKey(PhpLanguage language) {
        if (key == null) {
            key = language.classKey(new ClassKey.Id(declaration, parent == null ? null : parent.makeKey(language),
                    keys(language, interfaces), keys(language, traits)));
        }
        return key;
    }

    private static List<ClassKey> keys(PhpLanguage language, List<PhpClass> classes) {
        List<ClassKey> keys = new ArrayList<>(classes.size());
        for (PhpClass phpClass : classes) {
            keys.add(phpClass.makeKey(language));
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
            flags[i++] = propertyFlags(decl);
        }
        return flags;
    }

    /** The shape flags of a declared property in an object of this class. */
    public int propertyFlags(PropertyDecl decl) {
        return (decl.visibility() == PropertyDecl.PROTECTED ? PROPERTY_PROTECTED
                : decl.visibility() == PropertyDecl.PRIVATE && lowerName.equals(decl.declaringClass())
                ? PROPERTY_PRIVATE : 0) | (decl.type() != null ? PROPERTY_TYPED : 0);
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
                PropertyDecl decl = entry.getValue();
                if (decl.type() != null) {
                    // checked as evaluated, in strict mode (zend_update_class_constants)
                    value = decl.type().assign(value, true, decl, null);
                }
                entry.setValue(new PropertyDecl(entry.getKey(), value, decl.visibility(), decl.declaringClass(),
                        decl.type()));
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
