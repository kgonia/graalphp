package pl.codeloom.php.runtime;

/**
 * Nanoseconds spent inside native calls (PCRE2), for the Gate 1 native-share accounting (wiki/gate-1.md). One
 * counter for the process: Gate 1 runs a single context.
 */
public final class NativeClock {
    private static long nanos;

    private NativeClock() {
    }

    public static void add(long elapsed) {
        nanos += elapsed;
    }

    public static long nanos() {
        return nanos;
    }
}
