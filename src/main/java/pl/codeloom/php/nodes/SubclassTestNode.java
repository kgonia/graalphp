package pl.codeloom.php.nodes;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.runtime.ClassKey;
import pl.codeloom.php.runtime.PhpClass;

/**
 * Whether a class is a class, extends it or implements it ({@link PhpClass#isSubclassOf}), inline-cached on the
 * classes' language-level keys: a key fixes the class's name, parent and interfaces, so the answer for one class
 * holds for every class with its key, in every context. The target is a lowercase class name (instanceof, the
 * same string at every call of a site) or a class (self::, parent::).
 */
@ReportPolymorphism
public abstract class SubclassTestNode extends Node {
    static final int LIMIT = 4;

    public abstract boolean execute(PhpClass phpClass, Object target);

    public static SubclassTestNode create() {
        return SubclassTestNodeGen.create();
    }

    @Specialization(guards = {"phpClass.getKey() == cachedKey", "lowerName == cachedName"}, limit = "LIMIT")
    protected static boolean name(PhpClass phpClass, String lowerName,
                                  @Cached("phpClass.getKey()") ClassKey cachedKey,
                                  @Cached("lowerName") String cachedName,
                                  @Cached("phpClass.isSubclassOf(lowerName)") boolean result) {
        return result;
    }

    @Specialization(guards = {"phpClass.getKey() == cachedKey", "target.getKey() == cachedTargetKey"},
            limit = "LIMIT")
    protected static boolean phpClass(PhpClass phpClass, PhpClass target,
                                      @Cached("phpClass.getKey()") ClassKey cachedKey,
                                      @Cached("target.getKey()") ClassKey cachedTargetKey,
                                      @Cached("phpClass.isSubclassOf(target.getLowerName())") boolean result) {
        return result;
    }

    @Specialization(replaces = "name")
    protected static boolean genericName(PhpClass phpClass, String lowerName) {
        return phpClass.isSubclassOf(lowerName);
    }

    @Specialization(replaces = "phpClass")
    protected static boolean genericClass(PhpClass phpClass, PhpClass target) {
        return phpClass.isSubclassOf(target.getLowerName());
    }
}
