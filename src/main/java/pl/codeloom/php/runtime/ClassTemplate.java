package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;

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

    /** A property: its default value, or a {@link Deferred} initializer. */
    public record Property(String name, Object initial, boolean isPublic, boolean isStatic) implements Member {
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

    public String getName() {
        return name;
    }

    public List<Member> getMembers() {
        return members;
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
        Set<String> own = new HashSet<>();
        for (Member member : members) {
            if (member instanceof Method method) {
                own.add(method.function().getName().toLowerCase());
            }
            add(phpClass, member);
        }
        for (Member member : members) {
            if (member instanceof TraitUse use) {
                useTraits(context, phpClass, use, own);
            }
        }
        return phpClass;
    }

    private static void add(PhpClass phpClass, Member member) {
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
                phpClass.addProperty(new PhpClass.PropertyDecl(property.name(), initial, property.isPublic()));
            }
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
                    String lower = function.getName().toLowerCase();
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
