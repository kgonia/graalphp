package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.Str;

/**
 * The first-class callable syntax, `f(...)`, `$o->m(...)`, `A::m(...)`, `$f(...)`: a Closure of what the call
 * would call, with the call's own lookup and errors (zend_closure_from_frame). A method missing where the call
 * would take __call or __callStatic gives a closure that calls the magic method with the name.
 */
public final class CallableConvertNodes {
    private static final Object[] NO_CAPTURES = new Object[0];

    private CallableConvertNodes() {
    }

    private static PhpClosure closure(PhpFunction function, PhpObject self, PhpClass scope, TruffleString magicName) {
        if (function.isAbstract()) {
            throw new PhpError("Cannot call abstract method " + function.displayName() + "()");
        }
        return new PhpClosure(function, self, scope, NO_CAPTURES, magicName);
    }

    /** `f(...)`: the function the name resolves to, the namespaced one first, as a call finds it. */
    public static final class FunctionNode {
        /** The closure of the function displayName names (its name id, the global one's or -1, its site's number). */
        @TruffleBoundary
        public static Object convert(PhpContext context, String displayName, int id, int fallbackId, int site) {
            return closure(FunctionCallNode.find(context, displayName, id, fallbackId, site), null, null, null);
        }
    }

    /** `$o->m(...)` and `$o->$name(...)`: the object's method, bound to it. */
    public static final class MethodNode {
        /**
         * The closure of target's method name as `$target->name()` finds it from scope, bound to it; php-src's
         * Errors for what has none.
         */
        @TruffleBoundary
        public static Object convert(Object target, String name, String scope) {
            if (target instanceof PhpClosure closure) {
                if (Str.lower(name).equals("__invoke")) {
                    return closure;
                }
                throw new PhpError("Call to undefined method Closure::" + name + "()");
            }
            if (!(target instanceof PhpObject self)) {
                throw MethodCallNode.memberOnNonObject(name, target);
            }
            PhpClass phpClass = self.getPhpClass();
            PhpFunction method = phpClass.methodFrom(name, scope);
            if (method != null) {
                return closure(method, method.isStatic() ? null : self, phpClass, null);
            }
            return closure(phpClass.findMethod("__call"), self, phpClass, Str.of(name));
        }
    }

    /**
     * `A::m(...)`, `self::m(...)`, `parent::m(...)`, `static::m(...)`: as StaticCallNode, a non-static method takes
     * the caller's $this when it is an instance of the class, and forwarding calls keep the called class.
     */
    public static final class StaticMethodNode {
        /**
         * The closure of phpClass's method name, as `Cls::name()` finds it from scope, for a caller whose $this is
         * caller, called as staticClass.
         */
        @TruffleBoundary
        public static Object convert(PhpClass phpClass, Object caller, PhpClass staticClass, String name,
                                     String scope) {
            PhpObject self = caller instanceof PhpObject object
                    && object.getPhpClass().isSubclassOf(phpClass.getLowerName()) ? object : null;
            PhpFunction method = phpClass.staticMethodFrom(name, scope);
            if (method != null) {
                if (method.isStatic()) {
                    return closure(method, null, staticClass, null);
                }
                if (self == null) {
                    throw new PhpError("Non-static method " + phpClass.getName() + "::" + method.getName()
                            + "() cannot be called statically");
                }
                return closure(method, self, self.getPhpClass(), null);
            }
            PhpFunction magicCall = phpClass.findMethod("__call");
            if (self != null && magicCall != null) {
                return closure(magicCall, self, self.getPhpClass(), Str.of(name));
            }
            PhpFunction magicStatic = phpClass.findMethod("__callstatic");
            if (magicStatic != null) {
                return closure(magicStatic, null, staticClass, Str.of(name));
            }
            throw phpClass.callError(name, scope);
        }
    }

    /** `$f(...)`, `[$o, 'm'](...)`, `'A::m'(...)`: what the direct call `$f()` would call; a Closure stays itself. */
    public static final class ValueNode {
        /** What the call of value from scope would call, as a Closure. */
        @TruffleBoundary
        public static Object convert(PhpContext context, Object value, String scope) {
            if (value instanceof PhpClosure closure) {
                return closure;
            }
            Callables.Target target = Callables.resolveCall(context, value, scope);
            PhpObject self = target.thisObject() instanceof PhpObject object ? object : null;
            return closure(target.function(), self, target.staticClass(), target.magicName());
        }
    }
}
