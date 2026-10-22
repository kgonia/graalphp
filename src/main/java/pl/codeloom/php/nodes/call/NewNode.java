package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;

/** `new Cls(args)`: instantiate, then call the constructor (inline-cached on the class). */
public final class NewNode extends ExprNode {
    @Child private ClassRefNode classRef;
    @Child private ArgumentsNode args;
    @Child private Construct construct = NewNodeFactory.ConstructNodeGen.create();
    @Child private InstantiateNode instantiate = new InstantiateNode();

    public NewNode(ClassRefNode classRef, ArgumentsNode args) {
        this.classRef = classRef;
        this.args = args;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpClass phpClass = classRef.resolve(frame);
        PhpObject object = instantiate.execute(phpClass);
        if (phpClass.getConstructor() != null) {
            construct.execute(frame, phpClass, object, args);
        }
        return object;
    }

    /** Calls a class's constructor on a new object. */
    public abstract static class Construct extends Node {
        public abstract void execute(VirtualFrame frame, PhpClass phpClass, PhpObject object, ArgumentsNode args);

        @Specialization(guards = "phpClass == cachedClass", limit = "4")
        protected void cached(VirtualFrame frame, PhpClass phpClass, PhpObject object, ArgumentsNode args,
                              @Cached("phpClass") PhpClass cachedClass,
                              @Cached("cachedClass.getConstructor()") PhpFunction constructor,
                              @Cached("create(constructor.getCallTarget())") DirectCallNode call) {
            call.call(args.build(frame, constructor, object, cachedClass, null));
        }

        @Specialization(replaces = "cached")
        protected void generic(VirtualFrame frame, PhpClass phpClass, PhpObject object, ArgumentsNode args,
                               @Cached IndirectCallNode call) {
            PhpFunction constructor = phpClass.getConstructor();
            call.call(constructor.getCallTarget(), args.build(frame, constructor, object, phpClass, null));
        }
    }
}
