package pl.codeloom.php.nodes.intrinsics;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;


/**
 * Hot built-ins as Truffle DSL nodes (D10 step 2), compiled in place of the call: arguments are child nodes,
 * specializations cover the common argument types with cached TruffleString nodes (the vectorized searches
 * among them), and nothing on those paths is a boundary, so partial evaluation sees through the whole call.
 * Every other case delegates to the function's regular built-in (BuiltinNode.callFull), which keeps the full
 * PHP semantics in one place. The translator fills missing optional arguments with their defaults, so each
 * node has a fixed arity; by-reference array arguments come through a {@link WritableArrayNode}.
 */
public final class BuiltinNodes {
    private BuiltinNodes() {
    }

    /** A built-in function compiled in place. */
    public static final class BuiltinNode {
        /**
         * The regular built-in's Java body for a call php-src compiles in, which makes no frame: what it calls back
         * (Countable::count) is called from the call's site. arguments are frame arguments.
         */
        @TruffleBoundary
        public static Object callInPlace(Node site, String name, Object[] arguments) {
            PhpFunction function = PhpContext.get(site).findFunction(name);
            return PhpRef.deref(Backtrace.callFrom(site, () -> function.getBuiltin().call(arguments)));
        }

        protected static boolean isString(Object value) {
            return value instanceof TruffleString;
        }

        protected static boolean isNull(Object value) {
            return value == PhpNull.NULL;
        }
    }

    @TruffleBoundary
    static PhpError arrayTypeError(String function, Object value) {
        return new PhpError("TypeError", function + "(): Argument #1 ($array) must be of type array, "
                + Args.valueName(value) + " given");
    }

    // ---- strings ----

    // ---- arrays ----

    // ---- the first intrinsics (count, strlen, ord, substr, strpos, str_contains...), on the DSL ----

}
