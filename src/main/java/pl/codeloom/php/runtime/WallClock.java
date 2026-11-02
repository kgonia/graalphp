package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.time.Instant;

/** The wall clock to the microsecond, as gettimeofday() gives it to php-src (microtime, uniqid, REQUEST_TIME_FLOAT). */
public final class WallClock {
    private static long lastUniqid;

    private WallClock() {
    }

    /** Microseconds since the epoch. */
    @TruffleBoundary
    public static long micros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1000;
    }

    /**
     * uniqid()'s time: php-src (ext/standard/uniqid.c) reads gettimeofday() again until it differs from the one
     * the process's last uniqid() read, so that two ids never share it. One process here serves every request.
     */
    @TruffleBoundary
    public static synchronized long uniqidMicros() {
        long micros = micros();
        while (micros == lastUniqid) {
            Thread.onSpinWait();
            micros = micros();
        }
        lastUniqid = micros;
        return micros;
    }
}
