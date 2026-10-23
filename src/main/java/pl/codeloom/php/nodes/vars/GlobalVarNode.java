package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;

/**
 * A variable in the global scope (top-level code of any file). Globals live in the context's symbol table as
 * references, so `global $x` in functions and top-level code see the same variable.
 */
public final class GlobalVarNode extends LValueNode {
    private final String name;

    public GlobalVarNode(String name) {
        this.name = name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpRef ref = PhpContext.get(this).findGlobal(name);
        return ref == null ? PhpNull.NULL : ref.get();
    }

    @Override
    public void write(VirtualFrame frame, Object value) {
        PhpContext.get(this).globalRef(name).set(value);
    }

    @Override
    public PhpRef makeRef(VirtualFrame frame) {
        return PhpContext.get(this).globalRef(name);
    }

    @Override
    public void bindRef(VirtualFrame frame, PhpRef ref) {
        PhpContext.get(this).bindGlobal(name, ref);
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        return Places.refArrayForWrite(PhpContext.get(this).globalRef(name));
    }

    @Override
    public void unset(VirtualFrame frame) {
        PhpContext.get(this).unsetGlobal(name);
    }

    @Override
    public boolean isDefined(VirtualFrame frame) {
        return PhpContext.get(this).findGlobal(name) != null;
    }

    @Override
    public boolean isSet(VirtualFrame frame) {
        PhpRef ref = PhpContext.get(this).findGlobal(name);
        return ref != null && ref.get() != PhpNull.NULL;
    }

    @Override
    public boolean isPure() {
        return true;
    }
}
