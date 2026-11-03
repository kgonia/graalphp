package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.runtime.ClassKey;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.Str;

/**
 * The method a class has under a name (declared, inherited or imported from a trait), or null: inline-cached on
 * the class's language-level key, so the same entry serves every context, and a class's entry gives the method as a
 * compile-time constant. A call then dispatches on the method found ({@link MethodDispatchNode}), so the classes a
 * site sees that resolve to one method share one call. Classes that resolve to different methods are what makes a
 * call polymorphic, and the dispatch reports that, not this lookup: a site seeing many classes of one method (a
 * base class's `$this->get_prop()` called on each of its subclasses) stays monomorphic and is not split.
 */
public abstract class MethodLookupNode extends Node {
    /**
     * The classes a site keeps the lookup of. Beyond them a call looks the method up in the class's table, as
     * php-src does whenever a site's class changes (its runtime cache keeps one class), and still calls the method
     * found directly while it is the same.
     */
    static final int CLASSES = 8;

    /** The name as written; isStatic: a static call's lookup (zend_std_get_static_method). */
    protected final String name;
    protected final String lowerName;
    private final boolean isStatic;

    protected MethodLookupNode(String name, boolean isStatic) {
        this.name = name;
        this.lowerName = Str.lower(name);
        this.isStatic = isStatic;
    }

    public static MethodLookupNode create(String name, boolean isStatic) {
        return MethodLookupNodeGen.create(name, isStatic);
    }

    /** The method from the calling scope (lowercase, or null), null when __call or __callStatic stands in. */
    public abstract PhpFunction execute(PhpClass phpClass, String scope);

    @Specialization(guards = {"phpClass.getKey() == cachedKey", "!scoped"}, limit = "CLASSES")
    protected PhpFunction cached(PhpClass phpClass, String scope,
                                 @Cached("phpClass.getKey()") ClassKey cachedKey,
                                 @Cached("phpClass.methodDependsOnScope(lowerName)") boolean scoped,
                                 @Cached("lookUp(phpClass, scope)") PhpFunction method) {
        return method;
    }

    @Specialization(replaces = "cached")
    protected PhpFunction lookUp(PhpClass phpClass, String scope) {
        return isStatic ? phpClass.staticMethodFrom(name, scope) : phpClass.methodFrom(name, scope);
    }
}
