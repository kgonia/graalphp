package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;

/**
 * `defined('NAME')` as php-src compiles it (zend_compile_func_defined into ZEND_DEFINED), the constant operand of
 * its instruction: a literal name with no namespace and no class, in a call that names the global function. The
 * name's id is resolved by the translator, and the test is the context's slot, an exact match as
 * zend_quick_check_constant's: true, false and null are folded by the translator. A constant is never undefined,
 * so, as ZEND_DEFINED's cache slot keeps the constant found, the site is true from then on while the language
 * serves one context.
 */
public final class DefinedSite {
    /** The name's {@link PhpLanguage#constantId}. */
    private final int id;
    /** The constant was found while the language served one context. */
    @CompilationFinal private boolean found;

    public DefinedSite(int id) {
        this.id = id;
    }

    boolean test() {
        Assumption single = PhpLanguage.get(null).singleContext();
        if (found && single.isValid()) {
            return true;
        }
        if (PhpContext.get(null).constantAt(id) == null) {
            return false;
        }
        if (!found && single.isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            found = true;
        }
        return true;
    }

    @Override
    public String toString() {
        return "defined#" + id;
    }
}
