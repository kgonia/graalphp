package pl.codeloom.php.runtime;

/**
 * Time spent inside native calls (PCRE2), for the Gate 1 native-share accounting (wiki/gate-1.md). Off unless
 * -Dphp.nativeclock=true: each reading costs about as much as a short match, so timed runs leave it off and the
 * native share comes from a separate instrumented run. The per-call cost of the clock itself is calibrated at
 * startup and subtracted. One counter for the process: Gate 1 runs a single context.
 */
public final class NativeClock {
    public static final boolean ENABLED = Boolean.getBoolean("php.nativeclock");
    private static final long OVERHEAD = ENABLED ? calibrate() : 0;

    private static long nanos;
    private static long calls;

    private NativeClock() {
    }

    public static void add(long elapsed) {
        nanos += elapsed;
        calls++;
    }

    /** Native nanoseconds so far, net of the clock's own cost; 0 when disabled. */
    public static long nanos() {
        return Math.max(0, nanos - calls * OVERHEAD);
    }

    /** The median cost of one back-to-back pair of nanoTime readings, as seen by an empty measured region. */
    private static long calibrate() {
        long[] samples = new long[2001];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            samples[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(samples);
        return samples[samples.length / 2];
    }
}
