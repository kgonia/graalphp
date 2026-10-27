package pl.codeloom.php.runtime;

import com.oracle.truffle.api.strings.TruffleString;

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

    public PhpClosure(PhpFunction function, PhpObject boundThis, PhpClass scope, Object[] captured) {
        this(function, boundThis, scope, captured, null);
    }

    public PhpClosure(PhpFunction function, PhpObject boundThis, PhpClass scope, Object[] captured,
                      TruffleString magicName) {
        this.function = function;
        this.boundThis = boundThis;
        this.scope = scope;
        this.captured = captured;
        this.magicName = magicName;
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
