package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Shape;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A PHP class, interface or enum after linking: inherited methods, constants and properties are flattened in, so
 * lookups never walk the parent chain. Names are kept as written; maps are keyed by lowercase method names and
 * case-sensitive constant and property names, as PHP.
 */
public final class PhpClass {
    /** A declared instance property: its default (or a thunk evaluating the constant expression) and visibility. */
    public record PropertyDecl(String name, Object defaultValue, boolean isPublic) {
    }

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

    @CompilationFinal private PhpFunction constructor;

    public PhpClass(String name, PhpClass parent, boolean isInterface, boolean isAbstract, Shape rootShape) {
        this.name = name;
        this.lowerName = name.toLowerCase();
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

    /** instanceof: this class, a parent, or an implemented interface (lowercase name). */
    @TruffleBoundary
    public boolean isSubclassOf(String lowerClassName) {
        return ancestors.contains(lowerClassName);
    }

    public void addInterface(PhpClass iface) {
        ancestors.addAll(iface.ancestors);
        for (Map.Entry<String, Object> constant : iface.constants.entrySet()) {
            constants.putIfAbsent(constant.getKey(), constant.getValue());
        }
        for (Map.Entry<String, PhpFunction> method : iface.methods.entrySet()) {
            methods.putIfAbsent(method.getKey(), method.getValue());
        }
    }

    public void addMethod(PhpFunction method) {
        String key = method.getName().toLowerCase();
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
        staticProperties.put(propertyName, new PhpRef(initial));
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
        properties.put(decl.name(), decl);
    }

    public Iterable<PropertyDecl> getProperties() {
        return properties.values();
    }

    @TruffleBoundary
    public boolean isPublicProperty(String propertyName) {
        PropertyDecl decl = properties.get(propertyName);
        return decl == null || decl.isPublic();
    }

    /** A new object with declared properties at their defaults, in declaration order (no constructor call). */
    @TruffleBoundary
    public PhpObject instantiate() {
        if (!isInstantiable()) {
            throw new PhpError("Cannot instantiate " + (isInterface ? "interface " : "abstract class ") + name);
        }
        PhpObject object = new PhpObject(rootShape, this);
        DynamicObjectLibrary library = DynamicObjectLibrary.getUncached();
        String[] names = declaredNames();
        Object[] defaults = declaredDefaults();
        for (int i = 0; i < names.length; i++) {
            Object value = defaults[i];
            if (value instanceof Long l) {
                library.putLong(object, names[i], l);
            } else {
                if (value instanceof PhpArray array) {
                    array.addHolder();
                }
                library.put(object, names[i], value);
            }
        }
        return object;
    }

    public boolean isInstantiable() {
        return !isAbstract && !isInterface;
    }

    public Shape getRootShape() {
        return rootShape;
    }

    /** Declared (and inherited) instance property names, in declaration order. */
    @TruffleBoundary
    public String[] declaredNames() {
        return properties.keySet().toArray(new String[0]);
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
                entry.setValue(new PropertyDecl(entry.getKey(), value, entry.getValue().isPublic()));
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
