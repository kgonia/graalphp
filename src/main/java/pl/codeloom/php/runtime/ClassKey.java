package pl.codeloom.php.runtime;

import java.util.List;

/**
 * The language-level identity of a class, which inline caches key on instead of the per-context
 * {@link PhpClass}: its declaration (a {@link ClassTemplate}, or a built-in class's name) and the keys of its
 * parent and interfaces. Those determine everything a cache takes from the class: its methods, its constructor
 * and its declared property names. Keys are interned by the language, so two contexts that declare the same
 * classes from the same code get the same keys, and a cache stays monomorphic across them.
 */
public final class ClassKey {
    /** What a key is interned by. */
    public record Id(Object declaration, ClassKey parent, List<ClassKey> interfaces) {
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
