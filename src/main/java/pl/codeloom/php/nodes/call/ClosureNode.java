package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;

/**
 * `function (...) use ($a, &$b) {...}` and `fn (...) => ...`: captures the use-variables (values, or references
 * for &), $this unless static, and the class scope. A by-value captured array counts the closure as a holder
 * for the closure's lifetime.
 */
public final class ClosureNode extends ExprNode {
    private final PhpFunction function;
    @Children private final ExprNode[] captures;
    @CompilationFinal(dimensions = 1) private final boolean[] byRef;
    private final boolean isStatic;
    @Child private ClassRefNode scope;

    public ClosureNode(PhpFunction function, ExprNode[] captures, boolean[] byRef, boolean isStatic,
                       ClassRefNode scope) {
        this.function = function;
        this.captures = captures;
        this.byRef = byRef;
        this.isStatic = isStatic;
        this.scope = scope;
    }

    public PhpFunction getFunction() {
        return function;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object[] captured = new Object[captures.length];
        for (int i = 0; i < captures.length; i++) {
            if (byRef[i]) {
                PhpRef ref = ((LValueNode) captures[i]).makeRef(frame);
                ref.bind();
                captured[i] = ref;
            } else {
                Object value = captures[i].execute(frame);
                if (value instanceof PhpArray array) {
                    array.addHolder();
                }
                captured[i] = value;
            }
        }
        Object self = isStatic ? null : frame.getArguments()[PhpFunction.ARG_THIS];
        PhpClass scopeClass = scope == null ? null : scope.resolve(frame);
        return new PhpClosure(function, self instanceof PhpObject object ? object : null, scopeClass, captured);
    }
}
