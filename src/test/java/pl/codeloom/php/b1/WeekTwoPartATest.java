package pl.codeloom.php.b1;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.b1.runtime.*;
import pl.codeloom.php.bytecode.TruffleIrAdapter;
import pl.codeloom.php.ir.PhpIrLowerer;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WeekTwoPartATest {
    private static final Path D17 = Path.of("bench/b1-precheck/probe-b/php-ref/d17_fixture.php");
    private static final Path LARGE = Path.of("src/test/resources/compile/large-function.php");
    private static final Path ORACLES = Path.of("src/test/resources/b1");

    @Test void d17TranscriptAndShutdown() throws Exception {
        byte[] oracle = Files.readAllBytes(ORACLES.resolve("d17_fixture.stdout"));
        assertEquals(556,oracle.length);
        var out = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(out);
        var file = B1Compiler.compile(D17,ctx);
        ctx.runScript(file.entry());
        assertArrayEquals(oracle,out.toByteArray());
        assertEquals(0,ctx.liveDestructibleCount());
        assertEquals(0,ctx.gcRootCount());

        // Truffle currently defers cyclic destructors to request shutdown (005/036).
        var truffle = truffle(D17);
        assertEquals(nonCycle(new String(oracle,StandardCharsets.UTF_8)),nonCycle(new String(truffle,StandardCharsets.UTF_8)));
    }

    private static List<String> nonCycle(String transcript) {
        return transcript.lines().filter(s -> !s.startsWith("cycle_dtor:") && !s.startsWith("gc_collect_")).toList();
    }

    @Test void largeFunctionOutlinesAtProductionBudget() throws Exception {
        byte[] expected = Files.readAllBytes(ORACLES.resolve("large-function.stdout"));
        var out = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(out);
        var file = B1Compiler.compile(LARGE,ctx);
        long helpers = file.methods().stream().filter(m -> m.function().equals("Query::sql") && m.method().startsWith("fragment")).count();
        assertTrue(helpers > 0);
        assertEquals(helpers,file.outlinedHelperCount());
        for (var method : file.methods()) assertTrue(method.bytes() <= 7500,method::toString);
        ctx.runScript(file.entry());
        assertEquals(0,ctx.liveDestructibleCount());
        assertEquals(0,ctx.gcRootCount());
        assertArrayEquals(expected,out.toByteArray());
        assertArrayEquals(expected,truffle(LARGE));
    }

    @Test void collectorRechecksReferencesAfterResurrection() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(out);
        ctx.runScript(B1Compiler.compile(ORACLES.resolve("w2_gc.php"),ctx).entry());
        assertArrayEquals(Files.readAllBytes(ORACLES.resolve("w2_gc.stdout")),out.toByteArray());
        assertEquals(0,ctx.gcRootCount());
    }

    @Test void releasedCandidatesLeaveTheBufferBeforeCollection() {
        var ctx = new B1RequestContext(java.io.OutputStream.nullOutputStream());
        var file = compile("roots.php","""
                <?php
                function discard($value) { $alias = $value; unset($alias, $value); }
                discard([1]); discard(new stdClass()); discard(function () {});
                """,ctx);
        file.entry().invoke0(ctx,null);
        assertEquals(0,ctx.gcRootCount());
        ctx.shutdown();
    }

    @Test void boundClosureAndTypedStaticPropertyOnBothBackends() throws Exception {
        var script = ORACLES.resolve("w2_bound_closure.php");
        byte[] expected = Files.readAllBytes(ORACLES.resolve("w2_bound_closure.stdout"));
        var out = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(out);
        ctx.runScript(B1Compiler.compile(script,ctx).entry());
        assertArrayEquals(expected,out.toByteArray());
        assertArrayEquals(expected,truffle(script));
    }

    private static byte[] truffle(Path script) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var engine = Context.newBuilder("php").allowAllAccess(true).allowExperimentalOptions(true)
                .option("php.Parser","java").out(out).build()) {
            Path path = script.toRealPath();
            engine.eval(Source.newBuilder("php",path.toFile()).build());
            engine.enter();
            try {
                var ctx = PhpContext.get(null);
                var counts = TruffleIrAdapter.counts(ctx.getLanguage(),path.toString());
                assertTrue(counts.ir() > 0);
                assertEquals(0,counts.fallback());
                ctx.endScript();
                assertEquals(0,ctx.liveDestructibleCount());
            } finally { engine.leave(); }
        }
        return out.toByteArray();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"arrays","callbacks","clear","gc","globals","numeric",
            "props","repeat","strict","threshold","callable_fatal"})
    void auditMatchesPhp(String name) throws Exception {
        String stem = "w2_audit_" + name;
        var path = ORACLES.resolve(stem + ".php");
        var out = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(out);
        String file = "/fixtures/" + path.getFileName();
        byte[] expected = Files.readAllBytes(ORACLES.resolve(stem + ".stdout"));
        if (name.equals("callable_fatal")) {
            var fatal = assertThrows(pl.codeloom.php.ir.PhpCompileFatal.class,
                    () -> compile(file,Files.readString(path),ctx));
            assertEquals(new String(expected,StandardCharsets.UTF_8),fatal.diagnostic());
            assertEquals(0,ctx.lintedClasses());
            return;
        }
        ctx.runScript(compile(file,Files.readString(path),ctx).entry());
        assertArrayEquals(expected,out.toByteArray(),out::toString);
        assertEquals(0,ctx.gcRootCount());
        assertEquals(0,ctx.liveDestructibleCount());
        if (name.equals("strict")) {
            out.reset();
            try (var engine = Context.newBuilder("php").allowAllAccess(true).out(out).build()) {
                engine.initialize("php");
                engine.enter();
                try {
                    var runtime = PhpContext.get(null);
                    String php = Files.readString(path);
                    var source = com.oracle.truffle.api.source.Source.newBuilder("php",php,file).build();
                    var ir = PhpIrLowerer.lowerFile(file,Files.readAllBytes(path));
                    var root = TruffleIrAdapter.translate(runtime.getLanguage(),source,ir,java.util.Map.of(),
                            List.of(),java.util.Set.of(),false);
                    root.getCallTarget().call(new Object[pl.codeloom.php.runtime.PhpFunction.ARG_FIRST]);
                    runtime.endScript();
                } finally { engine.leave(); }
            }
            assertArrayEquals(expected,out.toByteArray());
            String actual = new String(truffle(path),StandardCharsets.UTF_8);
            assertEquals(new String(expected,StandardCharsets.UTF_8).replace(file,path.toRealPath().toString()),actual);
        }
    }

    private static final String PLUGIN = """
            <?php
            class C { public static int $counter = 0; public static array $values = [1, 2]; }
            function mutate($delta) {
                global $data, $x;
                $alias = &$x;
                $alias += $delta;
                $data['nested'][0] = $x;
                foreach ($data['nested'] as $v) {
                    foreach ($data['nested'] as $w) echo "$v/$w ";
                }
                C::$counter++;
                C::$values[0] = $delta;
                echo next($data['nested']), ":", C::$counter, ";";
            }
            """;

    private static B1Compiler.CompiledFile compile(String name,String source,B1RequestContext ctx) {
        return B1Compiler.compile(PhpIrLowerer.lowerFile(name,source.getBytes(StandardCharsets.UTF_8),
                ctx::hasFunction,ctx::hasClass),ctx);
    }

    @Test void restoreSharesFrozenArraysAndIsolatesCursorsReferencesAndStatics() throws Exception {
        var seed = new B1RequestContext(java.io.OutputStream.nullOutputStream());
        compile("plugin.php",PLUGIN,seed);
        var setup = compile("setup.php","<?php $x=1; $data=['nested'=>[1,2,3]]; C::$counter=8;",seed);
        setup.entry().invoke0(seed,null);
        var snapshot = seed.freezePureData();

        var outA = new ByteArrayOutputStream();
        var outB = new ByteArrayOutputStream();
        var a = new B1RequestContext(outA);
        var b = new B1RequestContext(outB);
        compile("plugin.php",PLUGIN,a);
        a.restoreFrom(snapshot);
        b.restoreFrom(snapshot);
        compile("plugin.php",PLUGIN,b);
        assertSame(a.findGlobal("data").get(),b.findGlobal("data").get());
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CyclicBarrier(2);
            var first = workers.submit(() -> {
                start.await();
                var mutate = B1RequestContext.function(a,"mutate");
                mutate.invoke1(a,null,2L);
                return mutate.invoke1(a,null,1L);
            });
            var second = workers.submit(() -> {
                start.await();
                return B1RequestContext.function(b,"mutate").invoke1(b,null,6L);
            });
            first.get();
            second.get();
        }
        assertEquals("3/3 3/2 3/3 2/3 2/2 2/3 3/3 3/2 3/3 2:9;4/4 4/2 4/3 2/4 2/2 2/3 3/4 3/2 3/3 3:10;",outA.toString());
        assertEquals("7/7 7/2 7/3 2/7 2/2 2/3 3/7 3/2 3/3 2:9;",outB.toString());
        assertNotSame(a.findGlobal("data").get(),b.findGlobal("data").get());
        assertEquals(4L,a.findGlobal("x").get());
        assertEquals(7L,b.findGlobal("x").get());
        var fresh = new B1RequestContext(java.io.OutputStream.nullOutputStream());
        compile("plugin.php",PLUGIN,fresh);
        fresh.restoreFrom(snapshot);
        assertEquals(1L,((B1Array)((B1Array)fresh.findGlobal("data").get()).get("nested")).get(0L));
        assertEquals(1L,fresh.findGlobal("x").get());
        assertEquals(1L,((B1Array)fresh.staticProperty("c","values").get()).get(0L));
        assertEquals(1L,((B1Array)a.staticProperty("c","values").get()).get(0L));
        assertEquals(6L,((B1Array)b.staticProperty("c","values").get()).get(0L));
        assertEquals(8L,fresh.staticProperty("c","counter").get());
        for (var ctx : List.of(a,b,fresh,seed)) {
            ctx.shutdown();
            assertEquals(0,ctx.liveDestructibleCount());
            assertEquals(0,ctx.gcRootCount());
        }
    }

    @Test void snapshotRejectsObjectsAndLiveAliases() {
        for (String source : List.of("$x = new stdClass();","$x = [1]; $alias = &$x[0];")) {
            var ctx = new B1RequestContext(java.io.OutputStream.nullOutputStream());
            compile("impure.php","<?php " + source,ctx).entry().invoke0(ctx,null);
            assertThrows(IllegalArgumentException.class,ctx::freezePureData);
            ctx.shutdown();
        }
    }

    private static void retire(List<WeakReference<Class<?>>> retired) {
        var ctx = new B1RequestContext(java.io.OutputStream.nullOutputStream());
        var plugin = compile("plugin.php",PLUGIN,ctx);
        ctx.runScript(plugin.entry());
        for (var fn : plugin.entry().siblings) {
            assertTrue(fn.getClass().isHidden());
            retired.add(new WeakReference<>(fn.getClass()));
        }
    }

    @Test void reloadReclaimsEveryRetiredHiddenClass() throws InterruptedException {
        var retired = new ArrayList<WeakReference<Class<?>>>();
        for (int i = 0; i < 5; i++) retire(retired);
        assertTrue(retired.size() >= 10);
        for (int i = 0; i < 60 && retired.stream().anyMatch(ref -> ref.get() != null); i++) {
            var pressure = new byte[8][];
            for (int j = 0; j < pressure.length; j++) pressure[j] = new byte[256 * 1024];
            System.gc();
            java.lang.ref.Reference.reachabilityFence(pressure);
            Thread.sleep(20);
        }
        assertEquals(0,retired.stream().filter(ref -> ref.get() != null).count());
    }
}
