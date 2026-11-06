package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;

import java.util.HashMap;
import java.util.Map;

/**
 * A Closure object's payload: the function, the bound $this and class scope, and the captured `use` variables
 * (values for by-value captures, PhpRefs for by-reference ones), in the order of the function's capture list.
 * The PhpObject of class Closure that PHP code sees wraps one of these.
 */
public final class PhpClosure {
    private final PhpFunction function;
    private final PhpObject boundThis;
    private final PhpClass scope;
    private final Object[] captured;
    /** For a closure of a missing method made with `$o->m(...)`: function is __call or __callStatic, this the name. */
    private final TruffleString magicName;
    /** Made by Closure::fromCallable() of a __call stand-in: no variadic parameter, so no named arguments. */
    private boolean magicFixed;
    /** The class static:: names in the closure without a $this (zend_closure's called_scope). */
    private final PhpClass calledScope;
    /** var_dump's #n and spl_object_id(), numbered with the objects. */
    private final int id;
    /** A closure's own `static` variables (each closure object has its own), by name; null if it declares none. */
    private final Map<String, PhpRef> statics;

    public PhpClosure(PhpFunction function, PhpObject boundThis, PhpClass scope, Object[] captured) {
        this(function, boundThis, scope, captured, null);
    }

    public PhpClosure(PhpFunction function, PhpObject boundThis, PhpClass scope, Object[] captured,
                      TruffleString magicName) {
        this(function, boundThis, scope, boundThis != null ? boundThis.getPhpClass() : scope, captured, magicName);
    }

    public PhpClosure(PhpFunction function, PhpObject boundThis, PhpClass scope, PhpClass calledScope,
                      Object[] captured, TruffleString magicName) {
        this(function, boundThis, scope, calledScope, captured, magicName, PhpContext.get(null).nextObjectId(),
                function.getClosureVariables() != null && function.getClosureVariables().length > captured.length
                        ? new HashMap<>() : null);
    }

    private PhpClosure(PhpFunction function, PhpObject boundThis, PhpClass scope, PhpClass calledScope,
                       Object[] captured, TruffleString magicName, int id, Map<String, PhpRef> statics) {
        this.function = function;
        this.boundThis = boundThis;
        this.scope = scope;
        this.calledScope = calledScope;
        this.captured = captured;
        this.magicName = magicName;
        this.id = id;
        this.statics = statics;
    }

    /** Whether the closure keeps static variables of its own: a closure (not a fake one) that declares some. */
    public boolean hasStatics() {
        return statics != null;
    }

    /** The closure's `static $name`, or null before its first initialization. */
    @TruffleBoundary
    public PhpRef findStatic(String name) {
        return statics.get(name);
    }

    @TruffleBoundary
    public PhpRef createStatic(String name, Object initial) {
        PhpRef ref = new PhpRef(initial);
        ref.bind();
        statics.put(name, ref);
        return ref;
    }

    /** A copy of another closure's static variables' values (zend_create_closure_ex's zend_array_dup). */
    @TruffleBoundary
    public void copyStatics(PhpClosure from) {
        if (statics != null && from.statics != null) {
            for (Map.Entry<String, PhpRef> entry : from.statics.entrySet()) {
                Object value = entry.getValue().get();
                if (value instanceof PhpArray array) {
                    array.addHolder();
                }
                createStatic(entry.getKey(), value);
            }
        }
    }

    /**
     * A closure made where it is declared (ZEND_DECLARE_LAMBDA_FUNCTION), from the frame's $this and static class:
     * its called scope is the $this's class, else the frame's static class; a static closure binds no $this.
     */
    public static PhpClosure declared(PhpFunction function, boolean isStatic, Object frameThis, Object frameStatic,
                                      PhpClass scope, Object[] captured) {
        PhpObject self = frameThis instanceof PhpObject object ? object : null;
        PhpClass calledScope = self != null ? self.getPhpClass() : frameStatic instanceof PhpClass c ? c : null;
        return new PhpClosure(function, isStatic ? null : self, scope, calledScope, captured, null);
    }

    /** The same closure bound to another $this and scope for one call (Closure::call): no new object. */
    public PhpClosure rebound(PhpObject newThis, PhpClass newScope) {
        PhpClosure rebound = new PhpClosure(function, newThis, newScope, newThis.getPhpClass(), captured, magicName,
                id, statics);
        rebound.magicFixed = magicFixed;
        return rebound;
    }

    /**
     * Closure::fromCallable() of a method __call stands in for: php-src's closure of it takes no named arguments
     * (its zend_closure_call_magic has no variadic arg_info), unlike `$obj->missing(...)`'s, whose variadic
     * parameter collects them.
     */
    public void fixMagicParameters() {
        magicFixed = true;
    }

    /** Whether the closure's __call stand-in has a variadic parameter (see fixMagicParameters). */
    public boolean isMagicVariadic() {
        return !magicFixed;
    }

    /** The class static:: names in a call of the closure: its $this's, else its called scope. */
    public PhpClass staticClass() {
        return boundThis != null ? boundThis.getPhpClass() : calledScope;
    }

    public PhpClass getCalledScope() {
        return calledScope;
    }

    public int id() {
        return id;
    }

    public TruffleString getMagicName() {
        return magicName;
    }

    public PhpFunction getFunction() {
        return function;
    }

    public PhpObject getBoundThis() {
        return boundThis;
    }

    public PhpClass getScope() {
        return scope;
    }

    public int getCaptureCount() {
        return captured.length;
    }

    public Object getCaptured(int index) {
        return captured[index];
    }
}
