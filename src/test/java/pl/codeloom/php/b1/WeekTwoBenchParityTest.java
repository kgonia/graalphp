package pl.codeloom.php.b1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codeloom.php.b1.runtime.B1Array;
import pl.codeloom.php.b1.runtime.B1Bytes;
import pl.codeloom.php.b1.runtime.B1Object;
import pl.codeloom.php.b1.runtime.B1RequestContext;
import pl.codeloom.php.ir.PhpIrLowerer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The Week 2 harness kernels on B1 against PHP 8.5.13's checksums (bench/b1-week2/php/oracle.php). */
class WeekTwoBenchParityTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path FIXTURES = Path.of("src/test/resources/b1");

    @ParameterizedTest
    @ValueSource(ints = {7500, 220})
    void hooksOnTheRealPluginApi(int budget) throws Exception {
        var bench = new B1BenchMain(ROOT, budget);
        var headings = B1BenchMain.headings(bench.corpus());
        long[] ns = new long[1];
        for (boolean ctxB : new boolean[]{false, true}) {
            var ctx = bench.context();
            var kernel = bench.hooksKernel(ctx, ctxB, headings);
            for (int i = 0; i < 2; i++)
                assertEquals(ctxB ? B1BenchMain.HOOKS_CTXB : B1BenchMain.HOOKS, B1BenchMain.hooksRound(ctx, kernel, new B1Array(0).freeze(), ns));
            assertEquals(ctxB ? 50400L : null, ctx.globalValue("b1_all_calls"));
            assertTrue(ctx.largestMethod() <= budget);
            if (budget == 220) assertTrue(ctx.outlineHelpers() > 0);
            B1Object.release(ctx, kernel);
        }
        bench.finish();
    }

    @Test void hotKernelsAndTheirCtxBVariant() {
        var bench = new B1BenchMain(ROOT, 7500);
        long[] ns = new long[1];
        for (boolean ctxB : new boolean[]{false, true}) {
            var ctx = bench.context();
            var hot = bench.hot(ctx, ctxB);
            for (var kernel : List.of("mega", "none", "one", "hasf", "aget", "post")) {
                long expected = ctxB && kernel.equals("mega") ? B1BenchMain.MEGA_CTXB : B1BenchMain.HOT.get(kernel);
                assertEquals(expected, B1BenchMain.hotRound(ctx, hot, kernel, ns), kernel);
            }
            assertEquals(ctxB ? 40000L : 0L, ctx.globalValue("b1_all_calls"));
            B1Object.release(ctx, hot);
        }
        bench.finish();
    }

    /** Two requests install the same hidden classes; only CtxB's own hot_f0 eval is linked again. */
    @Test void pairedContextsShareHiddenClasses() {
        var bench = new B1BenchMain(ROOT, 7500);
        long[] ns = new long[1];
        var ctxA = bench.context();
        var ctxB = bench.context();
        var hotA = bench.hot(ctxA, false);
        var cache = ctxA.codeCache();
        long linked = cache.size();
        var hotB = bench.hot(ctxB, true);
        assertSame(cache, ctxB.codeCache());
        assertEquals(linked + 1, cache.size(), "CtxB links only its hot_f0");
        assertTrue(cache.hits() >= linked - 1);
        for (int i = 0; i < 3; i++) {
            assertEquals(B1BenchMain.MEGA_CTXB, B1BenchMain.hotRound(ctxB, hotB, "mega", ns));
            assertEquals(B1BenchMain.HOT.get("mega"), B1BenchMain.hotRound(ctxA, hotA, "mega", ns));
        }
        B1Object.release(ctxA, hotA);
        B1Object.release(ctxB, hotB);
        bench.finish();
    }

    @Test void k0KernelsSmallAndFull() {
        var bench = new B1BenchMain(ROOT, 7500);
        var ctx = bench.context();
        var kernels = (B1Array) B1Compiler.runScript(ctx, ROOT.resolve("bench/k0/kernels.php"), 7500);
        for (var name : B1BenchMain.K0.keySet()) {
            Object small = ctx.callHost(B1Bytes.ofUtf8("k0_" + name), B1BenchMain.K0_SMALL_ARGUMENT.get(name));
            assertEquals(B1BenchMain.K0_SMALL.get(name), B1BenchMain.json(small), name + " small");
            B1Object.release(ctx, small);
            Object full = ctx.callHost(kernels.get(name));
            assertEquals(B1BenchMain.K0.get(name), B1BenchMain.json(full), name);
            B1Object.release(ctx, full);
        }
        assertTrue(ctx.largestMethod() <= 7500);
        B1Object.release(ctx, kernels);
        bench.finish();
    }

    @Test void runtimeIsLintClean() {
        assertTrue(B1BenchMain.runtimeLint().endsWith("violations=0"), B1BenchMain.runtimeLint());
    }

    /** The B1 language level against PHP's output, warnings and errors included. */
    @Test void languageLevelMatchesPhp() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(out);
        B1Object.release(ctx, B1Compiler.runScript(ctx, FIXTURES.resolve("w2d45_language.php"), 7500));
        ctx.shutdown();
        assertEquals(0, ctx.liveDestructibleCount());
        assertEquals(0, ctx.gcRootCount());
        String dir = FIXTURES.toAbsolutePath().toRealPath() + java.io.File.separator;
        String actual = out.toString(StandardCharsets.UTF_8).replace(dir, "/fixtures/");
        assertEquals(Files.readString(FIXTURES.resolve("w2d45_language.stdout")), actual);
    }

    /** P1-1: the host's read-only arrays are frozen, so two requests never count owners or GC roots on them. */
    @Test void pairedHooksShareFrozenHostArrays() throws Exception {
        var bench = new B1BenchMain(ROOT, 7500);
        var headings = B1BenchMain.headings(bench.corpus());
        var corpus = new B1Array(0).freeze();
        assertEquals(-1, headings.gcCount());
        long[] ns = new long[1];
        var ctxA = bench.context();
        var ctxB = bench.context();
        Object kernelA = bench.hooksKernel(ctxA, false, headings), kernelB = bench.hooksKernel(ctxB, true, headings);
        for (int i = 0; i < 2; i++) {
            assertEquals(B1BenchMain.HOOKS_CTXB, B1BenchMain.hooksRound(ctxB, kernelB, corpus, ns));
            assertEquals(B1BenchMain.HOOKS, B1BenchMain.hooksRound(ctxA, kernelA, corpus, ns));
        }
        assertEquals(-1, headings.gcCount());
        assertEquals(-1, corpus.gcCount());
        B1Object.release(ctxA, kernelA);
        B1Object.release(ctxB, kernelB);
        bench.finish();
    }

    /** The audit regressions (w2d45_audit.php) against PHP 8.5.13's output. */
    @Test void auditRegressionsMatchPhp() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(out);
        B1Object.release(ctx, B1Compiler.runScript(ctx, FIXTURES.resolve("w2d45_audit.php"), 7500));
        ctx.shutdown();
        assertEquals(0, ctx.liveDestructibleCount());
        assertEquals(0, ctx.gcRootCount());
        String dir = FIXTURES.toAbsolutePath().toRealPath() + java.io.File.separator;
        String actual = out.toString(StandardCharsets.UTF_8).replace(dir, "/fixtures/");
        assertEquals(Files.readString(FIXTURES.resolve("w2d45_audit.stdout")), actual);
    }

    /** P1-2: function statics, define() constants and require_once state are request data: a restored request
     * sees the snapshot's, never another request's or its own previous request's. */
    @Test void snapshotIsolatesFunctionStaticsConstantsAndIncludes(@TempDir Path dir) throws Exception {
        Path lib = dir.resolve("lib.php");
        Files.writeString(lib, """
                <?php
                function tick() { static $n = 0, $log = []; $n++; $log[] = $n; return $n . ':' . count($log); }
                """);
        Files.writeString(dir.resolve("seed.php"), """
                <?php
                require_once __DIR__ . '/lib.php';
                tick(); tick();
                define('SEEDED', [1, 2]);
                """);
        Path request = dir.resolve("request.php");
        Files.writeString(request, """
                <?php
                require_once __DIR__ . '/lib.php';
                echo tick(), ' ', SEEDED[1], ' ', defined('LOCAL') ? 'local' : 'none', ';';
                define('LOCAL', 1);
                echo tick(), ' ', LOCAL, "\n";
                """);
        var seed = new B1RequestContext(java.io.OutputStream.nullOutputStream());
        B1Object.release(seed, B1Compiler.runScript(seed, dir.resolve("seed.php"), 7500));
        var snapshot = seed.freezePureData();

        var outA = new ByteArrayOutputStream();
        var outB = new ByteArrayOutputStream();
        var a = new B1RequestContext(outA);
        var b = new B1RequestContext(outB);
        for (var ctx : List.of(a, b)) {
            B1Compiler.linkDeclarations(ctx, lib, Set.of("tick"), 7500);
            ctx.restoreFrom(snapshot);
        }
        B1Object.release(a, B1Compiler.runScript(a, request, 7500));
        B1Object.release(b, B1Compiler.runScript(b, request, 7500));
        // The next request on A starts from the snapshot again.
        a.restoreFrom(snapshot);
        B1Object.release(a, B1Compiler.runScript(a, request, 7500));
        assertEquals("3:3 2 none;4:4 1\n3:3 2 none;4:4 1\n", outA.toString(StandardCharsets.UTF_8));
        assertEquals("3:3 2 none;4:4 1\n", outB.toString(StandardCharsets.UTF_8));
        for (var ctx : List.of(a, b, seed)) {
            ctx.shutdown();
            assertEquals(0, ctx.liveDestructibleCount());
            assertEquals(0, ctx.gcRootCount());
        }
    }

    /** P1-10: an unset path rooted in a call result is declined when lowering, not by a crash when linking. */
    @Test void unaddressableNestedUnsetIsDeclined() {
        byte[] source = "<?php function foo() { return []; } unset(foo()['a']['b']);".getBytes(StandardCharsets.UTF_8);
        assertThrows(PhpIrLowerer.UnsupportedIr.class, () -> PhpIrLowerer.lowerB1File("unset.php", source, name -> false, name -> false));
    }

    /** Truffle's IR entry keeps its subset: the real WordPress files still go to the legacy frontend there. */
    @ParameterizedTest
    @ValueSource(strings = {"bench/gate1/wordpress/wp-includes/plugin.php", "bench/gate1/wordpress/wp-includes/class-wp-hook.php",
            "bench/k0/kernels.php"})
    void truffleLevelIsUnchanged(String file) throws Exception {
        byte[] source = Files.readAllBytes(ROOT.resolve(file));
        assertThrows(PhpIrLowerer.UnsupportedIr.class, () -> PhpIrLowerer.lowerFile(file, source));
        assertFalse(PhpIrLowerer.lowerB1File(file, source, name -> false, name -> false).functions().isEmpty());
    }
}
