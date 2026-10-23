package pl.codeloom.php.runtime;

import java.util.List;

/**
 * The language-level identity of a class, which inline caches key on instead of the per-context
 * {@link PhpClass}: its declaration (a {@link ClassTemplate}, or a built-in class's name) and the keys of its
 * parent, its interfaces and the traits it uses (each context resolves trait names on its own, and a trait's
 * declaration decides the members it imports). Those determine everything a cache takes from the class: its
 * methods, its constructor, its declared property names and its ancestors. Keys are interned by the language, so
 * two contexts that declare the same classes from the same code get the same keys, and a cache stays monomorphic
 * across them.
 */
public final class ClassKey {
    /** What a key is interned by; traits in the order the class imported them, nested ones included. */
    public record Id(Object declaration, ClassKey parent, List<ClassKey> interfaces, List<ClassKey> traits) {
    }

    private final Id id;

    public ClassKey(Id id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "ClassKey[" + (id.declaration() instanceof ClassTemplate template ? template.getName()
                : id.declaration()) + "]";
    }
}
