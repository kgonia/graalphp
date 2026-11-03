package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpGenerator;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

import java.util.List;

/**
 * foreach by reference over a Generator of a function yielding by reference (zend_generator_get_iterator with
 * by_ref): each step takes the reference the generator yielded; or over a plain object's properties the scope may
 * access, each made a reference (PhpObject.propertyRef). An Iterator is an Error (zend_user_it_get_iterator). The
 * generator's steps are made from the loop's statement or instruction, as traces show them.
 */
public final class GeneratorRefIteration {
    private final PhpGenerator generator;
    /**
     * A plain object's walk (generator null): the object, the calling scope, its properties met so far
     * (PhpObject.visiblePropertyAt), the current one's reference.
     */
    private final PhpObject object;
    private final String scope;
    private final List<String> properties;
    private int index;
    private PhpRef current;
    private boolean started;

    private GeneratorRefIteration(PhpGenerator generator, PhpObject object, String scope, List<String> properties) {
        this.generator = generator;
        this.object = object;
        this.scope = scope;
        this.properties = properties;
    }

    /**
     * The iteration of subject (an IteratorAggregate's iterator) for code in scope: a generator not yielding by
     * reference throws.
     */
    @TruffleBoundary
    public static GeneratorRefIteration start(Node site, PhpObject subject, String scope) {
        PhpClass phpClass = subject.getPhpClass();
        if (!phpClass.isSubclassOf("iterator") && !phpClass.isSubclassOf("iteratoraggregate")) {
            PhpObject object = subject.initialized();
            return new GeneratorRefIteration(null, object, scope, object.visiblePropertyNames(scope));
        }
        PhpObject object = ForeachNode.unwrapAggregates(site, subject);
        if (!(object.getInternal() instanceof PhpGenerator generator)) {
            throw new PhpError("An iterator cannot be used with foreach by reference");
        }
        Backtrace.callFrom(site, () -> {
            generator.startIteration(true);
            generator.rewind();
            return null;
        });
        return new GeneratorRefIteration(generator, null, null, null);
    }

    /** Moves to the next element (after the first step): false at the end. */
    @TruffleBoundary
    public boolean advance(Node site) {
        if (generator == null) {
            current = null;
            String name;
            while (current == null && (name = object.visiblePropertyAt(properties, index, scope)) != null) {
                index++;
                current = object.propertyRef(name);
            }
            return current != null;
        }
        return (Boolean) Backtrace.callFrom(site, () -> {
            if (started) {
                generator.next();
            }
            started = true;
            return generator.valid();
        });
    }

    /** The current element's reference: a value the generator yielded with a notice becomes one. */
    @TruffleBoundary
    public PhpRef currentRef(Node site) {
        if (generator == null) {
            return this.current;
        }
        Object current = Backtrace.callFrom(site, generator::currentRef);
        return current instanceof PhpRef ref ? ref : new PhpRef(current);
    }

    @TruffleBoundary
    public Object key(Node site) {
        if (generator == null) {
            return Str.ofKey(properties.get(index - 1));
        }
        return Backtrace.callFrom(site, generator::key);
    }
}
