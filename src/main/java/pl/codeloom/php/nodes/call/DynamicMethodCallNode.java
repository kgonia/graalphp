package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.ToStrNode;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.PhpArray;

/** `$object->$name(args)`: called like the callable [$object, $name]. */
public final class DynamicMethodCallNode extends CallNode {
    @Child private ExprNode object;
    @Child private ExprNode name;
    @Child private ArgumentsNode args;
    @Child private ToStrNode toStr = ToStrNode.create();
    @Child private IndirectCallNode call = IndirectCallNode.create();

    public DynamicMethodCallNode(ExprNode object, ExprNode name, ArgumentsNode args) {
        this.object = object;
        this.name = name;
        this.args = args;
    }

    @Override
    public Object executeRef(VirtualFrame frame) {
        Object target = object.execute(frame);
        Object method = toStr.execute(name.execute(frame));
        return CallableCallNode.call(frame, Callables.resolveCall(PhpContext.get(this), PhpArray.list(target, method)),
                args, call);
    }
}
