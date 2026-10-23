package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.PhpArray;

/**
 * `compact('a', 'b')` with names known at translation time: an array of the variables that exist (a null value
 * counts, an undefined variable is left out), keyed by name.
 */
public final class CompactNode extends ExprNode {
    @CompilationFinal(dimensions = 1) private final TruffleString[] names;
    @Children private final LValueNode[] variables;

    public CompactNode(TruffleString[] names, LValueNode[] variables) {
        this.names = names;
        this.variables = variables;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        PhpArray result = new PhpArray();
        for (int i = 0; i < variables.length; i++) {
            if (variables[i].isDefined(frame)) {
                result.put(names[i], variables[i].execute(frame));
            }
        }
        return result;
    }
}
