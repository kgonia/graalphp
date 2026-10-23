package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.ToStrNode;
import pl.codeloom.php.runtime.PhpArray;

/** `$object->$name(args)`: dispatched like the callable [$object, $name], cached per class and method name. */
public final class DynamicMethodCallNode extends ExprNode {
    @Child private ExprNode object;
    @Child private ExprNode name;
    @Child private ArgumentsNode args;
    @Child private ToStrNode toStr = ToStrNode.create();
    @Child private CallableDispatchNode dispatch = CallableDispatchNodeGen.create();

    public DynamicMethodCallNode(ExprNode object, ExprNode name, ArgumentsNode args) {
        this.object = object;
        this.name = name;
        this.args = args;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object target = object.execute(frame);
        Object method = toStr.execute(name.execute(frame));
        return dispatch.execute(PhpArray.list(target, method), args.values(frame));
    }
}
