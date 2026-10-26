package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.ClassKey;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.SourceLocation;

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
                                  @Cached(value = "cachedClass.declaredFlags()", dimensions = 1) int[] flags,
                                  @Cached("createPuts(names.length)") DynamicObjectLibrary[] puts,
                                  @Cached("cachedClass.isThrowable()") boolean throwable) {
        return initialize(cachedClass, names, defaults, flags, puts, throwable);
    }

    @Specialization(guards = {"phpClass.getKey() == cachedKey", "phpClass.isInstantiable()"}, limit = "1")
    protected PhpObject sameKey(PhpClass phpClass,
                                @Cached("phpClass.getKey()") ClassKey cachedKey,
                                @Cached(value = "phpClass.declaredNames()", dimensions = 1) String[] names,
                                @Cached(value = "phpClass.declaredFlags()", dimensions = 1) int[] flags,
                                @Cached("createPuts(names.length)") DynamicObjectLibrary[] puts,
                                @Cached("phpClass.isThrowable()") boolean throwable) {
        return initialize(phpClass, names, phpClass.defaults(), flags, puts, throwable);
    }

    @Specialization(replaces = {"sameClass", "sameKey"})
    @TruffleBoundary
    protected PhpObject generic(PhpClass phpClass) {
        // a stack walk only for a Throwable: arguments are evaluated before instantiate() looks at the class
        return phpClass.isThrowable() ? phpClass.instantiate(location(), trace()) : phpClass.instantiate();
    }

    /** Where a Throwable made here was made: this `new`'s statement. */
    @TruffleBoundary
    private SourceLocation location() {
        return SourceLocation.of(this);
    }

    /** A Throwable's trace: the calls leading to this `new`. */
    @TruffleBoundary
    private static PhpArray trace() {
        return Backtrace.forThrowable();
    }

    @ExplodeLoop
    private PhpObject initialize(PhpClass phpClass, String[] names, Object[] defaults, int[] flags,
                                 DynamicObjectLibrary[] puts, boolean throwable) {
        PhpObject object = new PhpObject(phpClass.getRootShape(), phpClass, PhpContext.get(this).nextObjectId());
        for (int i = 0; i < puts.length; i++) {
            Object value = defaults[i];
            if (value instanceof PhpArray array) {
                array.addHolder();
            }
            if (flags[i] != 0) {
                puts[i].putWithFlags(object, names[i], value, flags[i]);
            } else if (value instanceof Long l) {
                puts[i].putLong(object, names[i], l);
            } else {
                puts[i].put(object, names[i], value);
            }
        }
        if (throwable) {
            PhpClass.initThrowable(object, location(), trace());
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
