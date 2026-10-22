package pl.codeloom.php.runtime;

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

    public PhpClosure(PhpFunction function, PhpObject boundThis, PhpClass scope, Object[] captured) {
        this.function = function;
        this.boundThis = boundThis;
        this.scope = scope;
        this.captured = captured;
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

    public Object getCaptured(int index) {
        return captured[index];
    }
}
