package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.runtime.ClassKey;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpFunction;

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

    protected final String lowerName;

    protected MethodLookupNode(String lowerName) {
        this.lowerName = lowerName;
    }

    public static MethodLookupNode create(String lowerName) {
        return MethodLookupNodeGen.create(lowerName);
    }

    public abstract PhpFunction execute(PhpClass phpClass);

    @Specialization(guards = "phpClass.getKey() == cachedKey", limit = "CLASSES")
    protected static PhpFunction cached(PhpClass phpClass,
                                        @Cached("phpClass.getKey()") ClassKey cachedKey,
                                        @Cached("phpClass.findMethod(lowerName)") PhpFunction method) {
        return method;
    }

    @Specialization(replaces = "cached")
    protected PhpFunction lookUp(PhpClass phpClass) {
        return phpClass.findMethod(lowerName);
    }
}
