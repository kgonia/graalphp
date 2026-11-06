package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Objects' reference counts: the counting-only prototype of wiki/destructors.md (P1, prototype item 1). An object's
 * count is the number of slots that hold it: variables, parameters, properties, array elements and references.
 * Temporaries are not counted: a value an expression produces has its count from the slots alone, a slot that
 * receives it increments, and a temporary that is dropped (an expression statement's value, a call's temporary
 * argument) is checked for zero ({@link #freeTemporary}). A returned object holds a count while the callee's
 * variables are released and gives it up after (a move), so returning a local doesn't pass through zero.
 *
 * <p>Nothing happens at zero yet: a release keeps php-src's two branches, the count reaching zero
 * (zend_objects_store_del) and the object becoming a possible root of a garbage cycle (gc_check_possible_root),
 * and both lead to stubs behind a boundary, so compiled code pays for the branches and their calls as the full
 * design would. The root stub marks the object as buffered (php-src's GC_INFO), so later releases skip it, as
 * php-src's do until a collection; the zero stub marks it destroyed.
 *
 * <p>With -Dphp.rc.stats=true the stubs count their events, and the counts are printed at exit.
 */
public final class RefCounts {
    /** In the (imagined) root buffer: later releases don't make it a possible root again. */
    public static final int ROOTED = 1;
    /** Its count reached zero: php-src would have destroyed it. */
    public static final int DESTROYED = 1 << 1;

    public static final boolean STATS = Boolean.getBoolean("php.rc.stats");
    private static final AtomicLongArray EVENTS = new AtomicLongArray(3);
    private static final int ZERO = 0;
    private static final int ROOT = 1;
    private static final int NEGATIVE = 2;

    static {
        if (STATS) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.err.println("php.rc.stats: zero "
                    + EVENTS.get(ZERO) + ", possible roots " + EVENTS.get(ROOT) + ", below zero "
                    + EVENTS.get(NEGATIVE))));
        }
    }

    private RefCounts() {
    }

    /** The object's count, -1 for any other value (php.Debug's truffle_refcount). */
    public static int count(Object value) {
        return value instanceof PhpObject object ? object.rc : -1;
    }

    /** One more slot holds value (if it is an object). */
    public static void addRef(Object value) {
        if (value instanceof PhpObject object) {
            object.rc++;
        }
    }

    public static void addRef(PhpObject object) {
        object.rc++;
    }

    /** The count less one, for a node that profiles the branches itself (then zero, or possibleRoot if unbuffered). */
    public static int decrement(PhpObject object) {
        int count = object.rc - 1;
        object.rc = count;
        return count;
    }

    /** Not a possible root yet, nor destroyed (php-src's GC_INFO is 0). */
    public static boolean unbuffered(PhpObject object) {
        return object.gcInfo == 0;
    }

    /** A slot gives up value (if it is an object). */
    public static void release(Object value) {
        if (value instanceof PhpObject object) {
            release(object);
        }
    }

    /** A slot gives up the object: zero, or a possible root (unprofiled: the runtime's paths). */
    public static void release(PhpObject object) {
        int count = object.rc - 1;
        object.rc = count;
        if (count == 0) {
            zero(object);
        } else if (object.gcInfo == 0) {
            possibleRoot(object);
        }
    }

    /**
     * A count moved from a slot to a temporary (a returned object once the callee's variables are released, an
     * element popped): no zero test, the value lives on as the temporary.
     */
    public static void moved(Object value) {
        if (value instanceof PhpObject object) {
            object.rc--;
        }
    }

    /** A temporary is dropped: an object nothing holds is at zero (once), an array nothing holds frees its elements. */
    public static void freeTemporary(Object value) {
        if (value instanceof PhpObject object) {
            if (object.rc == 0 && (object.gcInfo & DESTROYED) == 0) {
                zero(object);
            }
        } else if (value instanceof PhpArray array) {
            array.freeTemporary();
        }
    }

    /** The receiver of a method call other than `$this->m()`: its count's release (php-src's OBJ_RELEASE of This). */
    public static void receiverReleased(Object receiver) {
        if (receiver instanceof PhpObject object) {
            if (object.rc == 0) {
                if ((object.gcInfo & DESTROYED) == 0) {
                    zero(object);
                }
            } else if (object.gcInfo == 0) {
                possibleRoot(object);
            }
        }
    }

    /** The count reached zero: php-src destroys the object here (zend_objects_store_del). A stub for now. */
    @TruffleBoundary
    public static void zero(PhpObject object) {
        object.gcInfo = DESTROYED;
        if (STATS) {
            EVENTS.incrementAndGet(ZERO);
        }
    }

    /** The count fell but not to zero: php-src puts the object in the root buffer (gc_possible_root). A stub. */
    @TruffleBoundary
    public static void possibleRoot(PhpObject object) {
        object.gcInfo |= ROOTED;
        if (STATS) {
            EVENTS.incrementAndGet(object.rc < 0 ? NEGATIVE : ROOT);
        }
    }

    /**
     * A site's releases, profiled: the zero and the possible-root branches are a deoptimization until the site
     * first takes them (then a call behind a boundary), so a site whose objects are never released to zero compiles
     * no call there. A node keeps one in a final field.
     */
    public static final class Releases {
        private static final int ZERO_SEEN = 1;
        private static final int ROOT_SEEN = 1 << 1;
        @CompilationFinal private int seen;

        public static Releases create() {
            return new Releases();
        }

        public void release(PhpObject object) {
            int count = object.rc - 1;
            object.rc = count;
            if (count == 0) {
                met(ZERO_SEEN);
                zero(object);
            } else if (object.gcInfo == 0) {
                met(ROOT_SEEN);
                possibleRoot(object);
            }
        }

        private void met(int bit) {
            if ((seen & bit) == 0) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seen |= bit;
            }
        }
    }
}
