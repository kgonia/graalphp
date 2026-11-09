package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.bytecode.SpreadArguments;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.ClassTemplate;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What ext/reflection's classes (src/main/php/prelude/reflection.php) read of the runtime: classes, methods,
 * functions and parameters as declared, and the calls that skip visibility (ReflectionMethod::invoke). The
 * functions are in namespace Truffle, which no user code sees in its function lists.
 */
final class ReflectionFunctions {
    private ReflectionFunctions() {
    }

    static void register(Registry r) {
        r.fn("class_implements", (context, a) -> splClasses(context, a, "class_implements", 'i'));
        r.fn("class_parents", (context, a) -> splClasses(context, a, "class_parents", 'p'));
        r.fn("class_uses", (context, a) -> splClasses(context, a, "class_uses", 't'));
        r.fn("truffle\\reflection_class", (context, a) -> {
            PhpClass phpClass = phpClass(context, Args.get(a, 0), Args.bool(a, 1, true));
            return phpClass == null ? Boolean.FALSE : classInfo(context, phpClass);
        });
        r.fn("truffle\\reflection_methods", (context, a) -> methodList(context, phpClass(context, Args.get(a, 0), false)));
        r.fn("truffle\\reflection_method", (context, a) -> {
            PhpClass phpClass = phpClass(context, Args.get(a, 0), false);
            String lower = Str.lower(Str.java(Args.str(a, 1)));
            PhpFunction method = phpClass == null ? null : phpClass.findMethod(lower);
            return method == null ? Boolean.FALSE : methodInfo(context, phpClass, lower, method);
        });
        r.fn("truffle\\reflection_function", (context, a) -> functionInfo(context, Args.get(a, 0)));
        r.fn("truffle\\reflection_constants", (context, a) -> constants(context, phpClass(context, Args.get(a, 0),
                false)));
        r.fn("truffle\\reflection_call", (context, a) -> call(context, Args.get(a, 0), Args.str(a, 1), Args.str(a, 2),
                Args.array(a, 3, "Truffle\\reflection_call")));
        r.fn("truffle\\reflection_closure", (context, a) -> closure(context, Args.get(a, 0), Args.str(a, 1),
                Args.str(a, 2)));
        r.fn("truffle\\reflection_new", (context, a) -> phpClass(context, Args.get(a, 0), false).instantiate());
        r.fn("truffle\\reflection_construct", (context, a) -> construct(context, Args.get(a, 0), Args.array(a, 1,
                "Truffle\\reflection_construct")));
        r.fn("truffle\\lazy_new", (context, a) -> lazy(context, phpClass(context, Args.get(a, 0), false), null,
                Args.get(a, 1), (int) Args.lng(a, 2, 0), Args.bool(a, 3, false)));
        r.fn("truffle\\lazy_reset", (context, a) -> lazy(context, phpClass(context, Args.get(a, 0), false),
                (PhpObject) Args.get(a, 1), Args.get(a, 2), (int) Args.lng(a, 3, 0), Args.bool(a, 4, false)));
        r.fn("truffle\\lazy_state", (context, a) -> lazyState((PhpObject) Args.get(a, 0), Str.java(Args.str(a, 1))));
        r.fn("truffle\\reflection_property", (context, a) -> {
            PhpClass phpClass = phpClass(context, Args.get(a, 0), false);
            String name = Str.key(Args.str(a, 1));
            Object info = phpClass == null ? Boolean.FALSE : propertyInfo(phpClass, name);
            if (info == Boolean.FALSE && Args.get(a, 0) instanceof PhpObject object
                    && object.initialized().getProperty(name) != null) {
                // an object's dynamic property (ReflectionProperty's dynam_prop)
                PhpArray dynamic = new PhpArray();
                put(dynamic, "name", Str.ofKey(name));
                put(dynamic, "class", Str.of(phpClass.getName()));
                put(dynamic, "reflected", Str.of(phpClass.getName()));
                put(dynamic, "modifiers", 1L);
                put(dynamic, "dynamic", Boolean.TRUE);
                return dynamic;
            }
            return info;
        });
        r.fn("truffle\\reflection_properties", (context, a) -> {
            PhpClass phpClass = phpClass(context, Args.get(a, 0), false);
            PhpArray names = new PhpArray();
            for (PhpClass.PropertyDecl decl : phpClass.getProperties()) {
                names.append(Str.ofKey(decl.name()));
            }
            return names;
        });
        // ReflectionProperty::getValue() and setValue() of a static property
        r.fn("truffle\\static_property", (context, a) ->
                phpClass(context, Args.get(a, 0), false).getStaticProperty(Str.key(Args.str(a, 1))).get());
        r.fn("truffle\\set_static_property", (context, a) -> {
            phpClass(context, Args.get(a, 0), false).getStaticProperty(Str.key(Args.str(a, 1))).set(Args.get(a, 2));
            return PhpNull.NULL;
        });
        r.fn("truffle\\property_lazy", (context, a) -> propertyLazy(context, (PhpObject) Args.get(a, 0),
                Str.key(Args.str(a, 1)), Str.java(Args.str(a, 2)), Args.get(a, 3, PhpNull.NULL)));
        // an error of the reflection method's own (E_DEPRECATED), placed at the calling user code
        r.fn("truffle\\reflection_raise", (context, a) -> {
            ErrorFunctions.raise(context, (int) Args.lng(a, 0, ErrorFunctions.E_WARNING), Args.str(a, 1),
                    SourceLocation.current());
            return PhpNull.NULL;
        });
    }

