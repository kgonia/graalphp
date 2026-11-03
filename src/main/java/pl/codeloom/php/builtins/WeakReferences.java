package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The WeakReference class (Zend/zend_weakrefs.c): create() gives the one WeakReference of an object while that
 * WeakReference lives, get() the object or null once it is gone. php-src frees an object when its last reference
 * goes; here an object is gone once the JVM's collector has found it unreachable, so get() turns null at the
 * collection after the last reference was dropped rather than at the drop itself.
 */
final class WeakReferences {
    private WeakReferences() {
    }

    /** A context's WeakReference objects by their target; neither keeps the other alive. */
    static final class State {
        final Map<Object, WeakReference<PhpObject>> references = new WeakHashMap<>();
    }

    static List<PhpFunction> methods(Registry registry) {
        return List.of(
                registry.method("WeakReference", "create", true, a -> create(Args.get(a, 0))),
                registry.method("WeakReference", "get", false, a -> {
                    PhpObject self = (PhpObject) a[PhpFunction.ARG_THIS];
                    Object target = self.getInternal() instanceof WeakReference<?> reference ? reference.get() : null;
                    return target == null ? PhpNull.NULL : target;
                }));
    }

    static void declare(PhpContext context, List<PhpFunction> methods) {
        PhpClass phpClass = new PhpClass("WeakReference", null, false, false, context.getLanguage().rootShape(),
                "WeakReference");
        phpClass.setHandlers(new PhpClass.ObjectHandlers("Direct instantiation of WeakReference is not allowed, use "
                + "WeakReference::create instead", null, true, false, true, false));
        for (PhpFunction method : methods) {
            phpClass.addMethod(method);
        }
        context.declareClass(phpClass);
    }

    @TruffleBoundary
    private static Object create(Object value) {
        if (!(value instanceof PhpObject) && !(value instanceof pl.codeloom.php.runtime.PhpClosure)) {
            throw Args.typeError("WeakReference::create", 1, "object", "object", value);
        }
        Object object = value;
        PhpContext context = PhpContext.get(null);
        Map<Object, WeakReference<PhpObject>> references = context.builtinState().weakReferences.references;
        WeakReference<PhpObject> existing = references.get(object);
        PhpObject reference = existing == null ? null : existing.get();
        if (reference == null) {
            reference = context.getClass("WeakReference").instantiateInternal();
            reference.setInternal(new WeakReference<>(object));
            references.put(object, new WeakReference<>(reference));
        }
        return reference;
    }
}
