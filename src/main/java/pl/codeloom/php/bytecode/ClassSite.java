package pl.codeloom.php.bytecode;

import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.runtime.PhpClass;

/**
 * A class by name (ClassRefNode.named; self and parent are names here too), or `static`'s class outside a
 * late-static-binding call: the constant operand of the instruction resolving it.
 */
public final class ClassSite {
    final String name;
    /** The lowercase name's {@link pl.codeloom.php.PhpLanguage#classId}. */
    private final int id;

    public ClassSite(String name, int id) {
        this.name = name;
        this.id = id;
    }

    PhpClass find(PhpContext context) {
        return ClassRefNode.find(context, id, name);
    }

    @Override
    public String toString() {
        return name;
    }
}
