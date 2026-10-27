import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs PHP in contexts on one shared engine, the way a server runs requests (from tests/shared/run.sh):
 * <ul>
 * <li>{@code script.php expected threads runs}: the script in many contexts at once (threads x runs each); every
 * run must print the expected line;</li>
 * <li>{@code --sequence a.php b.php...}: each script in a new context, one after another, each output after a
 * "== script" line, for run.sh to compare with what PHP prints for each script alone;</li>
 * <li>{@code --retained script.php mb}: the script in a context, closed; then at most mb MB more heap may stay in
 * use than before it ran (a closed context's state must not stay reachable from the engine).</li>
 * </ul>
 */
public final class SharedContexts {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--sequence")) {
            sequence(args);
        } else if (args[0].equals("--retained")) {
            retained(args[1], Integer.parseInt(args[2]));
        } else {
            concurrent(args);
        }
    }

    private static Context context(Engine engine, ByteArrayOutputStream out) {
        return Context.newBuilder("php").engine(engine).out(out).err(out).option("php.AstRoot", "target/ast")
                .allowAllAccess(true).build();
    }

    private static String run(Engine engine, Source source) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = context(engine, out)) {
            context.eval(source);
        } catch (PolyglotException e) {
            if (!e.isExit()) {
                throw e;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void concurrent(String[] args) throws Exception {
        Source source = Source.newBuilder("php", new File(args[0])).build();
        String expected = args[1];
        int threads = Integer.parseInt(args[2]);
        int runs = Integer.parseInt(args[3]);
        int failures = 0;
        try (Engine engine = Engine.create("php")) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<String>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    StringBuilder outputs = new StringBuilder();
                    for (int r = 0; r < runs; r++) {
                        outputs.append(run(engine, source));
                    }
                    return outputs.toString();
                }));
            }
            for (Future<String> result : results) {
                for (String line : result.get().split("\n")) {
                    if (!line.equals(expected)) {
                        failures++;
                        System.out.println("unexpected: " + line);
                    }
                }
            }
            pool.shutdown();
        }
        System.out.println(failures == 0 ? "ok: " + threads * runs + " contexts on " + threads + " threads"
                : "FAILED: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void sequence(String[] args) throws Exception {
        try (Engine engine = Engine.create("php")) {
            for (int i = 1; i < args.length; i++) {
                System.out.print("== " + args[i] + "\n" + run(engine, Source.newBuilder("php", new File(args[i])).build()));
            }
        }
    }

    private static void retained(String script, int limitMb) throws Exception {
        try (Engine engine = Engine.create("php")) {
            Source source = Source.newBuilder("php", new File(script)).build();
            // what the engine keeps for every context (built-ins, the translation) counts too: a few MB
            long before = usedMb();
            String output = run(engine, source).trim();
            long after = usedMb();
            boolean ok = after - before <= limitMb;
            System.out.println((ok ? "ok: " : "FAILED: ") + script + " printed " + output + ", " + (after - before)
                    + " MB still in use after its context closed (at most " + limitMb + ")");
            System.exit(ok ? 0 : 1);
        }
    }

    private static long usedMb() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
    }
}
