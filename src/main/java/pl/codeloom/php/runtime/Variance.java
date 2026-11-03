package pl.codeloom.php.runtime;

import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.ErrorFunctions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * zend_inheritance's signature check (zend_do_perform_implementation_check): a method overriding its parent's,
 * or implementing an interface's, takes at least the same arguments, in the same modes, with parameter types as
 * wide (contravariant) and a return type as narrow (covariant). Methods of internal code, whose return types
 * php-src mostly declares as tentative, aren't checked.
 */
final class Variance {
    private Variance() {
    }

    private static final int SUCCESS = 0;
    private static final int ERROR = 1;
    private static final int UNRESOLVED = 2;

    /**
     * The checks of a class being linked: its methods against its parent's and its own interfaces'. A check
     * that needs an undeclared class waits until the classes it needs are autoloaded (load_delayed_classes),
     * then fails as "Could not check compatibility" if they are still missing.
     */
    static void check(PhpContext context, PhpClass phpClass) {
        PhpClass parent = phpClass.getParent();
        List<PhpFunction[]> delayed = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>();
        for (Map.Entry<String, PhpFunction> entry : phpClass.methods().entrySet()) {
            PhpFunction child = entry.getValue();
            if (parent != null && phpClass.getName().equalsIgnoreCase(child.getClassName())) {
                PhpFunction proto = parent.findMethod(entry.getKey());
                if (proto != null && proto != child && checked(proto, entry.getKey(), false)) {
                    check(context, phpClass, child, proto, delayed, unresolved);
                }
            }
        }
        for (PhpClass iface : interfaces(phpClass)) {
            for (Map.Entry<String, PhpFunction> entry : iface.methods().entrySet()) {
                PhpFunction child = phpClass.findMethod(entry.getKey());
                PhpFunction proto = entry.getValue();
                if (child != null && child != proto && checked(proto, entry.getKey(), true)) {
                    check(context, phpClass, child, proto, delayed, unresolved);
                }
            }
        }
        if (delayed.isEmpty()) {
            return;
        }
        for (String name : List.copyOf(unresolved)) {
            context.findClassAutoloading(name);
        }
        for (PhpFunction[] pair : delayed) {
            unresolved.clear();
            int status = compatible(context, phpClass, pair[0], pair[1], unresolved);
            if (status != SUCCESS) {
                fail(context, pair[0], pair[1], status == UNRESOLVED ? unresolved.iterator().next() : null);
            }
        }
    }

    private static void check(PhpContext context, PhpClass linking, PhpFunction child, PhpFunction proto,
                              List<PhpFunction[]> delayed, Set<String> unresolved) {
        int status = compatible(context, linking, child, proto, unresolved);
        if (status == ERROR) {
            fail(context, child, proto, null);
        }
        if (status == UNRESOLVED) {
            delayed.add(new PhpFunction[] {child, proto});
        }
    }

    private static void fail(PhpContext context, PhpFunction child, PhpFunction proto, String unresolved) {
        String message = unresolved != null
                ? "Could not check compatibility between " + declaration(child) + " and " + declaration(proto)
                        + ", because class " + unresolved + " is not available"
                : "Declaration of " + declaration(child) + " must be compatible with " + declaration(proto);
        throw ErrorFunctions.fatal(context, ErrorFunctions.E_COMPILE_ERROR, Str.of(message), child.getDeclaredAt(),
                0);
    }

    /** The interfaces the class adds (with the interfaces they extend), not its parent's. */
    private static List<PhpClass> interfaces(PhpClass phpClass) {
        List<PhpClass> result = new ArrayList<>();
        List<PhpClass> pending = new ArrayList<>(phpClass.directInterfaces());
        while (!pending.isEmpty()) {
            PhpClass iface = pending.remove(0);
            PhpClass parent = phpClass.getParent();
            if (!result.contains(iface) && (parent == null || !parent.isSubclassOf(iface.getLowerName()))) {
                result.add(iface);
                pending.addAll(iface.directInterfaces());
            }
        }
        return result;
    }

