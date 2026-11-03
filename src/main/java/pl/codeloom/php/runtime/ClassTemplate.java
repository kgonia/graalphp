package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.ErrorFunctions;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A class declaration as translated: language-level, shared by every context. Running the declaration in a
 * context instantiates it into that context's {@link PhpClass}, whose static properties, evaluated constants and
 * resolved parent are the context's own. The methods are the same PhpFunction objects in every context, so their
 * call targets (and compiled code) are shared.
 */
public final class ClassTemplate {
    /** A constant expression that doesn't fold: bound to each context's class, evaluated there on first use. */
    public interface Deferred {
        PhpClass.Thunk bind(PhpClass phpClass);
    }

    /** A class member, applied to a new PhpClass in declaration order. */
    public sealed interface Member {
    }

    /** A method, with the properties its constructor promotes (`public function __construct(private $x)`). */
    public record Method(PhpFunction function, List<Property> promoted) implements Member {
    }

    /** A class constant: its value, or a {@link Deferred} initializer. */
    public record Constant(String name, Object value) implements Member {
    }

    /**
     * A property: its default value, or a {@link Deferred} initializer; its php-ast modifier flags, its hooks as
     * declared (null for none), and whether it has a default written.
     */
    public record Property(String name, Object initial, int visibility, boolean isStatic, int flags,
                           PhpClass.Hooks hooks, boolean hasDefault) implements Member {
        public Property(String name, Object initial, int visibility, boolean isStatic) {
            this(name, initial, visibility, isStatic, 0, null, false);
        }
    }

    /** php-ast's final modifier. */
    private static final int FINAL = 32;

    /**
     * `use A, B { A::m insteadof B; m as n; }`: traits by resolved name, aliases and exclusions. Method names are
     * lower case.
     */
    public record TraitUse(List<String> traits, List<Alias> aliases, List<Exclusion> exclusions) implements Member {
    }

    /** `[Trait::]method as [visibility] alias`; alias is null when only the visibility changes. */
    public record Alias(String trait, String method, String alias) {
    }

    /** `Trait::method insteadof Other, ...`: the method of the listed traits is not imported. */
    public record Exclusion(String trait, String method, List<String> excluded) {
    }

    /**
     * A trait's members, translated for the class that uses it: self, parent and __CLASS__ in a trait's methods
     * are the using class's, so each using class gets its own PhpFunctions.
     */
    public interface TraitBody {
        List<Member> members(String className, String parentName);
    }

    private final String name;
    private final String parentName;
    private final List<String> interfaces;
    private final boolean isInterface;
    private final boolean isAbstract;
    private final List<Member> members;
    /** Why the translator couldn't handle a member, or null: declaring the class then fails with it. */
    private final String unsupported;
    /** A trait's body, or null for a class or interface. */
    private final TraitBody traitBody;

    public boolean isTrait() {
        return traitBody != null;
    }
    /** The used traits' members, translated for this class once (keyed by the trait's template). */
    private final Map<ClassTemplate, List<Member>> traitMembers = new ConcurrentHashMap<>();

    public ClassTemplate(String name, String parentName, List<String> interfaces, boolean isInterface,
                         boolean isAbstract, List<Member> members, String unsupported) {
        this(name, parentName, interfaces, isInterface, isAbstract, members, unsupported, null);
    }

    public ClassTemplate(String name, String parentName, List<String> interfaces, boolean isInterface,
                         boolean isAbstract, List<Member> members, String unsupported, TraitBody traitBody) {
        this.traitBody = traitBody;
        this.name = name;
        this.parentName = parentName;
        this.interfaces = List.copyOf(interfaces);
        this.isInterface = isInterface;
        this.isAbstract = isAbstract;
        this.members = List.copyOf(members);
        this.unsupported = unsupported;
    }

    /** Where the class was declared. */
    private SourceLocation declaredAt;

    public void setDeclaredAt(SourceLocation declaredAt) {
        this.declaredAt = declaredAt;
    }

    public SourceLocation getDeclaredAt() {
        return declaredAt;
    }

    public String getName() {
        return name;
    }

