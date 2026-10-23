package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ExprNode;

/**
 * `$GLOBALS` read as a whole: a copy of the global variables (as PHP 8.1+, where it is read-only).
 * `$GLOBALS['name']` is {@link GlobalsDimNode}.
 */
public final class GlobalsArrayNode extends ExprNode {
    @Override
    public Object execute(VirtualFrame frame) {
        return PhpContext.get(this).globalsArray();
    }
}
