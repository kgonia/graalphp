import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs a PHP script in many contexts at once on one shared engine (threads x runs each), the way a server runs
 * requests, and checks that every run printed the expected output. Usage (from tests/shared/run.sh):
 * java -cp ... SharedContexts.java script.php expected threads runs
 */
public final class SharedContexts {
    public static void main(String[] args) throws Exception {
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
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        try (Context context = Context.newBuilder("php").engine(engine).out(out)
                                .option("php.AstRoot", "target/ast").allowAllAccess(true).build()) {
                            context.eval(source);
                        }
                        outputs.append(out.toString(StandardCharsets.UTF_8));
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
}