    /** A private method isn't inherited, a constructor only checked when abstract or an interface's. */
    private static boolean checked(PhpFunction proto, String lowerName, boolean fromInterface) {
        if (proto.getDeclaredAt() == null) {
            return false;
        }
        int modifiers = proto.getModifiers();
        boolean isAbstract = fromInterface || (modifiers & PhpFunction.ACC_ABSTRACT) != 0;
        if ((modifiers & PhpFunction.ACC_PRIVATE) != 0 && !isAbstract) {
            return false;
        }
        return !lowerName.equals("__construct") || isAbstract;
    }

    /** zend_do_perform_implementation_check. */
    private static int compatible(PhpContext context, PhpClass linking, PhpFunction fe, PhpFunction proto,
                                  Set<String> unresolved) {
        if (required(proto) < required(fe) || proto.returnsRef() && !fe.returnsRef()) {
            return ERROR;
        }
        PhpFunction.Param[] feParams = fe.getParams();
        PhpFunction.Param[] protoParams = proto.getParams();
        boolean protoVariadic = protoParams.length > 0 && protoParams[protoParams.length - 1].variadic();
        boolean feVariadic = feParams.length > 0 && feParams[feParams.length - 1].variadic();
        if (protoVariadic && !feVariadic) {
            return ERROR;
        }
        int status = SUCCESS;
        for (int i = 0; i < Math.max(feParams.length, protoParams.length); i++) {
            PhpFunction.Param protoParam = i < protoParams.length ? protoParams[i]
                    : protoVariadic ? protoParams[protoParams.length - 1] : null;
            PhpFunction.Param feParam = i < feParams.length ? feParams[i]
                    : feVariadic ? feParams[feParams.length - 1] : null;
            if (protoParam == null) {
                continue;
            }
            if (feParam == null) {
                return ERROR;
            }
            // a child parameter with no type, or mixed, takes anything; the check is contravariant
            if (feParam.declared() != null && !feParam.declared().isMixed()) {
                int local = protoParam.declared() == null ? ERROR
                        : covariant(context, linking, protoParam.declared(), feParam.declared(), unresolved);
                if (local == ERROR) {
                    return ERROR;
                }
                status = Math.max(status, local);
            }
            if (feParam.byRef() != protoParam.byRef()) {
                return ERROR;
            }
        }
        if (proto.getDeclaredReturn() != null) {
            if (fe.getDeclaredReturn() == null) {
                return ERROR;
            }
            int local = covariant(context, linking, fe.getDeclaredReturn(), proto.getDeclaredReturn(), unresolved);
            if (local != SUCCESS) {
                return local;
            }
        }
        return status;
    }

    /** required_num_args: the parameters up to the last one without a default. */
    private static int required(PhpFunction function) {
        PhpFunction.Param[] params = function.getParams();
        int required = 0;
        for (int i = 0; i < params.length; i++) {
            if (params[i].defaultValue() == null && !params[i].variadic()) {
                required = i + 1;
            }
        }
        return required;
    }

    /** zend_perform_covariant_type_check: whether every value of fe is one of proto. */
    private static int covariant(PhpContext context, PhpClass linking, PhpType fe, PhpType proto,
                                 Set<String> unresolved) {
        if (proto.isMixed() && !fe.has(PhpType.VOID)) {
            return SUCCESS;
        }
        int added = fe.mask & ~proto.mask;
        if ((added & PhpType.STATIC) != 0 && permitsSelf(context, linking, proto)) {
            added &= ~PhpType.STATIC;
        }
        if (added == PhpType.NEVER) {
            return SUCCESS;
        }
        if (added != 0) {
            return ERROR;
        }
        if (fe.isIntersection()) {
            return intersectionSubtype(context, linking, fe.intersections()[0], proto, unresolved);
        }
        // every class of the union (an intersection among them too) must be a subtype of proto
        boolean pending = false;
        for (String name : fe.classNames()) {
            int status = classSubtype(context, linking, name, proto, unresolved);
            if (status == ERROR) {
                return ERROR;
            }
            pending |= status == UNRESOLVED;
        }
        for (String[] intersection : fe.intersections()) {
            int status = intersectionSubtype(context, linking, intersection, proto, unresolved);
            if (status == ERROR) {
                return ERROR;
            }
            pending |= status == UNRESOLVED;
        }
        return pending ? UNRESOLVED : SUCCESS;
    }

