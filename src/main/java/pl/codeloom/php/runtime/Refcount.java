package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Objects' reference counts (wiki/destructors.md, P1), counting only: the store paths count every {@link PhpObject}
 * a slot holds (a variable, an element of a held array, a property, a reference, a parameter, a capture) and every
 * owning temporary (the result of `new` or a call, until it is stored, returned or dropped). The two events a
 * decrement can raise, the count reaching zero and the object becoming a possible root of a garbage cycle, go to the
 * stubs here, out of line as the destructor call and the root buffer will be. Nothing runs at zero yet: the counts
 * are measured before destructors are built on them.
 * <p>
 * Counts are plain ints: a context runs on one thread at a time (D18, answer 7). With {@code -Dphp.rc.stats=true}
 * the number of events is printed at exit.
 */
public final class Refcount {
    private Refcount() {
    }

    private static final boolean STATS = Boolean.getBoolean("php.rc.stats");
    private static long zeros;
    private static long roots;

    static {
        if (STATS) {
            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                    System.err.println("refcount: " + zeros + " zeros, " + roots + " possible roots")));
        }
    }

    /** A slot or an owning temporary takes the value. */
    public static void inc(Object value) {
        if (value instanceof PhpObject object) {
            object.addRef();
        }
    }

    /** A slot or an owning temporary gives the value up. */
    public static void dec(Object value) {
        if (value instanceof PhpObject object) {
            object.release();
        }
    }

    /** The count reached zero: php-src's zend_objects_store_del (the destructor, then the properties' release). */
    @TruffleBoundary
    static void zero(PhpObject object) {
        if (STATS) {
            zeros++;
            if (TRACE) {
                System.err.println("refcount: zero " + object.getPhpClass().getName() + " #" + object.id());
            }
        }
    }

    private static final boolean TRACE = Boolean.getBoolean("php.rc.trace");

    /** A decrement left the count above zero: php-src's gc_possible_root, which buffers the object once. */
    @TruffleBoundary
    static void possibleRoot(PhpObject object) {
        object.buffered();
        if (STATS) {
            roots++;
        }
    }
}
