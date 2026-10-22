package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpRef;

/** func_get_args() and func_num_args(): the arguments as passed, read from the frame. */
public final class FrameArgsNode extends ExprNode {
    private final boolean countOnly;

    public FrameArgsNode(boolean countOnly) {
        this.countOnly = countOnly;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] arguments = frame.getArguments();
        int count = arguments.length - PhpFunction.ARG_FIRST;
        if (countOnly) {
            return (long) count;
        }
        PhpArray list = PhpArray.ofCapacity(count);
        for (int i = 0; i < count; i++) {
            list.append(PhpRef.deref(arguments[PhpFunction.ARG_FIRST + i]));
        }
        return list;
    }
}
