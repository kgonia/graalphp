package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;

/**
 * A variable in the global scope (top-level code of any file). Globals live in the context's symbol table as
 * references, so `global $x` in functions and top-level code see the same variable; the site caches the
 * reference ({@link GlobalRefNode}).
 */
public final class GlobalVarNode extends LValueNode {
    private final String name;
    @Child private GlobalRefNode lookup;

    public GlobalVarNode(String name) {
        this.name = name;
        this.lookup = GlobalRefNode.create(name);
    }

    /** The global's reference, or null if there's no such global. */
    private PhpRef find() {
        return lookup.execute(PhpContext.get(this), false);
    }

    /** The global's reference, created if absent. */
    private PhpRef ref() {
        return lookup.execute(PhpContext.get(this), true);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpRef ref = find();
        return ref == null ? PhpNull.NULL : ref.get();
    }

    @Override
    public void write(VirtualFrame frame, Object value) {
        ref().set(value);
    }

    @Override
    public PhpRef makeRef(VirtualFrame frame) {
        return ref();
    }

    @Override
    public void bindRef(VirtualFrame frame, PhpRef ref) {
        PhpContext.get(this).bindGlobal(name, ref);
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        return Places.refArrayForWrite(ref());
    }

    @Override
    public void unset(VirtualFrame frame) {
        PhpContext.get(this).unsetGlobal(name);
    }

    @Override
    public boolean isDefined(VirtualFrame frame) {
        return find() != null;
    }

    @Override
    public boolean isSet(VirtualFrame frame) {
        PhpRef ref = find();
        return ref != null && ref.get() != PhpNull.NULL;
    }

    @Override
    public boolean isPure() {
        return true;
    }
}
