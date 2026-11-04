package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

/**
 * `[$a, 'k' => $b, [$c]] = $value`: destructuring into places. Positional entries take keys 0, 1, 2… counting
 * skipped entries; targets are places or nested lists (in `nested`, at the same index).
 */
public final class ListAssignNode {
    /** FETCH_LIST_R's warning for a scalar destructured. */
    @TruffleBoundary
    public static void notAnArray(Node site, Object value) {
        ErrorFunctions.raise(PhpContext.get(site), ErrorFunctions.E_WARNING,
                Str.of("Cannot use " + Convert.debugType(value) + " as array"), SourceLocation.of(site));
    }
}