    /**
     * zend_is_intersection_subtype_of_type: U1&...&Un is of V1&...&Vm when each Vj has a Ui of it, of V1|...|Vm
     * when one Vj has.
     */
    private static int intersectionSubtype(PhpContext context, PhpClass linking, String[] fe, PhpType proto,
                                           Set<String> unresolved) {
        boolean pending = false;
        if (proto.has(PhpType.OBJECT)) {
            for (String name : fe) {
                if (lookup(context, name, unresolved) != null) {
                    return SUCCESS;
                }
                pending = true;
            }
        }
        if (proto.isIntersection()) {
            return intersectionOfAll(context, fe, proto.intersections()[0], unresolved);
        }
        for (String protoName : proto.classNames()) {
            int status = intersectionOfClass(context, fe, protoName, unresolved);
            if (status == SUCCESS) {
                return SUCCESS;
            }
            pending |= status == UNRESOLVED;
        }
        for (String[] intersection : proto.intersections()) {
            int status = intersectionOfAll(context, fe, intersection, unresolved);
            if (status == SUCCESS) {
                return SUCCESS;
            }
            pending |= status == UNRESOLVED;
        }
        return pending ? UNRESOLVED : ERROR;
    }

    private static int intersectionOfAll(PhpContext context, String[] fe, String[] proto, Set<String> unresolved) {
        boolean pending = false;
        for (String protoName : proto) {
            int status = intersectionOfClass(context, fe, protoName, unresolved);
            if (status == ERROR) {
                return ERROR;
            }
            pending |= status == UNRESOLVED;
        }
        return pending ? UNRESOLVED : SUCCESS;
    }

    /** zend_is_intersection_subtype_of_class: some class of the intersection is of the class. */
    private static int intersectionOfClass(PhpContext context, String[] fe, String protoName,
                                           Set<String> unresolved) {
        boolean pending = false;
        for (String name : fe) {
            if (name.equalsIgnoreCase(protoName)) {
                return SUCCESS;
            }
            Object protoClass = lookup(context, protoName, unresolved);
            Object feClass = lookup(context, name, unresolved);
            if (feClass == null || protoClass == null) {
                pending = true;
            } else if (instanceOf(context, feClass, protoClass)) {
                return SUCCESS;
            }
        }
        return pending ? UNRESOLVED : ERROR;
    }

    /**
     * zend_is_class_subtype_of_type: the class is of proto's object, of one of its classes or intersections (of all
     * the classes of an intersection proto), Closure of callable, or, in a final class, the class itself for
     * static.
     */
    private static int classSubtype(PhpContext context, PhpClass linking, String name, PhpType proto,
                                    Set<String> unresolved) {
        boolean pending = false;
        if (proto.has(PhpType.OBJECT)) {
            if (lookup(context, name, unresolved) != null) {
                return SUCCESS;
            }
            pending = true;
        }
        if (proto.has(PhpType.CALLABLE) && name.equalsIgnoreCase("Closure")) {
            return SUCCESS;
        }
        if (proto.has(PhpType.STATIC) && linking.getDeclaration() instanceof ClassTemplate template
                && (template.getFlags() & PhpFunction.ACC_FINAL) != 0) {
            Object feClass = lookup(context, name, unresolved);
            if (feClass == null) {
                pending = true;
            } else if (feClass == lookup(context, linking.getName(), unresolved)) {
                return SUCCESS;
            }
        }
        if (proto.isIntersection()) {
            int status = classOfAll(context, name, proto.intersections()[0], unresolved);
            return status == SUCCESS && pending ? UNRESOLVED : status;
        }
        for (String protoName : proto.classNames()) {
            int status = classOfAll(context, name, new String[] {protoName}, unresolved);
            if (status == SUCCESS) {
                return SUCCESS;
            }
            pending |= status == UNRESOLVED;
        }
        for (String[] intersection : proto.intersections()) {
            int status = classOfAll(context, name, intersection, unresolved);
            if (status == SUCCESS) {
                return SUCCESS;
            }
            pending |= status == UNRESOLVED;
        }
        return pending ? UNRESOLVED : ERROR;
    }

