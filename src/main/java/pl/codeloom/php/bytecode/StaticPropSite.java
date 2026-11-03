package pl.codeloom.php.bytecode;

import pl.codeloom.php.nodes.vars.StaticPropNode;

/**
 * A static property, `Cls::$name`: the constant operand of its instructions, which keep a
 * {@link StaticPropNode.Lookup} of their own (made by {@link #lookup}), the tree's per-class cache of the reference.
 */
public final class StaticPropSite {
    /** The interned property name. */
    final String name;

    public StaticPropSite(String name) {
        this.name = name;
    }

    /** A cached instruction's own lookup. */
    StaticPropNode.Lookup lookup() {
        return StaticPropNode.Lookup.create(name);
    }

    @Override
    public String toString() {
        return "::$" + name;
    }
}