    /**
     * class_implements(), class_parents() and class_uses() (ext/spl): the interfaces as ce->interfaces lists them,
     * the parents nearest first, or the traits the class itself uses; each keyed by its name.
     */
    @TruffleBoundary
    private static Object splClasses(PhpContext context, Object[] a, String function, char kind) {
        Object value = Args.get(a, 0, PhpNull.NULL);
        if (!(value instanceof PhpObject) && !(value instanceof PhpClosure) && !(value instanceof TruffleString)) {
            throw Args.typeError(function, 1, "object_or_class", "object|string", value);
        }
        boolean autoload = Args.bool(a, 1, true);
        PhpClass phpClass = phpClass(context, value, autoload);
        if (phpClass == null) {
            ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of(function + "(): Class "
                    + Str.java((TruffleString) value) + " does not exist" + (autoload ? " and could not be loaded" : "")),
                    SourceLocation.current());
            return Boolean.FALSE;
        }
        PhpArray result = new PhpArray();
        if (kind == 'i') {
            for (PhpClass iface : interfaces(phpClass)) {
                result.put(ArrayKey.of(Str.of(iface.getName())), Str.of(iface.getName()));
            }
        } else if (kind == 'p') {
            for (PhpClass parent = phpClass.getParent(); parent != null; parent = parent.getParent()) {
                result.put(ArrayKey.of(Str.of(parent.getName())), Str.of(parent.getName()));
            }
        } else if (template(phpClass) != null) {
            for (ClassTemplate.Member member : template(phpClass).ownMembers()) {
                if (member instanceof ClassTemplate.TraitUse use) {
                    for (String trait : use.traits()) {
                        PhpClass traitClass = context.findClass(Str.lower(trait));
                        String name = traitClass == null ? trait : traitClass.getName();
                        result.put(ArrayKey.of(Str.of(name)), Str.of(name));
                    }
                }
            }
        }
        return result;
    }

    /**
     * ReflectionClass::newLazyGhost(), newLazyProxy() (object null) and resetAsLazyGhost(), resetAsLazyProxy()
     * (zend_object_make_lazy): the lazy object, or null for a reset.
     */
    @TruffleBoundary
    private static Object lazy(PhpContext context, PhpClass phpClass, PhpObject object, Object initializer,
                               int options, boolean proxy) {
        if (internal(phpClass)) {
            throw new PhpError("Cannot make instance of internal class lazy: " + phpClass.getName() + " is internal");
        }
        for (PhpClass parent = phpClass.getParent(); parent != null; parent = parent.getParent()) {
            if (internal(parent)) {
                throw new PhpError("Cannot make instance of internal class lazy: " + phpClass.getName()
                        + " inherits internal class " + parent.getName());
            }
        }
        if (object == null) {
            if (!phpClass.isInstantiable()) {
                phpClass.instantiate();
            }
            object = new PhpObject(phpClass.getRootShape(), phpClass, context.nextObjectId());
            object.makeLazy(initializer, proxy, options);
            // without properties to make lazy, an object as `new` makes it
            return object;
        }
        if (object.isInitializingLazy()) {
            throw new PhpError("Can not reset an object while it is being initialized");
        }
        object.makeLazy(initializer, proxy, options);
        return PhpNull.NULL;
    }

    /** A built-in class (the prelude's too, declared nowhere), which can't be lazy; stdClass can. */
    private static boolean internal(PhpClass phpClass) {
        ClassTemplate template = template(phpClass);
        return (template == null || template.getDeclaredAt() == null) && !phpClass.getLowerName().equals("stdclass");
    }

    /** ReflectionClass's questions and orders about a lazy object (by what), as php-src answers them. */
    @TruffleBoundary
    private static Object lazyState(PhpObject object, String what) {
        boolean uninitialized = object.isUninitializedLazy();
        switch (what) {
            case "uninitialized":
                return uninitialized;
            case "initializer":
                return uninitialized ? object.lazy().initializer() : PhpNull.NULL;
            case "initialize":
                if (uninitialized) {
                    object.initializeLazy();
                }
                break;
            case "mark":
                if (uninitialized) {
                    object.markLazyInitialized();
                }
                break;
            default:
                throw new PhpError("unknown lazy state " + what);
        }
        PhpObject instance = object;
        while (instance.lazy() != null && instance.lazy().instance() != null) {
            instance = instance.lazy().instance();
        }
        return instance;
    }

    /** What ReflectionProperty reads of a declared property, or false for a name the class doesn't declare. */
    @TruffleBoundary
    private static Object propertyInfo(PhpClass phpClass, String name) {
        PhpClass.PropertyDecl decl = phpClass.findProperty(name);
        boolean isStatic = decl == null && phpClass.isStaticProperty(name);
        if (decl == null && !isStatic) {
            return Boolean.FALSE;
        }
        PhpArray info = new PhpArray();
        put(info, "name", Str.ofKey(name));
        String declaring = phpClass.getName();
        if (decl != null) {
            for (PhpClass c = phpClass; c != null; c = c.getParent()) {
                if (c.getLowerName().equals(decl.declaringClass())) {
                    declaring = c.getName();
                }
            }
        }
        put(info, "class", Str.of(declaring));
        put(info, "reflected", Str.of(phpClass.getName()));
        int visibility = decl == null ? 0 : decl.visibility();
        PhpClass.Hooks hooks = phpClass.findHooks(name);
        long modifiers = (visibility == PhpClass.PropertyDecl.PRIVATE ? 4 : visibility == PhpClass.PropertyDecl.PROTECTED
                ? 2 : 1) | (isStatic ? 16 : 0) | (hooks != null && hooks.virtual() ? 512 : 0)
                | (phpClass.isFinalProperty(name) ? 32 : 0);
        put(info, "modifiers", modifiers);
        return info;
    }

    /**
     * ReflectionProperty's lazy-object methods (by what): skipLazyInitialization(), setRawValueWithoutLazyInitialization()
     * and isLazy() on the object (an initialized proxy's instance), and getValue()/setValue() through the hooks.
     */
    @TruffleBoundary
    private static Object propertyLazy(PhpContext context, PhpObject object, String name, String what, Object value) {
        while (object.lazy() != null && object.lazy().instance() != null) {
            object = object.lazy().instance();
        }
        PhpClass phpClass = object.getPhpClass();
        PhpClass.Hooks hooks = phpClass.findHooks(name);
        boolean declared = phpClass.isDeclaredProperty(name);
        if (what.equals("isLazy")) {
            return declared && (hooks == null || !hooks.virtual()) && object.isUninitializedLazy()
                    && object.getProperty(name) == null;
        }
        String method = what.equals("skip") ? "skipLazyInitialization" : "setRawValueWithoutLazyInitialization";
        if (!declared) {
            throw new PhpError("ReflectionException", "Can not use " + method + " on "
                    + (phpClass.isStaticProperty(name) ? "static" : "dynamic") + " property " + phpClass.getName()
                    + "::$" + Str.keyText(name));
        }
        if (hooks != null && hooks.virtual()) {
            throw new PhpError("ReflectionException", "Can not use " + method + " on virtual property "
                    + phpClass.getName() + "::$" + Str.keyText(name));
        }
        boolean lazy = object.isUninitializedLazy() && object.getProperty(name) == null;
        if (what.equals("skip")) {
            if (!lazy) {
                return PhpNull.NULL;
            }
            String[] names = phpClass.declaredNames();
            Object[] defaults = phpClass.defaults();
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(name)) {
                    object.writeRaw(name, defaults[i]);
                }
            }
        } else {
            object.writeRaw(name, value);
        }
        if (lazy) {
            object.lazyPropertyInitialized();
        }
        return PhpNull.NULL;
    }

    /** The class an argument names (a name, autoloaded if asked, or an object's class), or null. */
    private static PhpClass phpClass(PhpContext context, Object value, boolean autoload) {
        if (value instanceof PhpObject object) {
            return object.getPhpClass();
        }
        if (value instanceof PhpClosure) {
            return context.findClass("closure");
        }
        String name = Str.java(Convert.toStr(value));
        if (name.startsWith("\\")) {
            name = name.substring(1);
        }
        return autoload ? context.findClassAutoloading(name) : context.findClass(Str.lower(name));
    }

    private static void put(PhpArray array, String key, Object value) {
        array.put(ArrayKey.of(Str.of(key)), value);
    }

    private static Object str(String value) {
        return value == null ? (Object) Boolean.FALSE : Str.of(value);
    }

    static ClassTemplate template(PhpClass phpClass) {
        return phpClass.getDeclaration() instanceof ClassTemplate template ? template : null;
    }

    /** What ReflectionClass reads of a class: its declaration and what it inherits, in php-src's orders. */
    @TruffleBoundary
    private static PhpArray classInfo(PhpContext context, PhpClass phpClass) {
        ClassTemplate template = template(phpClass);
        PhpArray info = new PhpArray();
        put(info, "name", Str.of(phpClass.getName()));
        put(info, "internal", template == null);
        SourceLocation at = template == null ? null : template.getDeclaredAt();
        put(info, "file", at == null ? Boolean.FALSE : Str.of(at.file()));
        put(info, "startLine", at == null ? (Object) Boolean.FALSE : (long) at.line());
        put(info, "endLine", at == null ? (Object) Boolean.FALSE : (long) template.getEndLine());
        put(info, "docComment", str(template == null ? null : template.getDocComment()));
        put(info, "interface", phpClass.isInterface());
        put(info, "trait", template != null && template.isTrait());
        int flags = template == null ? 0 : template.getFlags();
        // ZEND_ACC_FINAL, ZEND_ACC_EXPLICIT_ABSTRACT_CLASS, ZEND_ACC_READONLY_CLASS
        put(info, "modifiers", (long) (flags & (32 | 64 | 65536)));
        boolean abstractMethods = false;
        for (PhpFunction method : phpClass.methods().values()) {
            abstractMethods |= method.isAbstract();
        }
        put(info, "abstract", phpClass.isAbstract() || (flags & 64) != 0 || abstractMethods);
        put(info, "parent", phpClass.getParent() == null ? Boolean.FALSE : Str.of(phpClass.getParent().getName()));
        PhpArray interfaces = new PhpArray();
        for (PhpClass iface : interfaces(phpClass)) {
            interfaces.append(Str.of(iface.getName()));
        }
        put(info, "interfaces", interfaces);
        PhpArray traits = new PhpArray();
        if (template != null) {
            for (ClassTemplate.Member member : template.ownMembers()) {
                if (member instanceof ClassTemplate.TraitUse use) {
                    for (String trait : use.traits()) {
                        PhpClass traitClass = context.findClass(Str.lower(trait));
                        traits.append(Str.of(traitClass == null ? trait : traitClass.getName()));
                    }
                }
            }
        }
        put(info, "traits", traits);
        put(info, "anonymous", phpClass.getName().contains("@anonymous"));
        PhpFunction clone = phpClass.findMethod("__clone");
        put(info, "privateClone", clone != null && !clone.isPublic());
        PhpClass.ObjectHandlers handlers = phpClass.getHandlers();
        put(info, "uncloneable", handlers != null && handlers.uncloneable());
        return info;
    }

    /**
     * The interfaces a class implements as php-src lists them (ce->interfaces). A user class with interfaces of its
     * own (zend_do_implement_interfaces): the parent's, then the declared ones, then what each of those extends,
     * added in reverse. Without (zend_do_inherit_interfaces): the parent's, in reverse. A built-in class keeps its
     * parent's order. Each once.
     */
    static List<PhpClass> interfaces(PhpClass phpClass) {
        List<PhpClass> inheritedList = phpClass.getParent() == null ? List.of() : interfaces(phpClass.getParent());
        List<PhpClass> result = new ArrayList<>();
        boolean user = template(phpClass) != null;
        if (phpClass.directInterfaces().isEmpty() && user) {
            inheritReversed(result, inheritedList);
            return result;
        }
        result.addAll(inheritedList);
        int declaredFrom = result.size();
        for (PhpClass iface : phpClass.directInterfaces()) {
            if (!result.contains(iface)) {
                result.add(iface);
            }
        }
        int declaredTo = result.size();
        for (int i = declaredFrom; i < declaredTo; i++) {
            inheritReversed(result, interfaces(result.get(i)));
        }
        return result;
    }

    private static void inheritReversed(List<PhpClass> result, List<PhpClass> inherited) {
        for (int i = inherited.size() - 1; i >= 0; i--) {
            if (!result.contains(inherited.get(i))) {
                result.add(inherited.get(i));
            }
        }
    }

    /**
     * The methods in php-src's function table order: the class's own, then those its traits bring, then the
     * inherited ones (parent's, then the interfaces'). Lowercase name to the name the class has the method by.
     */
    static Map<String, String> orderedMethods(PhpContext context, PhpClass phpClass) {
        Map<String, String> names = new LinkedHashMap<>();
        ClassTemplate template = template(phpClass);
        if (template == null || template.isTrait()) {
            // a built-in class's, or a trait's, which has its methods in declaration order
            for (Map.Entry<String, PhpFunction> entry : phpClass.methods().entrySet()) {
                names.put(entry.getKey(), entry.getValue().getName());
            }
            return names;
        }
        for (ClassTemplate.Member member : template.ownMembers()) {
            if (member instanceof ClassTemplate.Method method) {
                names.put(Str.lower(method.function().getName()), method.function().getName());
            }
        }
        for (ClassTemplate.Member member : template.ownMembers()) {
            if (member instanceof ClassTemplate.TraitUse use) {
                traitMethods(context, phpClass, use, names);
            }
        }
        List<PhpClass> inherited = new ArrayList<>();
        if (phpClass.getParent() != null) {
            inherited.add(phpClass.getParent());
        }
        inherited.addAll(phpClass.directInterfaces());
        for (PhpClass from : inherited) {
            for (Map.Entry<String, String> entry : orderedMethods(context, from).entrySet()) {
                if (phpClass.findMethod(entry.getKey()) != null) {
                    names.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }
        return names;
    }

    /** A trait use's methods as the class has them: each alias before its method, as zend_traits_copy_functions. */
    private static void traitMethods(PhpContext context, PhpClass phpClass, ClassTemplate.TraitUse use,
                                     Map<String, String> names) {
        for (String traitName : use.traits()) {
            PhpClass trait = context.findClass(Str.lower(traitName));
            if (trait == null) {
                continue;
            }
            for (Map.Entry<String, String> entry : orderedMethods(context, trait).entrySet()) {
                for (ClassTemplate.Alias alias : use.aliases()) {
                    if (alias.alias() != null && alias.method().equals(entry.getKey())
                            && (alias.trait() == null || alias.trait().equalsIgnoreCase(trait.getName()))) {
                        names.putIfAbsent(Str.lower(alias.alias()), alias.alias());
                    }
                }
                PhpFunction method = phpClass.findMethod(entry.getKey());
                if (method != null && phpClass.getName().equals(method.getClassName())) {
                    names.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    @TruffleBoundary
    private static PhpArray methodList(PhpContext context, PhpClass phpClass) {
        PhpArray list = new PhpArray();
        if (phpClass == null) {
            return list;
        }
        for (Map.Entry<String, String> entry : orderedMethods(context, phpClass).entrySet()) {
            PhpFunction method = phpClass.findMethod(entry.getKey());
            if (method == null) {
                continue;
            }
            PhpArray item = new PhpArray();
            put(item, "name", Str.of(entry.getValue()));
            put(item, "class", Str.of(declaringClass(phpClass, entry.getKey(), method).getName()));
            list.append(item);
        }
        return list;
    }

    /**
     * The class that declares a method: the one its translation names (a trait's method belongs to the class
     * using it), else, for a built-in method, the highest ancestor that has the same function under the name.
     */
    static PhpClass declaringClass(PhpClass phpClass, String lower, PhpFunction method) {
        String named = method.getClassName();
        PhpClass declaring = phpClass;
        for (PhpClass c = phpClass; c != null; c = c.getParent()) {
            if (named != null ? c.getName().equalsIgnoreCase(named) : c.findMethod(lower) == method) {
                declaring = c;
                if (named != null) {
                    return c;
                }
            }
        }
        if (named != null) {
            for (PhpClass iface : interfaces(phpClass)) {
                if (iface.getName().equalsIgnoreCase(named)) {
                    return iface;
                }
            }
        }
        return declaring;
    }

    @TruffleBoundary
    private static PhpArray methodInfo(PhpContext context, PhpClass phpClass, String lower, PhpFunction method) {
        PhpClass declaring = declaringClass(phpClass, lower, method);
        String name = orderedMethods(context, phpClass).getOrDefault(lower, method.getName());
        PhpArray info = functionInfo(method, name);
        put(info, "class", Str.of(declaring.getName()));
        int modifiers = method.getModifiers();
        if (method.getBuiltin() != null) {
            modifiers = PhpFunction.ACC_PUBLIC | (method.isStatic() ? PhpFunction.ACC_STATIC : 0);
        }
        put(info, "modifiers", (long) modifiers);
        return info;
    }

    /** What ReflectionFunctionAbstract reads of a function, method or closure. */
    private static PhpArray functionInfo(PhpFunction function, String name) {
        PhpArray info = new PhpArray();
        boolean internal = function.getBuiltin() != null;
        put(info, "name", Str.of(name));
        put(info, "internal", internal);
        SourceLocation at = function.getDeclaredAt();
        put(info, "file", at == null ? Boolean.FALSE : Str.of(at.file()));
        put(info, "startLine", at == null ? (Object) Boolean.FALSE : (long) at.line());
        put(info, "endLine", at == null ? (Object) Boolean.FALSE : (long) function.getEndLine());
        put(info, "docComment", str(function.getDocComment()));
        put(info, "returnsRef", function.returnsRef());
        put(info, "returnType", function.getReturnType() == null ? PhpNull.NULL : Str.of(function.getReturnType()));
        put(info, "static", function.isStatic());
        PhpFunction.Param[] params = function.getParams();
        if (internal) {
            // the built-ins are declared without php-src's arginfo: there are no names, types or defaults to give
            put(info, "params", PhpNull.NULL);
            return info;
        }
        int required = 0;
        for (int i = 0; i < params.length; i++) {
            if (params[i].defaultValue() == null && !params[i].variadic()) {
                required = i + 1;
            }
        }
        put(info, "required", (long) required);
        PhpArray list = new PhpArray();
        for (int i = 0; i < params.length; i++) {
            PhpFunction.Param param = params[i];
            PhpArray item = new PhpArray();
            put(item, "name", Str.of(param.name()));
            put(item, "byRef", param.byRef());
            put(item, "variadic", param.variadic());
            put(item, "optional", i >= required);
            put(item, "hasDefault", param.defaultValue() != null);
            put(item, "type", param.type() == null ? PhpNull.NULL : Str.of(param.type()));
            list.append(item);
        }
        put(info, "params", list);
        return info;
    }

    /** A function by name, or a closure: its declaration, and a closure's $this and scope. */
    @TruffleBoundary
    private static Object functionInfo(PhpContext context, Object value) {
        if (value instanceof PhpClosure closure) {
            PhpFunction function = closure.getFunction();
            PhpArray info = functionInfo(function, function.getName());
            put(info, "closure", true);
            put(info, "this", closure.getBoundThis() == null ? PhpNull.NULL : closure.getBoundThis());
            put(info, "scope", closure.getScope() == null ? PhpNull.NULL : Str.of(closure.getScope().getName()));
            return info;
        }
        String name = Str.java(Convert.toStr(value));
        PhpFunction function = context.findFunction(Str.lower(name.startsWith("\\") ? name.substring(1) : name));
        if (function == null) {
            return Boolean.FALSE;
        }
        PhpArray info = functionInfo(function, function.getName());
        put(info, "closure", false);
        return info;
    }

    /** The class constants by name, evaluated: own in declaration order, then the parent's, then the interfaces'. */
    @TruffleBoundary
    private static PhpArray constants(PhpContext context, PhpClass phpClass) {
        PhpArray result = new PhpArray();
        if (phpClass == null) {
            return result;
        }
        for (String name : orderedConstants(phpClass)) {
            result.put(ArrayKey.of(Str.of(name)), phpClass.getConstant(name));
        }
        return result;
    }

    private static List<String> orderedConstants(PhpClass phpClass) {
        List<String> names = new ArrayList<>();
        ClassTemplate template = template(phpClass);
        if (template == null) {
            names.addAll(phpClass.constantNames());
            return names;
        }
        for (ClassTemplate.Member member : template.ownMembers()) {
            if (member instanceof ClassTemplate.Constant constant && !names.contains(constant.name())) {
                names.add(constant.name());
            } else if (member instanceof ClassTemplate.Case enumCase) {
                names.add(enumCase.name());
            }
        }
        List<PhpClass> inherited = new ArrayList<>();
        if (phpClass.getParent() != null) {
            inherited.add(phpClass.getParent());
        }
        inherited.addAll(phpClass.directInterfaces());
        for (PhpClass from : inherited) {
            for (String name : orderedConstants(from)) {
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * ReflectionMethod::invoke: the method of the class, whatever its visibility, on the object (null for a static
     * method), with positional and named arguments. A by-reference parameter given a value warns as
     * zend_call_function does and gets a reference of its own.
     */
    @TruffleBoundary
    private static Object call(PhpContext context, Object thisValue, TruffleString className, TruffleString methodName,
                               PhpArray arguments) {
        PhpClass phpClass = phpClass(context, className, false);
        PhpFunction method = phpClass.findMethod(Str.lower(Str.java(methodName)));
        PhpObject object = thisValue instanceof PhpObject o ? o : null;
        PhpClass staticClass = object != null ? object.getPhpClass() : phpClass;
        // zend_call_function with the arguments as named_params (ZEND_SEND_ARRAY's binding)
        Callables.Target target = new Callables.Target(method, method.isStatic() ? null : object, staticClass, null);
        return pl.codeloom.php.runtime.Refcount.pending(PhpRef.deref(method.getCallTarget().call(SpreadArguments.sendArray(target, arguments,
                null))));
    }

    /**
     * ReflectionClass::newInstance() and newInstanceArgs(): an instance of the class, as `new` makes it, its
     * constructor called with the arguments as named_params (zend_call_known_function: ZEND_SEND_ARRAY's binding).
     */
    @TruffleBoundary
    private static PhpObject construct(PhpContext context, Object className, PhpArray arguments) {
        PhpClass phpClass = phpClass(context, className, false);
        PhpObject object = phpClass.isThrowable() ? phpClass.instantiate(SourceLocation.current(),
                Backtrace.forThrowable()) : phpClass.instantiate();
        PhpFunction constructor = phpClass.getConstructor();
        if (constructor != null) {
            Callables.Target target = new Callables.Target(constructor, object, phpClass, null);
            // held while the constructor runs, as `new`'s temporary holds it; the built-in's result counts it after
            object.addRef();
            try {
                pl.codeloom.php.runtime.Refcount.done(constructor.getCallTarget().call(SpreadArguments.sendArray(target, arguments, null)));
            } finally {
                object.disown();
            }
        }
        return object;
    }

    /** ReflectionMethod::getClosure: the method as a closure bound to the object (none for a static method). */
    @TruffleBoundary
    private static Object closure(PhpContext context, Object thisValue, TruffleString className,
                                  TruffleString methodName) {
        PhpClass phpClass = phpClass(context, className, false);
        PhpFunction method = phpClass.findMethod(Str.lower(Str.java(methodName)));
        PhpObject object = thisValue instanceof PhpObject o && !method.isStatic() ? o : null;
        PhpClass scope = declaringClass(phpClass, Str.lower(Str.java(methodName)), method);
        return new PhpClosure(method, object, scope, new Object[0]);
    }
}
