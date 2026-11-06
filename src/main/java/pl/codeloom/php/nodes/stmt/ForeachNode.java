package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpGenerator;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.RefIteration;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

/**
 * foreach. By value it iterates a snapshot: the loop holds the array, so writes to the variable inside the loop
 * copy it and the iteration is unaffected. By reference it holds the variable's reference and walks whatever array
 * that reference holds at each step ({@link RefIteration}), binding the value variable to each element's
 * reference. Objects iterate their Iterator methods or public properties.
 */
public final class ForeachNode {
    /** notIterable for a loop at site. A Closure is an object without properties: the loop runs no times, quietly. */
    @TruffleBoundary
    public static void notIterable(Node site, Object value) {
        if (value instanceof pl.codeloom.php.runtime.PhpClosure) {
            return;
        }
        String type = value instanceof Boolean b ? (b ? "true" : "false") : Convert.debugType(value);
        ErrorFunctions.raise(PhpContext.get(site), ErrorFunctions.E_WARNING,
                Str.of("foreach() argument must be of type array|object, " + type + " given"), SourceLocation.of(site));
    }


    /** An iteration step made from site, which traces show as the call's location. */
    @TruffleBoundary
    public static Object step(Node site, PhpObject object, String method) {
        if (!(object.getInternal() instanceof PhpGenerator generator)) {
            return object.callMethod(site, method);
        }
        return Backtrace.callFrom(site, () -> generatorStep(generator, method));
    }

    private static Object generatorStep(PhpGenerator generator, String method) {
        return switch (method) {
            case "valid" -> generator.valid();
            case "current" -> generator.current();
            case "key" -> generator.key();
            case "rewind" -> {
                // foreach's start: zend_generator_get_iterator, then its rewind
                generator.startIteration(false);
                generator.rewind();
                yield PhpNull.NULL;
            }
            default -> {
                generator.next();
                yield PhpNull.NULL;
            }
        };
    }

    /** A step of {@link #traverse}: false stops the walk. */
    @FunctionalInterface
    public interface Visit {
        boolean element(Object key, Object value);
    }

    /**
     * A Traversable walked as php-src's object iterator walks it for unpacking (zend_user_it_*): rewind, then per
     * element valid, current, key, visit, next; from site, which traces show as the calls' location.
     */
    @TruffleBoundary
    public static void traverse(Node site, PhpObject subject, Visit visit) {
        PhpObject iterator = unwrapAggregates(site, subject);
        step(site, iterator, "rewind");
        while (Convert.toBool(step(site, iterator, "valid"))) {
            Object value = PhpRef.deref(step(site, iterator, "current"));
            if (!visit.element(PhpRef.deref(step(site, iterator, "key")), value)) {
                return;
            }
            step(site, iterator, "next");
        }
    }

    /** unwrapAggregates for a loop at site. */
    @TruffleBoundary
    public static PhpObject unwrapAggregates(Node site, PhpObject subject) {
        PhpObject object = subject;
        while (object.getPhpClass().isSubclassOf("iteratoraggregate")) {
            if (!(object.callMethod(site, "getiterator") instanceof PhpObject inner)) {
                throw new PhpError("getIterator() must return a Traversable");
            }
            object = inner;
        }
        return object;
    }
}
