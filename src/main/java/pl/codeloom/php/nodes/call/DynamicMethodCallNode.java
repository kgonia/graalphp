package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ExprNode;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/** `$object->$name(args)`: called like the callable [$object, $name]. */
public final class DynamicMethodCallNode extends CallNode {
    @Child private ExprNode object;
    @Child private ExprNode name;
    @Child private ArgumentsNode args;
    @Child private IndirectCallNode call = IndirectCallNode.create();

    public DynamicMethodCallNode(ExprNode object, ExprNode name, ArgumentsNode args) {
        this.object = object;
        this.name = name;
        this.args = args;
    }

    @Override
    public Object executeRef(VirtualFrame frame) {
        Object target = object.execute(frame);
        Object method = PhpRef.deref(name.execute(frame));
        if (!(method instanceof TruffleString)) {
            throw MethodCallNode.nameNotString();
        }
        if (!(target instanceof PhpObject) && !(target instanceof PhpClosure)) {
            throw MethodCallNode.memberOnNonObject(Str.java((TruffleString) method), target);
        }
        return CallableCallNode.call(frame, Callables.resolveCall(PhpContext.get(this), PhpArray.list(target, method)),
                args, call);
    }
}
