package pl.codeloom.php.b1;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.b1.runtime.*;
import pl.codeloom.php.bytecode.TruffleIrAdapter;
import pl.codeloom.php.ir.PhpIrLowerer;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class WeekTwoPartBTest {
    private static final Path FIXTURES = Path.of("src/test/resources/b1");

    @Test void generatorContinuationsAndOutlining() throws Exception { fixture("w2b_generators",7500,220); }
    @Test void fibers() throws Exception { fixture("w2b_fibers",7500); }

    private static void fixture(String name,int... budgets) throws Exception {
        Path path = FIXTURES.resolve(name + ".php").toRealPath();
        byte[] oracle = Files.readAllBytes(FIXTURES.resolve(name + ".stdout"));
        for (int budget : budgets) {
            var out = new ByteArrayOutputStream();
            var ctx = new B1RequestContext(out);
            var unit = PhpIrLowerer.lowerFile(path.toString(),Files.readAllBytes(path));
            var compiled = B1Compiler.compile(unit,ctx,budget);
            var fragments = compiled.methods().stream().filter(m -> m.function().equals("operands")
                    && m.method().startsWith("fragmentBlock")).count();
            assertTrue(budget == 220 ? fragments > 1 : fragments == 0);
            assertTrue(compiled.maxMethodBytes() <= budget);
            ctx.runScript(compiled.entry());
            assertArrayEquals(oracle,out.toByteArray());
            assertEquals(0,ctx.liveDestructibleCount());
            assertEquals(0,ctx.gcRootCount());
        }
        var out = new ByteArrayOutputStream();
        try (var engine = Context.newBuilder("php").allowAllAccess(true).allowExperimentalOptions(true)
                .option("php.Parser","java").out(out).build()) {
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
        assertArrayEquals(oracle,out.toByteArray());
    }

    // The comparator is called from the C library on the PHP worker. No Java exception
    // may escape an FFM upcall: surface assertion failures after qsort has returned.
    static final class NativeSort extends B1Callable {
        final List<Thread> workers = new ArrayList<>();
        B1RequestContext context;
        Throwable failure;
        boolean suspended;
        NativeSort() { super(new boolean[0],new String[0]); }
        public int compare(MemorySegment a,MemorySegment b) {
            try {
                if (!suspended) {
                    suspended = true;
                    Thread worker = Thread.currentThread();
                    workers.add(worker);
                    assertFalse(worker.isVirtual());
                    assertEquals(29L,B1Fiber.suspend(context,17L));
                    assertSame(worker,Thread.currentThread());
                }
                return Integer.compare(a.reinterpret(4).get(JAVA_INT,0),b.reinterpret(4).get(JAVA_INT,0));
            } catch (Throwable ex) { failure = ex; return 0; }
        }
        @Override public Object invoke0(B1RequestContext ctx,B1Object self) {
            context = ctx; suspended = false;
            var linker = Linker.nativeLinker();
            try (var arena = Arena.ofConfined()) {
                var symbols = System.getProperty("os.name").startsWith("Windows")
                        ? SymbolLookup.libraryLookup("ucrtbase.dll",arena) : linker.defaultLookup();
                var qsort = linker.downcallHandle(symbols.findOrThrow("qsort"),
                        FunctionDescriptor.ofVoid(ADDRESS,JAVA_LONG,JAVA_LONG,ADDRESS));
                var compare = MethodHandles.lookup().findVirtual(NativeSort.class,"compare",
                        MethodType.methodType(int.class,MemorySegment.class,MemorySegment.class)).bindTo(this);
                var callback = linker.upcallStub(compare,FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS),arena);
                var data = arena.allocateFrom(JAVA_INT,3,1,2);
                qsort.invokeExact(data,3L,4L,callback);
                if (failure != null) throw new AssertionError(failure);
                assertArrayEquals(new int[]{1,2,3},data.toArray(JAVA_INT));
                return 42L;
            } catch (Throwable ex) { throw new AssertionError(ex); }
        }
        @Override public Object invoke1(B1RequestContext ctx,B1Object self,Object a) { throw new AssertionError(); }
        @Override public Object invoke2(B1RequestContext ctx,B1Object self,Object a,Object b) { throw new AssertionError(); }
        @Override public Object invoke(B1RequestContext ctx,B1Object self,Object[] args) { throw new AssertionError(); }
    }

    @Test @Timeout(value=30,threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
    void suspendsUnderNativeQsortReusesAndJoinsPlatformWorker() {
        var out = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(out);
        var sort = new NativeSort();
        ctx.install("native",sort,Map.of("native_sort",sort),Map.of());
        String php = """
                <?php function nested() { return native_sort(); }
                foreach ([1,2] as $i) {
                    $f = new Fiber('nested');
                    echo $f->start(), '/', $f->resume(29), '/', $f->getReturn(), "\\n";
                }
                """;
        var file = B1Compiler.compile(PhpIrLowerer.lowerFile("qsort.php",php.getBytes(StandardCharsets.UTF_8),
                ctx::hasFunction,ctx::hasClass),ctx);
        try {
            file.entry().invoke0(ctx,null);
            assertEquals("17//42\n17//42\n",out.toString(StandardCharsets.UTF_8));
            assertEquals(1,ctx.fiberThreadCount());
            assertEquals(2,sort.workers.size());
            assertSame(sort.workers.getFirst(),sort.workers.getLast());
            assertTrue(sort.workers.getFirst().isAlive());
        } finally { ctx.shutdown(); }
        assertFalse(sort.workers.getFirst().isAlive());
        assertEquals(0,ctx.liveDestructibleCount());
        assertEquals(0,ctx.gcRootCount());
    }

    @Test void wordPressGetPostsInventoryAndMeasuredExcerpt() throws Exception {
        Path wordpress = Path.of("target/bootshare/wordpress/wp-includes/class-wp-query.php");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(wordpress),"WordPress checkout required");
        String source = Files.readString(wordpress);
        int start = source.indexOf("public function get_posts()");
        String method = source.substring(start,source.indexOf("\n\t}",start) + 3);
        String prefix = "<?php class WP_Query { ";
        // Resolve external names for this lowering probe only. The first unsupported syntax
        // even with WordPress and extension declarations available is array(&$this).
        var first = assertThrows(PhpIrLowerer.UnsupportedIr.class,() -> probe(prefix + method + "}"));
        assertTrue(first.getMessage().startsWith("AST_ARRAY_ELEM"),first::getMessage);

        // Verify the inventory by neutralizing exactly these source forms in a diagnostic copy.
        // This copy is never executed or counted as compiling get_posts.
        String masked = method.replace("&$this )","$this )").replace("(bool)","").replace("(array)","")
                .replace(" - "," + ").replace(", OBJECT,",", 'OBJECT',")
                .replace("instanceof WP_Post_Type","!== null").replace("$this->posts ??=","$this->posts =");
        probe(prefix + masked + "}");

        int firstStatement = method.indexOf("$distinct ");
        int lastStatement = method.indexOf("// If true, forcibly turns off SQL_CALC_FOUND_ROWS");
        String excerpt = method.substring(firstStatement,lastStatement);
        String sqlOptions = prefix + "public function queryOptions($query_vars) { " + excerpt + " } }";
        var unit = probe(sqlOptions);
        for (int limit : new int[]{7500,220}) {
            var ctx = new B1RequestContext(new ByteArrayOutputStream());
            var file = B1Compiler.compile(unit,ctx,limit);
            assertTrue(file.maxMethodBytes() <= limit);
            if (limit == 220) assertTrue(file.outlinedHelperCount() > 1);
            ctx.shutdown();
        }
    }
    private static pl.codeloom.php.ir.IrUnit probe(String php) {
        return PhpIrLowerer.lowerFile("class-wp-query.php",php.getBytes(StandardCharsets.UTF_8),name -> true,name -> true);
    }

    @Test @Timeout(30) void isolatedBatchComparesBytesAndClassifies(@TempDir Path root) throws Exception {
        Path cases = Files.createDirectories(root.resolve("cases/Zend"));
        Path expected = Files.createDirectories(root.resolve("expected/Zend"));
        var manifest = List.of("Zend/ok.php","Zend/unsupported.php","Zend/diff.php","Zend/loop.php");
        Files.write(root.resolve("cases/cases.txt"),manifest);
        Files.write(root.resolve("expected/results.txt"),manifest.stream().map(n -> n + "\tran\t0").toList());
        Files.writeString(cases.resolve("ok.php"),"<?php echo 'x';");
        Files.writeString(cases.resolve("unsupported.php"),"<?php unknown_builtin();");
        Files.writeString(cases.resolve("diff.php"),"<?php echo 'x';");
        Files.writeString(cases.resolve("loop.php"),"<?php while (true) {}");
        for (String name : manifest) Files.writeString(root.resolve("expected/" + name + ".out"),name.contains("diff") ? "x\n" : "x");
        Path log = root.resolve("summary");
        String java = ProcessHandle.current().info().command().orElseThrow();
        var batch = new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),B1BatchMain.class.getName(),
                root.toString(),"--timeout=3","Zend").redirectOutput(log.toFile()).redirectErrorStream(true).start();
        assertEquals(0,batch.waitFor(),() -> log.toString());
        assertTrue(Files.readString(log).contains("total pass=1 unsupported=1 diff=1 error=0 timeout=1"),() -> log.toString());
    }

    @Test @Timeout(30) void opusAuditGeneratorAndFiberRegressions() {
        // 1. Cyclic yield from throws instead of hanging
        // 2. Spilled call result across yield inside loop is freed before next statement
        // 3. Generator and Fiber forbid dynamic properties
        // 4. Sent value is not leaked when ClosedGeneratorException is injected
        // 5. RUNNING generator inside suspended Fiber does not double-count frame in cycle GC
        String php = """
                <?php
                class Track {
                    public $name;
                    function __construct($n) { $this->name = $n; }
                    function __destruct() { echo "drop:", $this->name, "\\n"; }
                }
                function mk($n) { return new Track($n); }
                function cycA($h) { yield from $h->b; }
                function cycB($h) { yield from $h->a; }
                $h = new stdClass();
                $h->a = cycA($h);
                $h->b = cycB($h);
                try {
                    $h->a->next();
                    $h->b->next();
                    $h->b->current();
                } catch (Error $e) {
                    echo "cyc:", $e->getMessage(), "\\n";
                }
                unset($h);

                function spillLoop() {
                    $i = 0;
                    while ($i < 2) {
                        $s = mk('s' . $i) === (yield $i);
                        echo "after:", $i, "\\n";
                        $i = $i + 1;
                    }
                }
                $g = spillLoop();
                $g->rewind();
                $g->send(0);
                $g->send(1);
                unset($g);

                try {
                    $g2 = spillLoop();
                    $g2->prop = 1;
                } catch (Error $e) {
                    echo "gdyn:", $e->getMessage(), "\\n";
                }
                unset($g2);
                try {
                    $f = new Fiber(static function () {});
                    $f->prop = 1;
                } catch (Error $e) {
                    echo "fdyn:", $e->getMessage(), "\\n";
                }
                unset($f);

                function deadInner() { throw new Exception('x'); yield 1; }
                function outerDead($in) { yield from $in; }
                $in = deadInner();
                try { $in->next(); } catch (Exception $e) {}
                $out = outerDead($in);
                try { $out->send(mk('sent_leak')); } catch (Throwable $e) { echo "sent_err\\n"; }
                unset($out, $in);

                $live = mk('live_fiber_gen');
                function genInFiber($v) {
                    $local = $v;
                    Fiber::suspend('paused');
                    yield $local->name;
                }
                $f2 = new Fiber(static function () use ($live) {
                    $gg = genInFiber($live);
                    return $gg->current();
                });
                $f2->start();
                gc_collect_cycles();
                $f2->resume();
                echo "res:", $f2->getReturn(), "\\n";
                unset($f2, $live);
                """;
        var out = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(out);
        var file = B1Compiler.compile(PhpIrLowerer.lowerFile("w2b_audit.php", php.getBytes(StandardCharsets.UTF_8)), ctx);
        ctx.runScript(file.entry());
        String actual = out.toString(StandardCharsets.UTF_8);
        assertTrue(actual.contains("cyc:Impossible to yield from the Generator being currently run\n"), actual);
        assertTrue(actual.contains("drop:s0\nafter:0\ndrop:s1\nafter:1\n"), actual);
        assertTrue(actual.contains("gdyn:Cannot create dynamic property Generator::$prop\n"), actual);
        assertTrue(actual.contains("fdyn:Cannot create dynamic property Fiber::$prop\n"), actual);
        assertTrue(actual.contains("drop:sent_leak\nsent_err\n") || actual.contains("sent_err\ndrop:sent_leak\n"), actual);
        assertTrue(actual.contains("res:live_fiber_gen\ndrop:live_fiber_gen\n"), actual);
        assertEquals(0, ctx.liveDestructibleCount());
        assertEquals(0, ctx.gcRootCount());
    }
}
