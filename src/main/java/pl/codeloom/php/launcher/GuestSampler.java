package pl.codeloom.php.launcher;

import com.oracle.truffle.tools.profiler.CPUSampler;
import com.oracle.truffle.tools.profiler.StackTraceEntry;
import org.graalvm.polyglot.Engine;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * For a server's steady-state profile (wiki/pe-round4-diagnosis.md): the PHP stacks of the request threads, sampled
 * across all the requests' contexts and summed, from a moment the driver picks to the engine's close. The CPU
 * sampler's own output is a histogram per context, so per request, and from the start (warm-up included).
 *
 * <p>-Dphp.sample.out=path turns it on: a daemon thread waits for path.start to exist, then every
 * php.sample.period milliseconds (default 10) takes a sample of every thread running PHP (CPUSampler.takeSample)
 * and counts each stack's top frame (self) by function and tier (0 the interpreter, 1, 2), and each function once
 * per stack (total). At the server's close the counts go to path. A thread blocked outside PHP's code (a database
 * read) answers late or not at all, so the samples lean to time on the CPU. For analysis, never for timing.
 */
final class GuestSampler {
    private static volatile GuestSampler running;

    private final CPUSampler sampler;
    private final Path out;
    private final Map<String, long[]> self = new HashMap<>();
    private final Map<String, Long> total = new HashMap<>();
    private long samples;
    private long stacks;

    private GuestSampler(CPUSampler sampler, Path out) {
        this.sampler = sampler;
        this.out = out;
    }

    /** Starts the sampler if -Dphp.sample.out is set. */
    static void startIfAsked(Engine engine) {
        String outName = System.getProperty("php.sample.out");
        if (outName == null) {
            return;
        }
        long period = Long.getLong("php.sample.period", 10);
        Path out = Path.of(outName);
        Path start = Path.of(outName + ".start");
        GuestSampler guest = new GuestSampler(CPUSampler.find(engine), out);
        running = guest;
        Thread thread = new Thread(() -> {
            try {
                while (!Files.exists(start)) {
                    Thread.sleep(1000);
                }
                System.err.println("guest sampler: sampling every " + period + " ms");
                while (true) {
                    Thread.sleep(period);
                    guest.sample();
                }
            } catch (InterruptedException e) {
                // the server closes
            }
        }, "guest-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    /** Writes the counts, if the sampler runs (before the engine closes). */
    static void writeIfRunning() {
        GuestSampler guest = running;
        if (guest != null) {
            guest.write();
        }
    }

    private void sample() {
        Map<Thread, List<StackTraceEntry>> taken = sampler.takeSample();
        synchronized (this) {
            samples++;
            for (List<StackTraceEntry> stack : taken.values()) {
                if (stack.isEmpty()) {
                    continue;
                }
                stacks++;
                StackTraceEntry top = stack.get(0);
                self.computeIfAbsent(top.getRootName(), name -> new long[3])[Math.min(top.getTier(), 2)]++;
                Set<String> seen = new HashSet<>();
                for (StackTraceEntry entry : stack) {
                    if (seen.add(entry.getRootName())) {
                        total.merge(entry.getRootName(), 1L, Long::sum);
                    }
                }
            }
        }
    }

    private synchronized void write() {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            long[] tiers = new long[3];
            for (long[] counts : self.values()) {
                for (int i = 0; i < 3; i++) {
                    tiers[i] += counts[i];
                }
            }
            w.printf("samples %d, stacks %d; self by tier: T0 %d, T1 %d, T2 %d%n", samples, stacks, tiers[0],
                    tiers[1], tiers[2]);
            w.println("self\tT0\tT1\tT2\ttotal\tfunction");
            List<String> names = new ArrayList<>(self.keySet());
            names.sort((a, b) -> Long.compare(sum(self.get(b)), sum(self.get(a))));
            for (String name : names) {
                long[] counts = self.get(name);
                w.printf("%d\t%d\t%d\t%d\t%d\t%s%n", sum(counts), counts[0], counts[1], counts[2],
                        total.getOrDefault(name, 0L), name);
            }
            w.println("total\tfunction (no self time)");
            List<String> others = new ArrayList<>(total.keySet());
            others.removeAll(self.keySet());
            others.sort((a, b) -> Long.compare(total.get(b), total.get(a)));
            for (String name : others) {
                w.printf("%d\t%s%n", total.get(name), name);
            }
        } catch (IOException e) {
            System.err.println("guest sampler: " + e);
        }
    }

    private static long sum(long[] counts) {
        return counts[0] + counts[1] + counts[2];
    }
}
