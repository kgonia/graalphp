package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.ClassKey;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpObject;

/**
 * Creates an instance with its declared properties at their defaults. A `new` site that sees one class (nearly
 * all of them) initializes the properties through one cached object library per property, so compiled code
 * stores into the final shape's fields directly; PhpClass.instantiate walks the uncached shape transitions, which
 * cost ~10x more per object. While the language serves one context the class itself is cached and its defaults
 * are compile-time constants; with several, the cache keys on the class's language-level identity and reads the
 * calling context's defaults. Other classes take the generic path.
 */
@ReportPolymorphism
public abstract class InstantiateNode extends Node {
    public abstract PhpObject execute(PhpClass phpClass);

    public static InstantiateNode create() {
        return InstantiateNodeGen.create();
    }

    @Specialization(guards = {"phpClass == cachedClass", "cachedClass.isInstantiable()"}, limit = "1",
            assumptions = "singleContext()")
    @ExplodeLoop
    protected PhpObject sameClass(PhpClass phpClass,
                                  @Cached("phpClass") PhpClass cachedClass,
                                  @Cached(value = "cachedClass.declaredNames()", dimensions = 1) String[] names,
                                  @Cached(value = "cachedClass.defaults()", dimensions = 1) Object[] defaults,
                                  @Cached("createPuts(names.length)") DynamicObjectLibrary[] puts) {
        return initialize(cachedClass, names, defaults, puts);
    }

    @Specialization(guards = {"phpClass.getKey() == cachedKey", "phpClass.isInstantiable()"}, limit = "1")
    protected PhpObject sameKey(PhpClass phpClass,
                                @Cached("phpClass.getKey()") ClassKey cachedKey,
                                @Cached(value = "phpClass.declaredNames()", dimensions = 1) String[] names,
                                @Cached("createPuts(names.length)") DynamicObjectLibrary[] puts) {
        return initialize(phpClass, names, phpClass.defaults(), puts);
    }

    @Specialization(replaces = {"sameClass", "sameKey"})
    protected PhpObject generic(PhpClass phpClass) {
        return phpClass.instantiate();
    }

    @ExplodeLoop
    private PhpObject initialize(PhpClass phpClass, String[] names, Object[] defaults, DynamicObjectLibrary[] puts) {
        PhpObject object = new PhpObject(phpClass.getRootShape(), phpClass, PhpContext.get(this).nextObjectId());
        for (int i = 0; i < puts.length; i++) {
            Object value = defaults[i];
            if (value instanceof Long l) {
                puts[i].putLong(object, names[i], l);
            } else {
                if (value instanceof PhpArray array) {
                    array.addHolder();
                }
                puts[i].put(object, names[i], value);
            }
        }
        return object;
    }

    protected Assumption singleContext() {
        return PhpLanguage.get(this).singleContext();
    }

    protected static DynamicObjectLibrary[] createPuts(int count) {
        DynamicObjectLibrary[] libraries = new DynamicObjectLibrary[count];
        for (int i = 0; i < count; i++) {
            libraries[i] = DynamicObjectLibrary.getFactory().createDispatched(1);
        }
        return libraries;
    }
}
