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
 * <p>-Dphp.sample.out=path turns it on: a daemon thread waits for path.start to exist (or, with
 * php.sample.after=SECONDS, that long from its start: a script's run, PhpMain, where the counts are written when
 * the script ends), then every
 * php.sample.period milliseconds (default 10) takes a sample of every thread running PHP (CPUSampler.takeSample)
 * and counts each stack's top frame (self) by function and tier (0 the interpreter, 1, 2), and each function once
 * per stack (total). "user" counts the sample for the nearest frame of PHP code, so a built-in's time is its
 * calling PHP function's (what a PHP profiler such as Excimer reports); the pairs of a built-in on top and that
 * caller follow the table. At the server's close the counts go to path. A thread blocked outside PHP's code (a
 * database read) answers late or not at all, so the samples lean to time on the CPU. For analysis, never for timing.
 */
final class GuestSampler {
    private static volatile GuestSampler running;

    private final CPUSampler sampler;
    private final Path out;
    private final Map<String, long[]> self = new HashMap<>();
    private final Map<String, Long> total = new HashMap<>();
    private final Map<String, Long> user = new HashMap<>();
    private final Map<String, Long> builtinUnder = new HashMap<>();
    private long samples;
    private long stacks;

    private GuestSampler(CPUSampler sampler, Path out) {
        this.sampler = sampler;
        this.out = out;
    }

    /** Starts the sampler if -Dphp.sample.out is set and it doesn't run yet. */
    static void startIfAsked(Engine engine) {
        String outName = System.getProperty("php.sample.out");
        if (outName == null || running != null) {
            return;
        }
        long period = Long.getLong("php.sample.period", 10);
        long after = Long.getLong("php.sample.after", -1);
        Path out = Path.of(outName);
        Path start = Path.of(outName + ".start");
        GuestSampler guest = new GuestSampler(CPUSampler.find(engine), out);
        running = guest;
        Thread thread = new Thread(() -> {
            try {
                if (after >= 0) {
                    Thread.sleep(after * 1000);
                } else {
                    while (!Files.exists(start)) {
                        Thread.sleep(1000);
                    }
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
                StackTraceEntry owner = top;
                for (StackTraceEntry entry : stack) {
                    // a built-in's root has the built-ins' unavailable section; a function's has none, a file's its own
                    if (entry.getSourceSection() == null || entry.getSourceSection().isAvailable()) {
                        owner = entry;
                        break;
                    }
                }
                user.merge(owner.getRootName(), 1L, Long::sum);
                if (owner != top) {
                    builtinUnder.merge(top.getRootName() + "\t" + owner.getRootName(), 1L, Long::sum);
                }
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
            w.println("self\tT0\tT1\tT2\ttotal\tuser\tfunction");
            Set<String> withTime = new HashSet<>(self.keySet());
            withTime.addAll(user.keySet());
            List<String> names = new ArrayList<>(withTime);
            long[] none = new long[3];
            names.sort((a, b) -> Long.compare(sum(self.getOrDefault(b, none)), sum(self.getOrDefault(a, none))));
            for (String name : names) {
                long[] counts = self.getOrDefault(name, none);
                w.printf("%d\t%d\t%d\t%d\t%d\t%d\t%s%n", sum(counts), counts[0], counts[1], counts[2],
                        total.getOrDefault(name, 0L), user.getOrDefault(name, 0L), name);
            }
            w.println("total\tfunction (no self time)");
            List<String> others = new ArrayList<>(total.keySet());
            others.removeAll(withTime);
            others.sort((a, b) -> Long.compare(total.get(b), total.get(a)));
            for (String name : others) {
                w.printf("%d\t%s%n", total.get(name), name);
            }
            w.println("samples\tbuilt-in on top\tunder the PHP function");
            List<String> pairs = new ArrayList<>(builtinUnder.keySet());
            pairs.sort((a, b) -> Long.compare(builtinUnder.get(b), builtinUnder.get(a)));
            for (String pair : pairs) {
                w.printf("%d\t%s%n", builtinUnder.get(pair), pair);
            }
        } catch (IOException e) {
            System.err.println("guest sampler: " + e);
        }
    }

    private static long sum(long[] counts) {
        return counts[0] + counts[1] + counts[2];
    }
}
