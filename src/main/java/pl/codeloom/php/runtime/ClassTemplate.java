package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;

import java.util.List;

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

    private final String name;
    private final String parentName;
    private final List<String> interfaces;
    private final boolean isInterface;
    private final boolean isAbstract;
    private final List<Member> members;
    /** Why the translator couldn't handle a member, or null: declaring the class then fails with it. */
    private final String unsupported;

    public ClassTemplate(String name, String parentName, List<String> interfaces, boolean isInterface,
                         boolean isAbstract, List<Member> members, String unsupported) {
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
        for (Member member : members) {
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
        return phpClass;
    }

    private static Object bind(Object valueOrDeferred, PhpClass phpClass) {
        return valueOrDeferred instanceof Deferred deferred ? deferred.bind(phpClass) : valueOrDeferred;
    }
}