    /** The class's ZEND_ACC_* flags as declared (final, abstract, readonly), its doc comment and last line. */
    private int flags;
    private String docComment;
    private int endLine;

    public void setDeclaration(int flags, String docComment, int endLine) {
        this.flags = flags;
        this.docComment = docComment;
        this.endLine = endLine;
    }

    public int getFlags() {
        return flags;
    }

    public String getDocComment() {
        return docComment;
    }

    public int getEndLine() {
        return endLine;
    }

    public String getParentName() {
        return parentName;
    }

    /** The interfaces the declaration names, resolved. */
    public List<String> getInterfaceNames() {
        return interfaces;
    }

    public List<Member> getMembers() {
        return members;
    }

    /**
     * The members as declared: a class's, or a trait's translated for the trait itself (a trait has its own
     * function table in php-src too).
     */
    public List<Member> ownMembers() {
        return traitBody == null ? members : traitMembers.computeIfAbsent(this, t -> t.traitBody.members(name, null));
    }

    /** Why the translator couldn't handle a member, or null. */
    public String getUnsupported() {
        return unsupported;
    }

    /** This context's class for the declaration: parent and interfaces resolved by name in the context. */
    @TruffleBoundary
    public PhpClass instantiate(PhpContext context) {
        PhpClass parent = parentName == null ? null : context.getClass(parentName);
        PhpClass phpClass = new PhpClass(name, parent, isInterface, isAbstract, context.getLanguage().rootShape(),
                this);
        for (String iface : interfaces) {
            phpClass.addInterface(context.getClass(iface));
        }
        if (unsupported != null) {
            throw new PhpError(unsupported);
        }
        List<Member> declared = ownMembers();
        Set<String> own = new HashSet<>();
        for (Member member : declared) {
            if (member instanceof Method method) {
                own.add(Str.lower(method.function().getName()));
            }
            add(phpClass, member);
        }
        for (Member member : declared) {
            if (member instanceof TraitUse use) {
                useTraits(context, phpClass, use, own);
            }
        }
        for (PhpClass iface : phpClass.directInterfaces()) {
            inheritInterfaceProperties(context, phpClass, iface);
        }
        if (!isInterface && !isAbstract && traitBody == null && declaredAt != null) {
            verifyAbstract(context, phpClass);
        }
        return phpClass;
    }

    private void add(PhpClass phpClass, Member member) {
        if (member instanceof Method method) {
            for (Property property : method.promoted()) {
                add(phpClass, property);
            }
            phpClass.addMethod(method.function());
        } else if (member instanceof Constant constant) {
            phpClass.addConstant(constant.name(), bind(constant.value(), phpClass));
        } else if (member instanceof Property property) {
            Object initial = bind(property.initial(), phpClass);
            if (property.isStatic()) {
                phpClass.addStaticProperty(property.name(), initial);
            } else {
                inheritHooks(phpClass, property);
                phpClass.addProperty(new PhpClass.PropertyDecl(property.name(), initial, property.visibility()));
                phpClass.setSetVisibility(property.name(), property.flags());
            }
        }
    }

