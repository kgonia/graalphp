package pl.codeloom.php.bytecode;

import pl.codeloom.php.nodes.vars.StaticPropNode;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpRef;

/**
 * A static property, `Cls::$name`: the constant operand of its instructions, which keep a
 * {@link StaticPropNode.Lookup} of their own (made by {@link #lookup}), the tree's per-class cache of the reference.
 */
public final class StaticPropSite {
    /** The interned property name. */
    final String name;
    /** The class the code is written in (lowercase) or null; scopeInFrame as PropSite's. */
    final String lexicalClass;
    final boolean scopeInFrame;

    public StaticPropSite(String name, String lexicalClass, boolean scopeInFrame) {
        this.name = name;
        this.lexicalClass = lexicalClass;
        this.scopeInFrame = scopeInFrame;
    }

    /** A cached instruction's own lookup. */
    StaticPropNode.Lookup lookup() {
        return StaticPropNode.Lookup.create(name, lexicalClass, scopeInFrame);
    }

    /** The uncached interpreter's lookup. */
    PhpRef find(com.oracle.truffle.api.frame.VirtualFrame frame, PhpClass phpClass, boolean quiet) {
        return StaticPropNode.checked(frame, phpClass, phpClass.getStaticProperty(name), name, lexicalClass,
                scopeInFrame, quiet);
    }

    @Override
    public String toString() {
        return "::$" + name;
    }
}
