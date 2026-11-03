package pl.codeloom.php.bytecode;

import pl.codeloom.php.nodes.vars.PropNode;

/**
 * A property access, `$obj->name` (or `$obj->$name`): the constant operand of its instruction, which keeps a
 * {@link PropNode} of its own for the access (made by {@link #create}) and the tree's rules through it.
 */
public final class PropSite {
    /** The interned property name, or null when the instruction computes it. */
    final String name;
    final String lexicalClass;
    private final boolean scopeInFrame;
    private final boolean thisInMethod;
    /** In a property hook, the hooked property's name (PropNode's hookOf); else null. */
    private final String hookOf;
    /** The uncached interpreter's access, made once. */
    private PropNode uncached;

    public PropSite(String name, String lexicalClass, boolean scopeInFrame, boolean thisInMethod, String hookOf) {
        this.name = name;
        this.hookOf = hookOf;
        this.lexicalClass = lexicalClass;
        this.scopeInFrame = scopeInFrame;
        this.thisInMethod = thisInMethod;
    }

    /** A cached instruction's own access node. */
    PropNode create() {
        return PropNode.forOperands(name, lexicalClass, scopeInFrame, thisInMethod, hookOf, false);
    }

    PropNode uncached() {
        PropNode access = uncached;
        if (access == null) {
            access = PropNode.forOperands(name, lexicalClass, scopeInFrame, thisInMethod, hookOf, true);
            uncached = access;
        }
        return access;
    }

    @Override
    public String toString() {
        return "->" + (name == null ? "$name" : name);
    }
}