    /**
     * A property declared over an inherited one (do_inherit_property): a final one can't be; the child is backed
     * if the parent's is; the hooks it doesn't declare are the parent's (inherit_property_hook), but an abstract
     * one that a backed property implements, and a final hook can't be overridden.
     */
    private void inheritHooks(PhpClass phpClass, Property property) {
        String name = property.name();
        PhpClass.PropertyDecl inherited = phpClass.findProperty(name);
        if (inherited != null && inherited.visibility() == PhpClass.PropertyDecl.PRIVATE) {
            inherited = null;
        }
        if (inherited != null && phpClass.isFinalProperty(name)) {
            throw fatal("Cannot override final property " + className(phpClass, inherited) + "::$" + Str.keyText(name));
        }
        int childSet = property.flags() & (1024 | 2048 | 4096);
        PhpClass.Hooks inheritedHooks = inherited == null ? null : phpClass.findHooks(name);
        if (inherited != null && childSet != 0
                && !(inheritedHooks != null && inheritedHooks.virtual() && inheritedHooks.set() == null)) {
            // a set visibility no stricter than the parent's (its read visibility's, if it has none)
            int parentSet = phpClass.setVisibilityFlag(name);
            int visibility = inherited.visibility();
            int effective = parentSet != 0 ? parentSet : visibility == PhpClass.PropertyDecl.PRIVATE ? 4096
                    : visibility == PhpClass.PropertyDecl.PROTECTED ? 2048 : 1024;
            if (childSet > effective) {
                throw fatal("Set access level of " + phpClass.getName() + "::$" + Str.keyText(name) + " must be "
                        + (parentSet == 0 ? "omitted" : parentSet == 4096 ? "private(set)" : parentSet == 2048
                        ? "protected(set)" : "public(set)") + " (as in class " + className(phpClass, inherited) + ")"
                        + (parentSet == 0 ? "" : " or weaker"));
            }
        }
        // private(set) is implicitly final
        if ((property.flags() & (FINAL | 4096)) != 0) {
            phpClass.setFinalProperty(name);
        }
        PhpClass.Hooks parent = inherited == null ? null : phpClass.findHooks(name);
        PhpClass.Hooks own = property.hooks();
        if (own == null && parent == null) {
            phpClass.setHooks(name, null);
            return;
        }
        boolean virtual = own != null && own.virtual() && (inherited == null || parent != null && parent.virtual());
        PhpFunction get = own == null ? null : own.get();
        PhpFunction set = own == null ? null : own.set();
        if (parent != null) {
            get = inheritHook(get, parent.get(), !virtual);
            set = inheritHook(set, parent.set(), !virtual);
        }
        if (virtual && property.hasDefault()) {
            throw fatal("Cannot specify default value for virtual hooked property " + phpClass.getName() + "::$"
                    + Str.keyText(name));
        }
        if (!virtual && get != null && get.returnsRef() && set != null && !get.isAbstract()) {
            throw fatal("Get hook of backed property " + phpClass.getName() + "::" + Str.keyText(name)
                    + " with set hook may not return by reference");
        }
        phpClass.setHooks(name, new PhpClass.Hooks(get, set, virtual));
    }

    /** One hook of a property declared over an inherited one: the child's, else the parent's (see inheritHooks). */
    private PhpFunction inheritHook(PhpFunction child, PhpFunction parent, boolean backed) {
        if (parent == null) {
            return child;
        }
        if (child == null) {
            return parent.isAbstract() && backed ? null : parent;
        }
        if ((parent.getModifiers() & PhpFunction.ACC_FINAL) != 0 && (parent.getModifiers() & PhpFunction.ACC_PRIVATE) == 0) {
            throw fatal("Cannot override final property hook " + parent.getClassName() + "::" + parent.getName() + "()");
        }
        return child;
    }

    /**
     * An interface's properties (do_inherit_iface... do_inherit_property): those the class doesn't declare are its
     * own then, abstract; for those it does, the interface's hooks it doesn't implement.
     */
    private void inheritInterfaceProperties(PhpContext context, PhpClass phpClass, PhpClass iface) {
        for (PhpClass.PropertyDecl decl : iface.getProperties()) {
            PhpClass.Hooks required = iface.findHooks(decl.name());
            if (phpClass.findProperty(decl.name()) == null) {
                phpClass.addProperty(decl);
                phpClass.setHooks(decl.name(), required);
            } else if (required != null) {
                PhpClass.Hooks hooks = phpClass.findHooks(decl.name());
                boolean backed = hooks == null || !hooks.virtual();
                PhpFunction get = hooks == null ? null : hooks.get();
                PhpFunction set = hooks == null ? null : hooks.set();
                get = get != null ? get : inheritHook(null, required.get(), backed);
                set = set != null ? set : inheritHook(null, required.set(), backed);
                if (get != null || set != null) {
                    phpClass.setHooks(decl.name(), new PhpClass.Hooks(get, set, !backed));
                }
            }
        }
    }

