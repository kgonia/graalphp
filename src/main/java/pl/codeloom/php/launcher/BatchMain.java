package pl.codeloom.php.launcher;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs many PHP scripts in one JVM, each in a fresh context, for conformance suites (tests/phpt):
 * {@code BatchMain --ast-root=dir --list=file --out=dir [--timeout=seconds]}. Each script's stdout goes to
 * {@code out/<script>.out}; {@code out/results.txt} gets one line per script: path, outcome (ok, exit:N, fatal,
 * timeout, internal) and milliseconds. A separate JVM per script would cost a JVM start per test.
 *
 * <p>Scripts already in {@code results.txt} are skipped, so a run can be resumed. A script that ignores
 * cancellation (a loop inside a built-in has no safepoint to stop at) is recorded as {@code hung} and the JVM
 * exits with {@link #HUNG}; the caller restarts it to go on with the rest.
 */
public final class BatchMain {
    /** Exit status when a script could not be stopped; the remaining scripts need a new JVM. */
    public static final int HUNG = 3;

    private BatchMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String astRoot = "target/ast";
        String list = null;
        String outDir = null;
        long timeoutSeconds = 10;
        for (String arg : args) {
            if (arg.startsWith("--ast-root=")) {
                astRoot = arg.substring("--ast-root=".length());
            } else if (arg.startsWith("--list=")) {
                list = arg.substring("--list=".length());
            } else if (arg.startsWith("--out=")) {
                outDir = arg.substring("--out=".length());
            } else if (arg.startsWith("--timeout=")) {
                timeoutSeconds = Long.parseLong(arg.substring("--timeout=".length()));
            } else {
                System.err.println("unknown option " + arg);
                System.exit(2);
            }
        }
        if (list == null || outDir == null) {
            System.err.println("usage: BatchMain [--ast-root=dir] --list=file --out=dir [--timeout=seconds]");
            System.exit(2);
        }
        String root = astRoot;
        List<String> scripts = Files.readAllLines(Path.of(list), StandardCharsets.UTF_8);
        Path resultsFile = Path.of(outDir, "results.txt");
        Set<String> done = new HashSet<>();
        if (Files.exists(resultsFile)) {
            for (String line : Files.readAllLines(resultsFile, StandardCharsets.UTF_8)) {
                done.add(line.split("	", 2)[0]);
            }
        }
        ExecutorService worker = Executors.newSingleThreadExecutor(BatchMain::daemon);
        try (PrintWriter results = new PrintWriter(Files.newBufferedWriter(resultsFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            for (String script : scripts) {
                if (script.isBlank() || done.contains(script)) {
                    continue;
                }
                long start = System.nanoTime();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteArrayOutputStream err = new ByteArrayOutputStream();
                AtomicReference<Context> running = new AtomicReference<>();
                Future<String> outcome = worker.submit(() -> run(script, root, out, err, running));
                String status;
                try {
                    status = outcome.get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    // closing waits for the script's thread to stop, so it must not block this one
                    Context context = running.get();
                    if (context != null) {
                        daemon(() -> context.close(true)).start();
                    }
                    try {
                        outcome.get(timeoutSeconds, TimeUnit.SECONDS);
                        status = "timeout";
                    } catch (ExecutionException ignored) {
                        status = "timeout";
                    } catch (TimeoutException ignored) {
                        results.println(script + "	hung	" + (System.nanoTime() - start) / 1_000_000);
                        results.flush();
                        Runtime.getRuntime().halt(HUNG);
                        return;
                    }
                } catch (ExecutionException e) {
                    status = "internal " + oneLine(String.valueOf(e.getCause()));
                }
                Path target = Path.of(outDir, script + ".out");
                Files.createDirectories(target.getParent());
                Files.write(target, out.toByteArray());
                if (err.size() > 0) {
                    Files.write(Path.of(outDir, script + ".err"), err.toByteArray());
                }
                long millis = (System.nanoTime() - start) / 1_000_000;
                results.println(script + "\t" + status + "\t" + millis);
                results.flush();
            }
        } finally {
            worker.shutdownNow();
        }
    }

    private static String run(String script, String astRoot, ByteArrayOutputStream out, ByteArrayOutputStream err,
                              AtomicReference<Context> running) throws IOException {
        Source source = Source.newBuilder("php", new File(script)).build();
        try (Context context = Context.newBuilder("php")
                .option("php.AstRoot", astRoot)
                .arguments("php", new String[] {script})
                .out(out)
                .err(err)
                .allowAllAccess(true)
                .build()) {
            running.set(context);
            context.eval(source);
            return "ok";
        } catch (PolyglotException e) {
            if (e.isCancelled()) {
                return "timeout";
            }
            if (e.isExit()) {
                return "exit:" + e.getExitStatus();
            }
            if (e.isInternalError() || !e.isGuestException()) {
                return "internal " + oneLine(String.valueOf(e.getMessage()));
            }
            return "fatal " + oneLine(String.valueOf(e.getMessage()));
        }
    }

    private static Thread daemon(Runnable task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        return thread;
    }

    private static String oneLine(String message) {
        String line = message.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        return line.length() > 300 ? line.substring(0, 300) : line;
    }
}
