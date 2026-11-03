package pl.codeloom.php.runtime;

import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.ErrorFunctions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * zend_inheritance's signature check (zend_do_perform_implementation_check): a method overriding its parent's,
 * or implementing an interface's, takes at least the same arguments, in the same modes, with parameter types as
 * wide (contravariant) and a return type as narrow (covariant). Methods of internal code, whose return types
 * php-src mostly declares as tentative, aren't checked.
 */
final class Variance {
    private Variance() {
    }

    /** A class a check needs isn't declared (INHERITANCE_UNRESOLVED). */
    private static final class Unresolved extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String name;

        Unresolved(String name) {
            super(null, null, false, false);
            this.name = name;
        }
    }

    /** The checks of a class being linked: its methods against its parent's and its own interfaces'. */
    static void check(PhpContext context, PhpClass phpClass) {
        PhpClass parent = phpClass.getParent();
        for (Map.Entry<String, PhpFunction> entry : phpClass.methods().entrySet()) {
            PhpFunction child = entry.getValue();
            if (parent != null && phpClass.getName().equalsIgnoreCase(child.getClassName())) {
                PhpFunction proto = parent.findMethod(entry.getKey());
                if (proto != null && proto != child && checked(proto, entry.getKey(), false)) {
                    check(context, phpClass, child, proto);
                }
            }
        }
        for (PhpClass iface : interfaces(phpClass)) {
            for (Map.Entry<String, PhpFunction> entry : iface.methods().entrySet()) {
                PhpFunction child = phpClass.findMethod(entry.getKey());
                PhpFunction proto = entry.getValue();
                if (child != null && child != proto && checked(proto, entry.getKey(), true)) {
                    check(context, phpClass, child, proto);
                }
            }
        }
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

    private static void check(PhpContext context, PhpClass linking, PhpFunction child, PhpFunction proto) {
        String unresolved = null;
        boolean compatible;
        try {
            compatible = compatible(context, linking, child, proto);
        } catch (Unresolved e) {
            compatible = false;
            unresolved = e.name;
        }
        if (compatible) {
            return;
        }
        String message = unresolved != null
                ? "Could not check compatibility between " + declaration(child) + " and " + declaration(proto)
                        + ", because class " + unresolved + " is not available"
                : "Declaration of " + declaration(child) + " must be compatible with " + declaration(proto);
        throw ErrorFunctions.fatal(context, ErrorFunctions.E_COMPILE_ERROR, Str.of(message), child.getDeclaredAt(),
                0);
    }

    private static boolean compatible(PhpContext context, PhpClass linking, PhpFunction fe, PhpFunction proto) {
        if (required(proto) < required(fe) || proto.returnsRef() && !fe.returnsRef()) {
            return false;
        }
        PhpFunction.Param[] feParams = fe.getParams();
        PhpFunction.Param[] protoParams = proto.getParams();
        boolean protoVariadic = protoParams.length > 0 && protoParams[protoParams.length - 1].variadic();
        boolean feVariadic = feParams.length > 0 && feParams[feParams.length - 1].variadic();
        if (protoVariadic && !feVariadic) {
            return false;
        }
        Unresolved pending = null;
        for (int i = 0; i < Math.max(feParams.length, protoParams.length); i++) {
            PhpFunction.Param protoParam = i < protoParams.length ? protoParams[i]
                    : protoVariadic ? protoParams[protoParams.length - 1] : null;
            PhpFunction.Param feParam = i < feParams.length ? feParams[i]
                    : feVariadic ? feParams[feParams.length - 1] : null;
            if (protoParam == null) {
                continue;
            }
            if (feParam == null) {
                return false;
            }
            // a child parameter with no type, or mixed, takes anything
            if (feParam.declared() != null && !feParam.declared().isMixed()) {
                if (protoParam.declared() == null) {
                    return false;
                }
                try {
                    if (!covariant(context, linking, protoParam.declared(), feParam.declared())) {
                        return false;
                    }
                } catch (Unresolved e) {
                    pending = pending == null ? e : pending;
                }
            }
            if (feParam.byRef() != protoParam.byRef()) {
                return false;
            }
        }
        if (proto.getDeclaredReturn() != null) {
            if (fe.getDeclaredReturn() == null) {
                return false;
            }
            if (!covariant(context, linking, fe.getDeclaredReturn(), proto.getDeclaredReturn())) {
                return false;
            }
        }
        if (pending != null) {
            throw pending;
        }
        return true;
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
    private static boolean covariant(PhpContext context, PhpClass linking, PhpType fe, PhpType proto) {
        if (proto.isMixed() && !fe.has(PhpType.VOID)) {
            return true;
        }
        int added = fe.mask & ~proto.mask;
        if ((added & PhpType.STATIC) != 0 && permitsSelf(context, linking, proto)) {
            added &= ~PhpType.STATIC;
        }
        if (added == PhpType.NEVER) {
            return true;
        }
        if (added != 0) {
            return false;
        }
        Unresolved pending = null;
        if (fe.isIntersection()) {
            // A&B is a subtype when one of its classes is of each of proto's alternatives... of proto as a whole
            for (String name : fe.intersections()[0]) {
                try {
                    if (classSubtype(context, linking, name, proto)) {
                        return true;
                    }
                } catch (Unresolved e) {
                    pending = pending == null ? e : pending;
                }
            }
            if (pending != null) {
                throw pending;
            }
            return proto.has(PhpType.OBJECT);
        }
        for (String name : fe.classNames()) {
            try {
                if (!classSubtype(context, linking, name, proto)) {
                    return false;
                }
            } catch (Unresolved e) {
                pending = pending == null ? e : pending;
            }
        }
        for (String[] intersection : fe.intersections()) {
            boolean any = false;
            for (String name : intersection) {
                try {
                    any |= classSubtype(context, linking, name, proto);
                } catch (Unresolved e) {
                    pending = pending == null ? e : pending;
                }
            }
            if (!any && pending == null && !proto.has(PhpType.OBJECT)) {
                return false;
            }
        }
        if (pending != null) {
            throw pending;
        }
        return true;
    }

    /** zend_is_class_subtype_of_type: the class is of proto's object, one of its classes, or all of one of its intersections. */
    private static boolean classSubtype(PhpContext context, PhpClass linking, String name, PhpType proto) {
        if (proto.has(PhpType.OBJECT)) {
            lookup(context, linking, name);
            return true;
        }
        for (String protoName : proto.classNames()) {
            if (isA(context, linking, name, protoName)) {
                return true;
            }
        }
        for (String[] intersection : proto.intersections()) {
            boolean all = true;
            for (String protoName : intersection) {
                all &= isA(context, linking, name, protoName);
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static boolean isA(PhpContext context, PhpClass linking, String name, String protoName) {
        if (name.equalsIgnoreCase(protoName)) {
            return true;
        }
        PhpClass fe = lookup(context, linking, name);
        lookup(context, linking, protoName);
        return fe.isSubclassOf(Str.lower(protoName));
    }

    /** zend_type_permits_self: the type takes the class being linked (static for self). */
    private static boolean permitsSelf(PhpContext context, PhpClass linking, PhpType type) {
        if (type.has(PhpType.OBJECT)) {
            return true;
        }
        for (String name : type.classNames()) {
            if (linking.isSubclassOf(Str.lower(name))) {
                return true;
            }
        }
        return false;
    }

    private static PhpClass lookup(PhpContext context, PhpClass linking, String name) {
        if (linking.getName().equalsIgnoreCase(name)) {
            return linking;
        }
        PhpClass found = context.findClassAutoloading(name);
        if (found == null) {
            throw new Unresolved(name);
        }
        return found;
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
