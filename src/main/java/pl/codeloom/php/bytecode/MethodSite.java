package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.Str;

/**
 * A method call, `$obj->name(args)` or `Cls::name(args)`: the constant operand of its resolving and calling
 * instructions. forwarding: a static call through self::, parent:: or static::, which keeps the caller's
 * late-static-binding class.
 */
public final class MethodSite {
    /**
     * What resolving gives when the arguments go by value whatever the callee: __call or __callStatic stands in for
     * the method, or the receiver is a Closure. The call finds the callee again.
     */
    static final PhpFunction BY_VALUE = new PhpFunction("{by value}", new PhpFunction.Param[0], false, false, true,
            false, () -> null);

    final String lowerName;
    /** The name as written, which __call and __callStatic are given. */
    final TruffleString calledName;
    final boolean forwarding;

    public MethodSite(String name, boolean forwarding) {
        this.lowerName = Str.lower(name);
        this.calledName = Str.of(name);
        this.forwarding = forwarding;
    }

    static boolean byValue(PhpFunction function) {
        return function == BY_VALUE;
    }

    @Override
    public String toString() {
        return lowerName + "()";
    }
}
