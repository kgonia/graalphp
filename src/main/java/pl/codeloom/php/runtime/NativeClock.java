package pl.codeloom.php.runtime;

/**
 * Time spent inside native calls (PCRE2), for the Gate 1 native-share accounting (wiki/gate-1.md). Off unless
 * -Dphp.nativeclock=true: each reading costs about as much as a short match, so timed runs leave it off and the
 * native share comes from a separate instrumented run. One counter for the process: Gate 1 runs a single context.
 *
 * <p>The clock's own cost is measured in place: right after each timed call the caller reads the clock once more,
 * and that back-to-back gap is subtracted. A one-off calibration at startup ran in cold code and left ~50 ns per
 * call in the total, over a millisecond per wpautop iteration (about 4,000 matches).
 */
public final class NativeClock {
    public static final boolean ENABLED = Boolean.getBoolean("php.nativeclock");

    private static long nanos;
    private static long overhead;

    private NativeClock() {
    }

    /** One timed native call: its elapsed time, and the gap between two clock readings taken just after it. */
    public static void add(long elapsed, long clockCost) {
        nanos += elapsed;
        overhead += clockCost;
    }

    /** Native nanoseconds so far, net of the clock's own cost; 0 when disabled. */
    public static long nanos() {
        return Math.max(0, nanos - overhead);
    }
}
