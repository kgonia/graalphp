package pl.codeloom.php.parser;

import java.util.List;
import java.util.Map;

/**
 * A zend_ast node as php-ast exports it (see tools/ast-export.php). Children are positional for list kinds and
 * named otherwise. Leaf values are String (UTF-8 text), byte[] (non-UTF-8 bytes), Long, Double or null.
 */
public final class Ast {
    public final String kind;
    public final int flags;
    public final int line;
    public final int endLine;
    private final List<Object> list;
    private final Map<String, Object> named;

    Ast(String kind, int flags, int line, int endLine, List<Object> list, Map<String, Object> named) {
        this.kind = kind;
        this.flags = flags;
        this.line = line;
        this.endLine = endLine;
        this.list = list;
        this.named = named;
    }

    /** Positional children of a list kind (empty for kinds without children). */
    public List<Object> children() {
        return list != null ? list : List.of();
    }

    public int size() {
        return list != null ? list.size() : 0;
    }

    public Object child(int index) {
        return list.get(index);
    }

    /** The named children's values (expr, stmts, ...), in no particular order. */
    public java.util.Collection<Object> namedValues() {
        return named != null ? named.values() : List.of();
    }

    /** The positional children as read, or null for a node with named children (for comparing trees). */
    List<Object> listOrNull() {
        return list;
    }

    /** The named children as read, in the export's order, or null for a list node. */
    Map<String, Object> namedOrNull() {
        return named;
    }

    public Object get(String name) {
        return named != null ? named.get(name) : null;
    }

    public Ast node(String name) {
        Object child = get(name);
        return child instanceof Ast ast ? ast : null;
    }

    /** A named child that holds a name or identifier (an AST_NAME node's text, or a plain string). */
    public String text(String name) {
        Object child = get(name);
        if (child instanceof String s) {
            return s;
        }
        if (child instanceof Ast ast && ast.get("name") instanceof String s) {
            return s;
        }
        return null;
    }

    public boolean is(String expectedKind) {
        return kind.equals(expectedKind);
    }

    @Override
    public String toString() {
        return kind + "@" + line;
    }
}
