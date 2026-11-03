package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
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
        Object[] values = arguments(context, method, arguments);
        return new Callables.Target(method, method.isStatic() ? null : object, staticClass, null).call(values);
    }

    private static Object[] arguments(PhpContext context, PhpFunction function, PhpArray arguments) {
        PhpFunction.Param[] params = function.getParams();
        List<Object> values = new ArrayList<>();
        for (int position = arguments.firstPosition(); arguments.isValidPosition(position);
             position = arguments.nextPosition(position)) {
            Object key = arguments.keyAt(position);
            Object value = arguments.valueAt(position);
            int index = values.size();
            if (key instanceof TruffleString name) {
                index = -1;
                for (int i = 0; i < params.length; i++) {
                    if (params[i].name().equals(Str.java(name)) && !params[i].variadic()) {
                        index = i;
                    }
                }
                if (index < 0) {
                    throw new PhpError("Error", "Unknown named parameter $" + Str.java(name));
                }
                if (index < values.size() && values.get(index) != null) {
                    throw new PhpError("Error", "Named parameter $" + Str.java(name)
                            + " overwrites previous argument");
                }
            }
            while (values.size() <= index) {
                values.add(null);
            }
            values.set(index, byValue(context, function, index, value));
        }
        // a skipped parameter before a named one takes its default: only the trailing ones may stay unsent
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == null) {
                throw new PhpError("ArgumentCountError", function.displayName() + "(): Argument #" + (i + 1)
                        + " ($" + params[i].name() + ") not passed");
            }
        }
        return values.toArray();
    }

    private static Object byValue(PhpContext context, PhpFunction function, int index, Object value) {
        if (!function.isByRef(index)) {
            return PhpRef.deref(value);
        }
        if (value instanceof PhpRef) {
            return value;
        }
        PhpFunction.Param[] params = function.getParams();
        String name = index < params.length ? params[index].name() : params[params.length - 1].name();
        ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of(function.displayName() + "(): Argument #"
                + (index + 1) + " ($" + name + ") must be passed by reference, value given"),
                SourceLocation.current());
        return new PhpRef(value);
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
