package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Str;

/**
 * `$GLOBALS[$name]`: the global variable named by the key, in every context php-src compiles it in (ZEND_FETCH_*
 * with ZEND_FETCH_GLOBAL): read, written, read and written (`op=`, `??=`, `++`), as a container, by reference,
 * isset() and unset(). `$GLOBALS` itself is a read-only copy ({@link GlobalsArrayNode}).
 */
public final class GlobalsDimNode {
    /** The global variable a key names (the bytecode's $GLOBALS[key] instructions convert it so too). */
    @TruffleBoundary
    public static String toName(Object value) {
        return Str.java(Convert.toStr(value));
    }

}
