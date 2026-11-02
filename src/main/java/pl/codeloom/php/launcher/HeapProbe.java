package pl.codeloom.php.launcher;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * For measuring what a request keeps alive (wiki/gc.md): with -Dphp.heapprobe=MS, a daemon thread collects the
 * whole heap every MS milliseconds ({@code System.gc()}) and prints a line {@code heap {...}} with the live heap
 * after it and the requests in flight then. The live heap with no request in flight is the server's own; a
 * request's peak is the most it adds above that. Every probe is a full collection, so the requests run slower;
 * it is for analysis, never for timing.
 */
final class HeapProbe {
    /** The requests running now (the probe's sample of them). */
    static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private HeapProbe() {
    }

    /** Starts the probe if -Dphp.heapprobe is set. */
    static void startIfAsked() {
        long interval = Long.getLong("php.heapprobe", 0);
        if (interval <= 0) {
            return;
        }
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long start = System.nanoTime();
        Thread probe = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    return;
                }
                int before = IN_FLIGHT.get();
                long gcStart = System.nanoTime();
                System.gc();
                long gcEnd = System.nanoTime();
                long used = memory.getHeapMemoryUsage().getUsed();
                System.err.println("heap {\"t_ms\":" + (gcEnd - start) / 1_000_000 + ",\"gc_ms\":"
                        + (gcEnd - gcStart) / 1_000_000 + ",\"live\":" + used + ",\"in_flight\":"
                        + Math.min(before, IN_FLIGHT.get()) + ",\"in_flight_max\":" + Math.max(before,
                        IN_FLIGHT.get()) + "}");
            }
        }, "heap-probe");
        probe.setDaemon(true);
        probe.start();
    }
}
