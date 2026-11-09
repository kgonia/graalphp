package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.PhpClass;
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
    /**
     * The parameters of Closure::fromCallable()'s closure of a __call stand-in: none and no variadic one, so its
     * arguments go by value and any name is unknown (zend_closure_call_magic's).
     */
    static final PhpFunction NO_PARAMETERS = new PhpFunction("{no parameters}", new PhpFunction.Param[0], false,
            false, true, false, () -> null);

    /** The name as written, for messages; calledName, as __call and __callStatic are given it. */
    final String name;
    final String lowerName;
    final TruffleString calledName;
    final boolean forwarding;
    /** `Cls::__construct()`, which php-src calls without a lookup. */
    final boolean constructor;
    /**
     * How the call counts its receiver while it runs (php-src's ZEND_CALL_RELEASE_THIS): not at all for `$this`
     * (INIT_METHOD_CALL's op1 UNUSED) or a static call; for a variable, the pair is skipped but its possible-root
     * event raised (wiki/destructors.md, "Why skipping a pair is exact"); any other receiver is counted.
     */
    final int receiver;
    public static final int UNCOUNTED = 0;
    public static final int VARIABLE = 1;
    public static final int COUNTED = 2;

    public MethodSite(String name, boolean forwarding) {
        this(name, forwarding, UNCOUNTED);
    }

    public MethodSite(String name, boolean forwarding, int receiver) {
        this.name = name;
        this.lowerName = Str.lower(name);
        this.calledName = Str.of(name);
        this.forwarding = forwarding;
        this.constructor = lowerName.equals("__construct");
        this.receiver = receiver;
    }

    /** Whether the lookup's answer from scope follows from the class's key (PhpClass.isMethodAccessKeyed). */
    public boolean isKeyed(PhpClass phpClass, String scope) {
        return phpClass.isMethodAccessKeyed(lowerName, scope);
    }

    /** Whether the lookup in a closure or a file, whose scope may change, depends on the scope. */
    public boolean isScoped(PhpBytecodeRootNode root, PhpClass phpClass) {
        return root.hasVaryingScope() && phpClass.methodDependsOnScope(lowerName);
    }

    static boolean byValue(PhpFunction function) {
        return function == BY_VALUE;
    }

    @Override
    public String toString() {
        return lowerName + "()";
    }
}
