package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
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
public final class InstantiateNode extends Node {
    @CompilationFinal private PhpClass cachedClass;
    @CompilationFinal(dimensions = 1) private String[] names;
    @CompilationFinal(dimensions = 1) private Object[] defaults;
    @Children private DynamicObjectLibrary[] puts;
    @CompilationFinal private boolean generic;

    public PhpObject execute(PhpClass phpClass) {
        if (!generic) {
            if (cachedClass == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                if (!phpClass.isInstantiable()) {
                    generic = true;
                    return phpClass.instantiate();
                }
                defaults = phpClass.declaredDefaults();
                names = phpClass.declaredNames();
                DynamicObjectLibrary[] libraries = new DynamicObjectLibrary[names.length];
                for (int i = 0; i < libraries.length; i++) {
                    libraries[i] = DynamicObjectLibrary.getFactory().createDispatched(1);
                }
                puts = insert(libraries);
                cachedClass = phpClass;
            }
            if (phpClass == cachedClass) {
                return initialize(phpClass);
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            generic = true;
        }
        return phpClass.instantiate();
    }

    @ExplodeLoop
    private PhpObject initialize(PhpClass phpClass) {
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
}
