package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpObject;

/**
 * A direct call of get_object_vars($object): the properties the calling scope may access (inside the class, its
 * protected and private ones too).
 */
public final class ObjectVarsNode extends ExprNode {
    @Child private ExprNode object;
    /** The class (lowercase) the call is written in, or null outside classes. */
    private final String lexicalClass;

    public ObjectVarsNode(ExprNode object, String lexicalClass) {
        this.object = object;
        this.lexicalClass = lexicalClass;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = object.execute(frame);
        if (value instanceof PhpObject phpObject) {
            return phpObject.visibleProperties(PropNode.callingScope(frame, lexicalClass));
        }
        throw notAnObject(value);
    }

    @TruffleBoundary
    private static PhpError notAnObject(Object value) {
        return new PhpError("TypeError", "get_object_vars(): Argument #1 ($object) must be of type object, "
                + Convert.typeName(value) + " given");
    }
}