    /**
     * zend_verify_abstract_class: a class that isn't abstract has no abstract method or hook left; the error names
     * the first three.
     */
    private void verifyAbstract(PhpContext context, PhpClass phpClass) {
        List<String> missing = new java.util.ArrayList<>();
        for (PhpFunction method : phpClass.methods().values()) {
            if (method.isAbstract()) {
                missing.add(method.displayName());
            }
        }
        for (PhpClass.PropertyDecl decl : phpClass.getProperties()) {
            PhpClass.Hooks hooks = phpClass.findHooks(decl.name());
            if (hooks != null) {
                for (PhpFunction hook : new PhpFunction[] {hooks.get(), hooks.set()}) {
                    if (hook != null && hook.isAbstract()) {
                        missing.add(hook.displayName());
                    }
                }
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        int count = missing.size();
        String listed = String.join(", ", missing.subList(0, Math.min(3, count))) + (count > 3 ? ", ..." : "");
        String plural = count > 1 ? "s" : "";
        boolean enumOrAnonymous = (flags & (1 << 28)) != 0 || name.contains("@anonymous");
        String kind = (flags & (1 << 28)) != 0 ? "Enum " : "Class ";
        String className = name.split("\0", 2)[0];
        throw ErrorFunctions.fatal(context, ErrorFunctions.E_ERROR, Str.of(enumOrAnonymous
                ? kind + className + " must implement " + count + " abstract method" + plural + " (" + listed + ")"
                : kind + className + " contains " + count + " abstract method" + plural
                + " and must therefore be declared abstract or implement the remaining method" + plural + " ("
                + listed + ")"), declaredAt, 0);
    }

    /** A compile error of the declaration's (zend_error_noreturn(E_COMPILE_ERROR)), at its line. */
    private RuntimeException fatal(String message) {
        return ErrorFunctions.fatal(PhpContext.get(null), ErrorFunctions.E_COMPILE_ERROR, Str.of(message),
                declaredAt != null ? declaredAt : SourceLocation.current(), 0);
    }

    /** The name, as written, of the class that declared an inherited property. */
    private static String className(PhpClass phpClass, PhpClass.PropertyDecl decl) {
        for (PhpClass c = phpClass; c != null; c = c.getParent()) {
            if (c.getLowerName().equals(decl.declaringClass())) {
                return c.getName();
            }
        }
        return decl.declaringClass();
    }

    /**
     * Imports the traits' members: the class's own methods win over a trait's, a trait's over inherited ones, and
     * a trait's abstract method doesn't replace an inherited implementation.
     */
    private void useTraits(PhpContext context, PhpClass phpClass, TraitUse use, Set<String> own) {
        for (String traitName : use.traits()) {
            PhpClass trait = context.getClass(traitName);
            if (!(trait.getDeclaration() instanceof ClassTemplate template) || template.traitBody == null) {
                throw new PhpError(phpClass.getName() + " cannot use " + trait.getName() + " - it is not a trait");
            }
            phpClass.addTrait(trait);
            String traitLower = trait.getLowerName();
            List<Member> imported = traitMembers.computeIfAbsent(template,
                    t -> t.traitBody.members(name, parentName));
            for (Member member : imported) {
                if (member instanceof Method method) {
                    PhpFunction function = method.function();
                    String lower = Str.lower(function.getName());
                    for (Alias alias : use.aliases()) {
                        if (alias.alias() != null && alias.method().equals(lower)
                                && (alias.trait() == null || alias.trait().equalsIgnoreCase(traitLower))) {
                            phpClass.addMethod(alias.alias(), function);
                        }
                    }
                    if (own.contains(lower) || excluded(use, traitLower, lower)
                            || function.isAbstract() && phpClass.findMethod(lower) != null) {
                        continue;
                    }
                    add(phpClass, member);
                } else if (member instanceof TraitUse nested) {
                    useTraits(context, phpClass, nested, own);
                } else {
                    add(phpClass, member);
                }
            }
        }
    }

    private static boolean excluded(TraitUse use, String traitLower, String method) {
        for (Exclusion exclusion : use.exclusions()) {
            if (exclusion.method().equals(method)) {
                for (String excluded : exclusion.excluded()) {
                    if (excluded.equalsIgnoreCase(traitLower)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Object bind(Object valueOrDeferred, PhpClass phpClass) {
        return valueOrDeferred instanceof Deferred deferred ? deferred.bind(phpClass) : valueOrDeferred;
    }
}
