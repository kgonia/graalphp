package pl.codeloom.php;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runtime compilation of PHP programs, with compilation forced early and synchronously: every failed compilation
 * (a graph-size bailout, a frame that escapes, code too large, a deopt cycle, ...) and every performance warning of
 * partial evaluation fails the test, with the engine's log. The programs are src/test/resources/compile/*.php, each
 * a construct the compilation diagnosis (tests/gate2/compile-diag.sh, wiki/partial-evaluation.md) found a problem
 * in, and Gate 1's kernels when they are fetched (bench/gate1/fetch.sh).
 */
class CompilationTest {
    /**
     * Low thresholds, so a short run compiles what it runs at both tiers and its loops by OSR; compilation on the
     * running thread, so a run compiles the same targets in the same order every time.
     */
    private static final Map<String, String> OPTIONS = Map.ofEntries(
            Map.entry("engine.BackgroundCompilation", "false"),
            Map.entry("engine.DynamicCompilationThresholds", "false"),
            Map.entry("engine.MinInvokeThreshold", "1"),
            Map.entry("engine.FirstTierMinInvokeThreshold", "1"),
            Map.entry("engine.FirstTierCompilationThreshold", "50"),
            Map.entry("engine.LastTierCompilationThreshold", "400"),
            Map.entry("engine.OSRCompilationThreshold", "1000"),
            Map.entry("engine.TraceCompilation", "true"),
            Map.entry("engine.CompilationFailureAction", "Print"),
            Map.entry("compiler.TracePerformanceWarnings", "all"),
            // as the launchers default it (PhpMain.compilerDefaults)
            Map.entry("compiler.FirstTierUseEconomy", "false"));

    static Stream<String> programs() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/test/resources/compile"))) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".php")).sorted().toList()
                    .stream();
        }
    }

    @ParameterizedTest
    @MethodSource("programs")
    void compilesWithoutFailure(String program) throws IOException {
        Path path = Path.of("src/test/resources/compile", program);
        String log = run(path, OPTIONS);
        check(program, log, 1);
        checkBudgets(program, Files.readString(path), log);
    }

    private static final Pattern BUDGET = Pattern.compile("^// IR budget: (\\S+) (\\d+)$", Pattern.MULTILINE);
    private static final Pattern DONE = Pattern.compile(
            "^\\[engine\\] opt done\\s+engine=\\d+\\s+id=\\d+\\s+(.*?)\\s*\\|Tier.*?\\|IR\\s+(\\d+)/.*?\\|Src (\\S+)");

    /**
     * A program's budgets of compiled code, lines "// IR budget: TARGET MAX": every compilation of the call target
     * named TARGET, or of the code at TARGET as file:line, has at most MAX IR nodes after partial evaluation. A
     * budget holds the code per operation of a construct to what it is now: HotSpot installs at most 640 KB of
     * code for one compilation, about 60,000 IR nodes, and WordPress's largest functions came close to it or over
     * (wiki/partial-evaluation.md).
     */
    private static void checkBudgets(String name, String source, String log) {
        Matcher budget = BUDGET.matcher(source);
        while (budget.find()) {
            String target = budget.group(1);
            int max = Integer.parseInt(budget.group(2));
            List<String> compilations = new ArrayList<>();
            int largest = 0;
            for (String line : log.split("\n")) {
                Matcher done = DONE.matcher(line);
                if (done.find() && (done.group(1).equals(target) || done.group(3).equals(target))) {
                    compilations.add(line);
                    largest = Math.max(largest, Integer.parseInt(done.group(2)));
                }
            }
            assertTrue(!compilations.isEmpty(), name + ": " + target + " was not compiled\n" + log);
            assertTrue(largest <= max, name + ": " + target + " compiled to " + largest + " IR nodes, its budget is "
                    + max + ":\n" + String.join("\n", compilations));
        }
    }

    /**
     * The kernels at the first tier only, without OSR (their call trees and loops take seconds each at the last
     * tier, run synchronously), for a few iterations.
     */
    @ParameterizedTest
    @MethodSource("kernels")
    void kernelCompilesWithoutFailure(String kernel) {
        Path driver = Path.of("bench/gate1/run.php");
        Assumptions.assumeTrue(Files.exists(Path.of("bench/gate1/corpus.php")),
                "Gate 1's WordPress files and corpus are not fetched (bench/gate1/fetch.sh)");
        Map<String, String> firstTier = new HashMap<>(OPTIONS);
        firstTier.put("engine.Mode", "latency");
        firstTier.put("engine.OSR", "false");
        String log = run(driver, firstTier, kernel, "5");
        check(kernel, log, 5);
    }

    static Stream<String> kernels() {
        return Stream.of("hooks", "escape", "slugs", "wpautop", "wptexturize", "blocks", "kses");
    }

    private static void check(String name, String log, int minimum) {
        List<String> problems = new ArrayList<>();
        int done = 0;
        for (String line : log.split("\n")) {
            if (line.startsWith("[engine] opt done")) {
                done++;
            } else if (line.startsWith("[engine] opt failed") || line.startsWith("[engine] perf warn")) {
                problems.add(line);
            }
        }
        if (!problems.isEmpty()) {
            fail(name + ": " + problems.size() + " failed compilations or performance warnings:\n"
                    + String.join("\n", problems) + "\n\nThe engine's log:\n" + log);
        }
        assertTrue(done >= minimum, name + " compiled " + done + " call targets, expected at least " + minimum
                + ": the thresholds no longer force compilation?\n" + log);
    }

    /** Runs the script with args in a context of its own engine; the engine's log. A PHP error fails the test. */
    private static String run(Path script, Map<String, String> options, String... args) {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] argv = new String[args.length + 1];
        argv[0] = script.toString();
        System.arraycopy(args, 0, argv, 1, args.length);
        try (Engine engine = Engine.newBuilder(PhpLanguage.ID).allowExperimentalOptions(true).options(options)
                .logHandler(log).out(out).err(out).build();
             Context context = Context.newBuilder(PhpLanguage.ID).engine(engine).allowExperimentalOptions(true)
                     .option("php.Parser", "java").arguments(PhpLanguage.ID, argv).allowIO(IOAccess.ALL)
                     .allowNativeAccess(true).build()) {
            context.eval(Source.newBuilder(PhpLanguage.ID, new File(script.toString())).build());
        } catch (PolyglotException e) {
            if (!e.isExit()) {
                fail(script + " failed: " + e.getMessage() + "\n" + log.toString(StandardCharsets.UTF_8), e);
            }
        } catch (IOException e) {
            fail(e);
        }
        return log.toString(StandardCharsets.UTF_8);
    }
}
