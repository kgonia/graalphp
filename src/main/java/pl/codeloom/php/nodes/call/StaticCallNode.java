package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.Str;


/**
 * `Cls::m()`, `self::m()`, `parent::m()`, `static::m()`. Forwarding calls (self, parent, static) keep the
 * caller's late-static-binding class; a non-static method called this way receives the caller's $this when it
 * is an instance of the class, as PHP does for `parent::__construct()`.
 */
public final class StaticCallNode {
    /**
     * What `Cls::$name(args)` calls (INIT_STATIC_METHOD_CALL of a name computed at run time): the method as
     * zend_std_get_static_method finds it from scope, a non-static one on the caller's $this (an instance of the
     * class), else __call on that $this or __callStatic standing in; staticClass is the call's called class.
     */
    @TruffleBoundary
    public static Callables.Target target(PhpClass phpClass, Object name, Object callerThis, PhpClass staticClass,
                                          String scope) {
        if (!(name instanceof TruffleString method)) {
            throw MethodCallNode.nameNotString();
        }
        String text = Str.java(method);
        PhpObject self = callerThis instanceof PhpObject object
                && object.getPhpClass().isSubclassOf(phpClass.getLowerName()) ? object : null;
        PhpFunction function = phpClass.staticMethodFrom(text, scope);
        if (function == null) {
            PhpFunction viaCall = self != null ? phpClass.findMethod("__call") : null;
            if (viaCall != null) {
                return new Callables.Target(viaCall, self, self.getPhpClass(), null, method);
            }
            PhpFunction viaStatic = phpClass.findMethod("__callstatic");
            if (viaStatic == null) {
                throw phpClass.callError(text, scope);
            }
            return new Callables.Target(viaStatic, null, staticClass, null, method);
        }
        if (function.isStatic()) {
            return new Callables.Target(function, null, staticClass, null);
        }
        if (self == null) {
            throw PhpClass.nonStaticCall(function);
        }
        return new Callables.Target(function, self, self.getPhpClass(), null);
    }

    /** `Cls::__construct()`: the class's constructor, or php-src's Error for a class without one. */
    @TruffleBoundary
    public static PhpFunction constructorOf(PhpClass phpClass) {
        PhpFunction constructor = phpClass.getConstructor();
        if (constructor == null) {
            throw new PhpError("Cannot call constructor");
        }
        return constructor;
    }

    /** A private constructor called through `Cls::__construct()` on an object of another class. */
    @TruffleBoundary
    public static void checkPrivateConstructor(PhpClass phpClass, PhpFunction constructor, Object self) {
        if ((constructor.getModifiers() & PhpFunction.ACC_PRIVATE) != 0 && self instanceof PhpObject object
                && !object.getPhpClass().getName().equalsIgnoreCase(constructor.getClassName())) {
            throw new PhpError("Cannot call private " + phpClass.getName() + "::__construct()");
        }
    }
}
