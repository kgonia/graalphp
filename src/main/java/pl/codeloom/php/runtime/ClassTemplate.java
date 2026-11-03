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
    public record Method(PhpFunction function, List<PhpClass.PropertyDecl> promoted) implements Member {
    }

    /** A class constant: its value, or a {@link Deferred} initializer. */
    public record Constant(String name, Object value) implements Member {
    }

    /**
     * A property: its default value, a {@link Deferred} initializer, or null for a typed one without a default;
     * its php-ast modifiers and type as PhpClass.PropertyDecl's.
     */
    public record Property(String name, Object initial, int visibility, boolean isStatic, int modifiers,
                           String type) implements Member {
    }

    /** An enum case: its backing value, a {@link Deferred} one, or null in a pure enum. */
    public record Case(String name, Object value) implements Member {
    }

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

    /** php-ast's class flags. */
    public static final int ENUM = 1 << 28;
    public static final int READONLY = 1 << 16;
    public static final int FINAL = 32;

    public boolean isEnum() {
        return (flags & ENUM) != 0;
    }

    /** A backed enum's type ("int" or "string"), else null. */
    private String backingType;

    public String getBackingType() {
        return backingType;
    }

    public void setBackingType(String backingType) {
        this.backingType = backingType;
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
        if (parent != null) {
            checkParent(context, parent);
        }
        Set<String> implemented = new HashSet<>();
        for (String iface : interfaces) {
            PhpClass declared = context.getClass(iface);
            checkInterface(context, declared);
            if (!implemented.add(declared.getLowerName())) {
                throw linkError(context, (isEnum() ? "Enum " : isInterface ? "Interface " : "Class ") + name
                        + " cannot implement previously implemented interface " + declared.getName());
            }
            phpClass.addInterface(declared);
        }
        if (unsupported != null) {
            throw new PhpError(unsupported);
        }
        if (isEnum()) {
            // zend_enum_add_interfaces, zend_enum_register_props and _funcs
            phpClass.addInterface(context.getClass("UnitEnum"));
            phpClass.addProperty(new PhpClass.PropertyDecl("name", null, PhpClass.PropertyDecl.PUBLIC, null,
                    PhpClass.PropertyDecl.READONLY, "string"));
            if (backingType != null) {
                phpClass.addInterface(context.getClass("BackedEnum"));
                phpClass.addProperty(new PhpClass.PropertyDecl("value", null, PhpClass.PropertyDecl.PUBLIC, null,
                        PhpClass.PropertyDecl.READONLY, backingType));
            }
            for (PhpFunction method : context.getLanguage().builtins().enumMethods()) {
                if (backingType != null || method.getName().equals("cases")) {
                    phpClass.addMethod(method);
                }
            }
            phpClass.setHandlers(new PhpClass.ObjectHandlers("Cannot instantiate enum " + name, null, true, false,
                    false, true));
        }
        List<Member> declared = ownMembers();
        Set<String> own = new HashSet<>();
        if (isEnum()) {
            // the enum's own methods: a trait's of the name doesn't replace them
            own.addAll(Set.of("cases", "from", "tryfrom"));
        }
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
        if (isEnum()) {
            checkEnum(context, phpClass);
        }
        return phpClass;
    }

    /** A compile error of the class's linking (zend_do_inheritance, zend_enum's checks) at its declaration. */
    private PhpBailout linkError(PhpContext context, String message) {
        return ErrorFunctions.fatal(context, ErrorFunctions.E_COMPILE_ERROR, Str.of(message), declaredAt, 0);
    }

    private void checkParent(PhpContext context, PhpClass parent) {
        if (parent.isEnum()) {
            throw linkError(context, "Class " + name + " cannot extend enum " + parent.getName());
        }
        int parentFlags = parent.getDeclaration() instanceof ClassTemplate template ? template.flags : 0;
        if ((parentFlags & FINAL) != 0) {
            throw linkError(context, "Class " + name + " cannot extend final class " + parent.getName());
        }
        if ((parentFlags & READONLY) != (flags & READONLY)) {
            throw linkError(context, ((flags & READONLY) != 0 ? "Readonly class " + name
                    + " cannot extend non-readonly" : "Non-readonly class " + name + " cannot extend readonly")
                    + " class " + parent.getName());
        }
    }

    /** zend_implement_unit_enum, zend_implement_backed_enum and the interfaces an enum can't implement. */
    private void checkInterface(PhpContext context, PhpClass iface) {
        boolean unit = iface.isDeclaredSubclassOf("unitenum");
        boolean backed = iface.isDeclaredSubclassOf("backedenum");
        if (!isEnum()) {
            if (unit && !isInterface) {
                throw linkError(context, "Non-enum class " + name + " cannot implement interface "
                        + (backed ? "BackedEnum" : "UnitEnum"));
            }
            return;
        }
        if (backed && backingType == null) {
            throw linkError(context, "Non-backed enum " + name + " cannot implement interface BackedEnum");
        }
        if (iface.getLowerName().equals("unitenum") || iface.getLowerName().equals("backedenum")) {
            // zend_enum_add_interfaces implemented it already
            throw linkError(context, "Enum " + name + " cannot implement previously implemented interface "
                    + iface.getName());
        }
        if (iface.isDeclaredSubclassOf("serializable")) {
            throw linkError(context, "Enum " + name + " cannot implement the Serializable interface");
        }
        if (iface.isDeclaredSubclassOf("throwable")) {
            throw linkError(context, "Enum " + name + " cannot implement interface Throwable");
        }
    }

    /** zend_verify_enum: no properties (a trait's either), no magic methods but __call, __callStatic, __invoke. */
    private void checkEnum(PhpContext context, PhpClass phpClass) {
        for (PhpClass.PropertyDecl property : phpClass.getProperties()) {
            if (!property.name().equals("name") && !property.name().equals("value")) {
                throw linkError(context, "Enum " + name + " cannot include properties");
            }
        }
        if (phpClass.hasStaticProperties()) {
            throw linkError(context, "Enum " + name + " cannot include properties");
        }
        for (String magic : new String[]{"__construct", "__destruct", "__clone", "__get", "__set", "__unset",
                "__isset", "__toString", "__debugInfo", "__serialize", "__unserialize", "__sleep", "__wakeup",
                "__set_state"}) {
            if (phpClass.findMethod(Str.lower(magic)) != null) {
                throw linkError(context, "Enum " + name + " cannot include magic method " + magic);
            }
        }
    }

    private void add(PhpClass phpClass, Member member) {
        if (member instanceof Method method) {
            for (PhpClass.PropertyDecl property : method.promoted()) {
                phpClass.addProperty(property);
            }
            phpClass.addMethod(method.function());
        } else if (member instanceof Constant constant) {
            phpClass.addConstant(constant.name(), bind(constant.value(), phpClass));
        } else if (member instanceof Property property) {
            Object initial = bind(property.initial(), phpClass);
            if (property.isStatic()) {
                phpClass.addStaticProperty(property.name(), initial);
            } else {
                PhpClass.PropertyDecl inherited = phpClass.findProperty(property.name());
                boolean readonly = (property.modifiers() & PhpClass.PropertyDecl.READONLY) != 0;
                if (inherited != null && inherited.isReadonly() != readonly) {
                    // zend_do_inheritance's property check
                    throw linkError(PhpContext.get(null), "Cannot redeclare " + (readonly ? "non-readonly" : "readonly")
                            + " property " + phpClass.declaringClassName(inherited) + "::$" + property.name() + " as "
                            + (readonly ? "readonly " : "non-readonly ") + phpClass.getName() + "::$"
                            + property.name());
                }
                phpClass.addProperty(new PhpClass.PropertyDecl(property.name(), initial, property.visibility(), null,
                        property.modifiers(), property.type()));
            }
        } else if (member instanceof Case enumCase) {
            phpClass.addCase(enumCase.name(), bind(enumCase.value(), phpClass));
        }
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
                    if (member instanceof Constant constant && phpClass.caseNames().contains(constant.name())) {
                        throw linkError(context, "Cannot use trait " + trait.getName() + ", because "
                                + trait.getName() + "::" + constant.name() + " conflicts with enum case " + name
                                + "::" + constant.name());
                    }
                    if (member instanceof Property property && (flags & READONLY) != 0
                            && (property.modifiers() & PhpClass.PropertyDecl.READONLY) == 0) {
                        throw linkError(context, "Readonly class " + name + " cannot use trait with a non-readonly"
                                + " property " + trait.getName() + "::$" + property.name());
                    }
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
