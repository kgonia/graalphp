package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;

/**
 * `new Cls(args)`: instantiate, then call the constructor (inline-cached on the constructor), if there is one.
 */
public final class NewNode extends ExprNode {
    @Child private ClassRefNode classRef;
    @Child private ArgumentsNode args;
    /**
     * The constructor called per constructor, not per class: the classes a site instantiates that inherit one
     * constructor share its call. The constructor is the class's own field, so no lookup is cached.
     */
    @Child private MethodDispatchNode construct = MethodDispatchNode.create();
    @Child private InstantiateNode instantiate = InstantiateNode.create();

    public NewNode(ClassRefNode classRef, ArgumentsNode args) {
        this.classRef = classRef;
        this.args = args;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpClass phpClass = classRef.resolve(frame);
        PhpObject object = instantiate.execute(phpClass);
        PhpFunction constructor = phpClass.getConstructor();
        if (constructor != null) {
            construct.execute(frame, constructor, object, phpClass, args);
        } else if (args.count() > 0) {
            // ZEND_NEW without a constructor: the arguments go to zend_pass_function, evaluated and dropped
            args.values(frame);
        }
        return object;
    }
}
