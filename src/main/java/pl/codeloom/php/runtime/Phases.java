package pl.codeloom.php.runtime;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Where a request's time goes before and around its PHP code runs: the includes, reading and parsing files,
 * reading function bodies left unparsed, and translating trees into Truffle nodes (wiki/visit-phases.md). Off
 * unless -Dphp.phases=true; the flag is a static final, so a run without it folds every check away, and a native
 * image fixes it at build time. The counters sit in slow paths only: an include's lookup, a file's or a body's
 * first translation, both behind a {@code TruffleBoundary}.
 *
 * <p>Every method here returns at once when the flag is off. Times are exclusive: entering a phase stops the
 * clock of the one it interrupts, so a body read while its function is translated counts as reading, not
 * translating, and the phases add up. A thread's counters are its own; a server reads them around each request
 * ({@link #begin}, {@link #current}), which runs on one thread. The functions declared and translated are also
 * counted for the whole process.
 */
public final class Phases {
    public static final boolean ENABLED = Boolean.getBoolean("php.phases");

    /** Not in any phase below: the PHP code running, the built-ins, the context's setup. */
    public static final int EXECUTION = 0;
    /** An include's lookup: the file's path, its modification time and size, the translation cache. */
    public static final int INCLUDE = 1;
    /** Reading a file and decoding its text, on a translation cache miss. */
    public static final int READ = 2;
    /** Parsing a file (php.Parser=java), or reading its php-ast export. */
    public static final int PARSE = 3;
    /** Reading a function body left unparsed (parsing its range again, or its range of the export). */
    public static final int BODY = 4;
    /** Translating a file's top level into nodes (its functions and classes declared, their bodies left). */
    public static final int FILE_TRANSLATE = 5;
    /** Translating a function's body into nodes, on its first call. */
    public static final int TRANSLATE = 6;
    public static final int COUNT = 7;

    /** Kinds of declared and translated functions. */
    public static final int FUNCTION = 0;
    public static final int METHOD = 1;
    public static final int CLOSURE = 2;

    private static final ThreadLocal<Phases> CURRENT = ThreadLocal.withInitial(Phases::new);
    private static final AtomicLongArray DECLARED = new AtomicLongArray(3);
    private static final AtomicLongArray TRANSLATED = new AtomicLongArray(3);

    /** Nanoseconds in each phase. */
    public final long[] nanos = new long[COUNT];
    /** Times each phase was entered. */
    public final long[] entries = new long[COUNT];
    /** Bytes of the files read. */
    public long fileBytes;
    /** Characters of the function bodies a file's parse left unparsed, and of those read again. */
    public long bodyCharsLeft;
    public long bodyCharsRead;
    public final long[] declared = new long[3];
    public final long[] translated = new long[3];

    private int phase = EXECUTION;
    private long since = System.nanoTime();

    private Phases() {
    }

    /** The current thread's counters, zeroed: a request begins. */
    public static Phases begin() {
        Phases phases = new Phases();
        CURRENT.set(phases);
        return phases;
    }

    /** The current thread's counters, with the running phase's time up to now. */
    public static Phases current() {
        Phases phases = CURRENT.get();
        long now = System.nanoTime();
        phases.nanos[phases.phase] += now - phases.since;
        phases.since = now;
        return phases;
    }

    /** Enters a phase; returns the one it interrupts, for {@link #exit}. */
    public static int enter(int phase) {
        if (!ENABLED) {
            return EXECUTION;
        }
        Phases phases = CURRENT.get();
        long now = System.nanoTime();
        int outer = phases.phase;
        phases.nanos[outer] += now - phases.since;
        phases.since = now;
        phases.phase = phase;
        phases.entries[phase]++;
        return outer;
    }

    /** Leaves the current phase for the one {@link #enter} returned. */
    public static void exit(int outer) {
        if (!ENABLED) {
            return;
        }
        Phases phases = CURRENT.get();
        long now = System.nanoTime();
        phases.nanos[phases.phase] += now - phases.since;
        phases.since = now;
        phases.phase = outer;
    }

    /** A file was read: its size. */
    public static void fileRead(long bytes) {
        if (ENABLED) {
            CURRENT.get().fileBytes += bytes;
        }
    }

    /** A file was parsed and left bodies of so many characters unparsed. */
    public static void bodiesLeft(long chars) {
        if (ENABLED) {
            CURRENT.get().bodyCharsLeft += chars;
        }
    }

    /** A body left unparsed was read: its size in characters (or bytes of the export). */
    public static void bodyRead(long chars) {
        if (ENABLED) {
            CURRENT.get().bodyCharsRead += chars;
        }
    }

    public static void declared(int kind) {
        if (ENABLED) {
            CURRENT.get().declared[kind]++;
            DECLARED.incrementAndGet(kind);
        }
    }

    public static void translated(int kind) {
        if (ENABLED) {
            CURRENT.get().translated[kind]++;
            TRANSLATED.incrementAndGet(kind);
        }
    }

    /** Functions of a kind declared so far in the process (in the files translated). */
    public static long declaredTotal(int kind) {
        return DECLARED.get(kind);
    }

    /** Functions of a kind translated so far in the process. */
    public static long translatedTotal(int kind) {
        return TRANSLATED.get(kind);
    }
}
