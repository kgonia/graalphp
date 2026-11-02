package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpGenerator;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;

/**
 * foreach by reference over a Generator of a function yielding by reference (zend_generator_get_iterator with
 * by_ref): each step takes the reference the generator yielded. php-src iterates no other object by reference
 * here: an Iterator is an Error (zend_user_it_get_iterator); a plain object's properties aren't supported. The
 * generator's steps are made from the loop's statement or instruction, as traces show them.
 */
public final class GeneratorRefIteration {
    private final PhpGenerator generator;
    private boolean started;

    private GeneratorRefIteration(PhpGenerator generator) {
        this.generator = generator;
    }

    /** The iteration of subject (an IteratorAggregate's iterator): a generator not yielding by reference throws. */
    @TruffleBoundary
    public static GeneratorRefIteration start(Node site, PhpObject subject) {
        PhpClass phpClass = subject.getPhpClass();
        if (!phpClass.isSubclassOf("iterator") && !phpClass.isSubclassOf("iteratoraggregate")) {
            throw new PhpError("foreach by reference over an object is not supported");
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
        return new GeneratorRefIteration(generator);
    }

    /** Moves to the next element (after the first step): false at the end. */
    @TruffleBoundary
    public boolean advance(Node site) {
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
        Object current = Backtrace.callFrom(site, generator::currentRef);
        return current instanceof PhpRef ref ? ref : new PhpRef(current);
    }

    @TruffleBoundary
    public Object key(Node site) {
        return Backtrace.callFrom(site, generator::key);
    }
}
