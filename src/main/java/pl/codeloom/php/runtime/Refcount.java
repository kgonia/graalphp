package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Objects' reference counts (wiki/destructors.md, P1), counting only: the store paths count every {@link PhpObject}
 * a slot holds (a variable, an element of a held array, a property, a reference, a parameter, a capture) and every
 * owning temporary (the result of `new` or a call, until it is stored, returned or dropped). The two events a
 * decrement can raise, the count reaching zero and the object becoming a possible root of a garbage cycle, go out
 * of line here. At zero, with -Dphp.rc.destructors=true, the destructor runs and the properties are released
 * ({@link #destroy}); without it nothing runs yet. The possible-root event only marks the object (no cycle collector).
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

    /**
     * The count audit (-Dphp.rc.audit=true, Census.audit): at the script's end, before the shutdown functions, each
     * object's count against its holders. when: "end of script" or "exit" (frames still running).
     */
    public static void audit(pl.codeloom.php.PhpContext context, String when) {
        if (Census.AUDIT) {
            Census.audit(context, when);
        }
    }

    /** Whether the translator emits an audit point at each statement's end (-Dphp.rc.audit.every=n). */
    public static final boolean AUDIT_POINTS = Census.AUDIT_POINTS;

    /** A frame's layout for the count audit: its first locals are PHP variables, then the hidden ones. */
    public interface Variables {
        int variableCount();
    }

    /**
     * An owning temporary sent as an argument (audit points only): php-src moves it into the callee (SEND_VAL), so
     * the hidden local still keeping it here isn't a holder php-src has, until it is released ({@link #unsent}).
     */
    public static void sent(Object value) {
        if (value instanceof PhpObject object) {
            Census.sent(object);
        }
    }

    public static void unsent(Object value) {
        if (value instanceof PhpObject object) {
            Census.unsent(object);
        }
    }

    /** A statement ended: every n-th time, the count audit with the frames as roots. */
    public static void auditPoint(com.oracle.truffle.api.frame.MaterializedFrame frame,
                                  com.oracle.truffle.api.bytecode.BytecodeNode bytecode) {
        Census.auditPoint(frame, bytecode);
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

    /**
     * A site's releases, profiled (from the B prototype's RefCounts.Releases): the zero and the possible-root events
     * are a deoptimization until the site first meets them, then a call behind a boundary, so a site whose objects
     * are never released to zero compiles no call there. A node keeps one in a final field.
     */
    public static final class Releases {
        private static final int ZERO_SEEN = 1;
        private static final int ROOT_SEEN = 1 << 1;
        @CompilationFinal private int seen;

        public static Releases create() {
            return new Releases();
        }

        public void release(PhpObject object) {
            int count = object.decrement();
            if (count == 0) {
                met(ZERO_SEEN);
                zero(object);
            } else if (object.unbuffered()) {
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

    /** A decrement's event: the count reached zero, or else the object became a possible root. */
    @TruffleBoundary
    static void released(PhpObject object, int count) {
        if (count == 0) {
            zero(object);
        } else {
            possibleRoot(object);
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
        if (Census.enabled() && Census.reachableAtZero(object)) {
            // a slot that holds it wasn't counted: destroying it would be php-src's behaviour only by accident
            return;
        }
        if (DESTRUCTORS) {
            destroy(object);
        }
    }

    private static final boolean TRACE = Boolean.getBoolean("php.rc.trace");

    /**
     * Destructors at zero (-Dphp.rc.destructors=true, wiki/destructors.md prototype step 2), off by default until
     * the counts are verified on WordPress: what zend_objects_store_del does.
     */
    private static final boolean DESTRUCTORS = Boolean.getBoolean("php.rc.destructors");

    /**
     * zend_objects_store_del: the destructor runs once, with the object held for its duration (GC_ADDREF); an object
     * the destructor stored somewhere (resurrected) lives on, else its properties are released, which can take what
     * they held to zero in turn (zend_object_std_dtor). An exception the destructor throws goes on after the release.
     */
    private static void destroy(PhpObject object) {
        RuntimeException thrown = null;
        if (!object.markDestructorCalled() && object.getPhpClass().findMethod("__destruct") != null) {
            object.addRef();
            try {
                object.callMethod("__destruct");
            } catch (RuntimeException e) {
                thrown = e;
            }
            if (object.decrement() > 0) {
                // resurrected: freed when its count next reaches zero, without its destructor
                if (thrown != null) {
                    throw thrown;
                }
                return;
            }
        }
        if (!object.markFreed()) {
            freeProperties(object);
        }
        if (thrown != null) {
            throw thrown;
        }
    }

    /** php-src's zend_object_std_dtor: each property gives up what it holds. */
    private static void freeProperties(PhpObject object) {
        Object[] table = object.propertyTable();
        for (int i = 1; i < table.length; i += 2) {
            if (table[i] instanceof PhpObject held) {
                held.release();
            } else if (table[i] instanceof PhpArray array) {
                array.removeHolder();
            } else if (table[i] instanceof PhpRef ref) {
                ref.unbind();
            }
        }
    }

    /** A decrement left the count above zero: php-src's gc_possible_root, which buffers the object once. */
    @TruffleBoundary
    static void possibleRoot(PhpObject object) {
        object.buffered();
        if (STATS) {
            roots++;
        }
    }
}
