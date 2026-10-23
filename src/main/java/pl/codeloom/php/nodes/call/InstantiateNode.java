package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpObject;

/**
 * Creates an instance with its declared properties at their defaults. A `new` site that sees one class (nearly
 * all of them) initializes the properties through one cached object library per property, so compiled code
 * stores into the final shape's fields directly; PhpClass.instantiate walks the uncached shape transitions, which
 * cost ~10x more per object. Other classes take that generic path.
 */
public abstract class InstantiateNode extends Node {
    public abstract PhpObject execute(PhpClass phpClass);

    public static InstantiateNode create() {
        return InstantiateNodeGen.create();
    }

    @Specialization(guards = {"phpClass == cachedClass", "cachedClass.isInstantiable()"}, limit = "1")
    @ExplodeLoop
    protected PhpObject cached(PhpClass phpClass,
                               @Cached("phpClass") PhpClass cachedClass,
                               @Cached(value = "cachedClass.declaredNames()", dimensions = 1) String[] names,
                               @Cached(value = "cachedClass.declaredDefaults()", dimensions = 1) Object[] defaults,
                               @Cached("createPuts(names.length)") DynamicObjectLibrary[] puts) {
        PhpObject object = new PhpObject(cachedClass.getRootShape(), cachedClass, PhpContext.get(this).nextObjectId());
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

    @Specialization(replaces = "cached")
    protected PhpObject generic(PhpClass phpClass) {
        return phpClass.instantiate();
    }

    protected static DynamicObjectLibrary[] createPuts(int count) {
        DynamicObjectLibrary[] libraries = new DynamicObjectLibrary[count];
        for (int i = 0; i < count; i++) {
            libraries[i] = DynamicObjectLibrary.getFactory().createDispatched(1);
        }
        return libraries;
    }
}
