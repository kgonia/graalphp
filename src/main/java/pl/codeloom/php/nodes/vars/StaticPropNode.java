package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpRef;

/** `Cls::$name`: a static property, which is always a reference shared by the class (and subclasses). */
public final class StaticPropNode extends LValueNode {
    @Child private ClassRefNode classRef;
    private final String name;

    public StaticPropNode(ClassRefNode classRef, String name) {
        this.classRef = classRef;
        this.name = name;
    }

    private PhpRef ref(VirtualFrame frame) {
        PhpRef ref = classRef.resolve(frame).getStaticProperty(name);
        if (ref == null) {
            throw new PhpError("Access to undeclared static property " + classRef.getName() + "::$" + name);
        }
        return ref;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return ref(frame).get();
    }

    @Override
    public void write(VirtualFrame frame, Object value) {
        ref(frame).set(value);
    }

    @Override
    public PhpRef makeRef(VirtualFrame frame) {
        return ref(frame);
    }

    @Override
    public void bindRef(VirtualFrame frame, PhpRef ref) {
        throw new PhpError("Binding a static property by reference is not supported");
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        return Places.refArrayForWrite(ref(frame));
    }

    @Override
    public void unset(VirtualFrame frame) {
        throw new PhpError("Attempt to unset static property " + classRef.getName() + "::$" + name);
    }

    @Override
    public boolean isSet(VirtualFrame frame) {
        PhpRef ref = classRef.resolve(frame).getStaticProperty(name);
        return ref != null && ref.get() != pl.codeloom.php.runtime.PhpNull.NULL;
    }

    @Override
    public boolean isPure() {
        return true;
    }
}