    private static int classOfAll(PhpContext context, String name, String[] proto, Set<String> unresolved) {
        boolean pending = false;
        for (String protoName : proto) {
            int status = classOfClass(context, name, protoName, unresolved);
            if (status == ERROR) {
                return ERROR;
            }
            pending |= status == UNRESOLVED;
        }
        return pending ? UNRESOLVED : SUCCESS;
    }

    private static int classOfClass(PhpContext context, String name, String protoName, Set<String> unresolved) {
        if (name.equalsIgnoreCase(protoName)) {
            return SUCCESS;
        }
        Object feClass = lookup(context, name, unresolved);
        Object protoClass = lookup(context, protoName, unresolved);
        if (feClass == null || protoClass == null) {
            return UNRESOLVED;
        }
        return instanceOf(context, feClass, protoClass) ? SUCCESS : ERROR;
    }

    /** zend_type_permits_self: the type takes the class being linked (static for self). */
    private static boolean permitsSelf(PhpContext context, PhpClass linking, PhpType type) {
        if (type.has(PhpType.OBJECT)) {
            return true;
        }
        Object self = lookup(context, linking.getName(), null);
        for (String name : type.classNames()) {
            Object found = lookup(context, name, null);
            if (found != null && instanceOf(context, self, found)) {
                return true;
            }
        }
        return false;
    }

    /**
     * lookup_class: a declared class, or one being declared (ZEND_FETCH_CLASS_ALLOW_UNLINKED: its template), not
     * autoloaded; a missing one is noted for load_delayed_classes.
     */
    private static Object lookup(PhpContext context, String name, Set<String> unresolved) {
        String lower = Str.lower(name);
        Object found = context.findClass(lower);
        if (found == null) {
            found = context.linkingClass(lower);
        }
        if (found == null && unresolved != null) {
            unresolved.add(name);
        }
        return found;
    }

    /** unlinked_instanceof: a class being declared is of its parent's and interfaces' classes, by name. */
    private static boolean instanceOf(PhpContext context, Object fe, Object proto) {
        if (fe == proto) {
            return true;
        }
        if (fe instanceof PhpClass phpClass) {
            return proto instanceof PhpClass protoClass && phpClass.isSubclassOf(protoClass.getLowerName());
        }
        ClassTemplate template = (ClassTemplate) fe;
        List<String> supers = new ArrayList<>(template.getInterfaceNames());
        if (template.getParentName() != null) {
            supers.add(template.getParentName());
        }
        for (String name : supers) {
            Object found = lookup(context, name, null);
            if (found != null && instanceOf(context, found, proto)) {
                return true;
            }
        }
        return false;
    }

    /** zend_get_function_declaration: "& C::name(int $a, ?Foo &...$rest = null): int". */
    static String declaration(PhpFunction function) {
        StringBuilder text = new StringBuilder();
        if (function.returnsRef()) {
            text.append("& ");
        }
        if (function.getClassName() != null) {
            String className = function.getClassName();
            int nul = className.indexOf('\0');
            text.append(nul < 0 ? className : className.substring(0, nul)).append("::");
        }
        String name = function.getName();
        text.append(name.substring(name.lastIndexOf(':') + 1)).append('(');
        PhpFunction.Param[] params = function.getParams();
        int required = required(function);
        for (int i = 0; i < params.length; i++) {
            PhpFunction.Param param = params[i];
            if (i > 0) {
                text.append(", ");
            }
            if (param.declared() != null) {
                text.append(param.declared().text(null)).append(' ');
            }
            text.append(param.byRef() ? "&" : "").append(param.variadic() ? "..." : "").append('$')
                    .append(param.name());
            if (i >= required && !param.variadic() && param.defaultValue() != null) {
                text.append(" = ").append(param.defaultValue());
            }
        }
        text.append(')');
        if (function.getDeclaredReturn() != null) {
            text.append(": ").append(function.getDeclaredReturn().text(null));
        }
        return text.toString();
    }
}
