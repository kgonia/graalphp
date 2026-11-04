package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpFunction;

/**
 * `new Cls(args)`: instantiate, then call the constructor (inline-cached on the constructor), if there is one.
 */
public final class NewNode {
    /** zend_std_get_constructor: a constructor that isn't public, which the scope may not call, is an Error. */
    @TruffleBoundary
    public static void checkConstructor(PhpClass phpClass, PhpFunction constructor, String scope) {
        if (!phpClass.canCall(constructor, "__construct", scope)) {
            throw PhpClass.constructorError(constructor, scope);
        }
    }
}
