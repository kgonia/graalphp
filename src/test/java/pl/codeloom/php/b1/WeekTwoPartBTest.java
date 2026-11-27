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

    @Test @Timeout(30) void opusAuditGeneratorAndFiberRegressions() throws Exception {
        // 1. Suspended-chain cyclic yield from + child RUNNING leaves parent open/valid
        // 2. Intra-statement & across-yield D17 timing: eat(yield), 1 === mk(yield) + step(),
        //    mk('lhs') === (yield) + step(), mk('c') && (yield), mk('t') ? (yield) : 2, eatArr([mk('e'), yield])
        // 3. Generator & Fiber forbid dynamic props (write, by-ref, ++, read, isset)
        // 4. Sent value is not leaked when rewound outer generator's delegate is aborted before send()
        // 5. RUNNING generator inside suspended Fiber in a GC root cycle does not double-decrement $box
        String php = """
                <?php
                class Track {
                    public $name;
                    public $f;
                    function __construct($n) { $this->name = $n; }
                    function __destruct() { echo "drop:", $this->name, "\\n"; }
                }
                function mk($n) { return new Track($n); }

                // 1a. Suspended 2-node cyclic yield from (exercising g.leaf() == this)
                function cyc1($h) { yield 'a'; yield from $h->b; }
                function cyc2($h) { yield from $h->a; }
                $h = new stdClass();
                $h->a = cyc1($h);
                $h->b = cyc2($h);
                $h->b->rewind(); // b suspends delegating to a (suspended at yield 'a')
                try {
                    $h->a->next(); // a runs, hits yield from $h->b where $h->b is SUSPENDED with leaf() == a
                } catch (Error $e) {
                    echo "cyc_susp:", $e->getMessage(), "\\n";
                }
                unset($h);

                // 1b. Child RUNNING does not close parent delegating generator, and throw() into RUNNING does not leak $ex
                function runInner($h) {
                    yield 1;
                    try { $h->outer->next(); } catch (Error $e) { echo "run_err:", $e->getMessage(), "\\n"; }
                    $ex = new Exception("run_throw");
                    $ex->box = mk("ex_box");
                    try { $h->outer->throw($ex); } catch (Error $e) { echo "run_throw_err:", $e->getMessage(), "\\n"; }
                    unset($ex);
                    echo "after_ex_unset\\n";
                    $ex2 = new Exception("self_throw");
                    $ex2->box = mk("self_ex_box");
                    try { $h->inner->throw($ex2); } catch (Error $e) { echo "self_throw_err:", $e->getMessage(), "\\n"; }
                    unset($ex2);
                    echo "after_ex2_unset\\n";
                    yield 2;
                }
                function runOuter($in) { yield from $in; }
                $rh = new stdClass();
                $rin = runInner($rh);
                $rh->inner = $rin;
                $rh->outer = runOuter($rin);
                $rh->outer->rewind();
                $rin->next();
                echo "outer_valid:", ($rh->outer->valid() ? "true" : "false"), ":", $rh->outer->current(), "\\n";
                unset($rh, $rin);

                // 2. Intra-statement & across-yield D17 timing (exact PHP 8.5.13 parity)
                function eat($x) { echo "eat_in\\n"; $x = null; echo "eat_out\\n"; }
                function step() { echo "step\\n"; return 0; }
                function retInner() { yield 'in'; return mk('ret_eat'); }
                function eatYieldFrom() { eat(yield from retInner()); }
                $gy = eatYieldFrom();
                $gy->rewind();
                $gy->next();
                unset($gy);

                function gTiming() {
                    eat(yield 'eat');
                    $x = (mk('lhs') === (yield 'lhs')) + step();
                    $y = (1 === mk(yield 'rhs')) + step();
                    $z = mk('and_cond') && (yield 'and');
                    $w = mk('tern_cond') ? (yield 'tern') : 2;
                    eat([mk('arr_elem'), yield 'arr']);
                }
                $gt = gTiming();
                $gt->rewind();
                $gt->send(mk('sent_eat'));
                $gt->send(0);
                $gt->send('rhs_obj');
                echo "before_and_send\\n";
                $gt->send(true);
                echo "before_tern_send\\n";
                $gt->send(1);
                $gt->send(0);
                unset($gt);

                // 3. Generator and Fiber dynamic properties (write, by-ref, inc, isset)
                $g2 = gTiming();
                try { $g2->prop = 1; } catch (Error $e) { echo "gdyn_w:", $e->getMessage(), "\\n"; }
                try { $r = &$g2->prop; } catch (Error $e) { echo "gdyn_ref:", $e->getMessage(), "\\n"; }
                try { $g2->prop++; } catch (Error $e) { echo "gdyn_inc:", $e->getMessage(), "\\n"; }
                echo "gdyn_isset:", (isset($g2->prop) ? "1" : "0"), "\\n";
                unset($g2);
                $f = new Fiber(static function () {});
                try { $f->prop = 1; } catch (Error $e) { echo "fdyn_w:", $e->getMessage(), "\\n"; }
                try { $rf = &$f->prop; } catch (Error $e) { echo "fdyn_ref:", $e->getMessage(), "\\n"; }
                unset($f);

                // 4. Sent value not leaked when already-rewound outer's delegate aborts before send()
                function abortableInner() { yield 'first'; throw new Exception('boom'); }
                function outerAbort($in) { yield from $in; }
                $in = abortableInner();
                $out = outerAbort($in);
                $out->rewind(); // outer is now SUSPENDED delegating to $in
                try { $in->next(); } catch (Exception $e) {} // $in aborts directly
                try { $out->send(mk('sent_leak')); } catch (Throwable $e) { echo "sent_err:", $e->getMessage(), "\\n"; }
                unset($out, $in);

                // 5. RUNNING generator inside suspended Fiber in GC root cycle does NOT prematurely destroy $box
                function genInFiber($v) {
                    $local = $v;
                    Fiber::suspend('paused');
                    yield $local->name;
                }
                $box = mk('gc_box');
                $f2 = new Fiber(static function () use ($box) {
                    $gg = genInFiber($box);
                    return $gg->current();
                });
                $box->f = $f2; // cycle: $box -> $f2 -> closure/gen frame -> $box
                $f2->start();
                unset($f2); // puts $f2 in GC roots while $box still has external ref
                gc_collect_cycles();
                echo "after_gc\\n";
                $box->f->resume();
                echo "res:", $box->f->getReturn(), "\\n";
                $box->f = null;
                unset($box);
                """;
        var out = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(out);
        var file = B1Compiler.compile(PhpIrLowerer.lowerFile("w2b_audit.php", php.getBytes(StandardCharsets.UTF_8)), ctx);
        ctx.runScript(file.entry());
        String actual = out.toString(StandardCharsets.UTF_8);
        assertTrue(actual.contains("cyc_susp:Impossible to yield from the Generator being currently run\n"), actual);
        assertTrue(actual.contains("run_err:Cannot resume an already running generator\n"
                + "run_throw_err:Cannot resume an already running generator\n"
                + "drop:ex_box\nafter_ex_unset\n"
                + "self_throw_err:Cannot resume an already running generator\n"
                + "drop:self_ex_box\nafter_ex2_unset\n"
                + "outer_valid:true:2\n"), actual);
        assertTrue(actual.contains("eat_in\ndrop:ret_eat\neat_out\n"), actual);
        assertTrue(actual.contains("eat_in\neat_out\ndrop:sent_eat\n"), actual);
        assertTrue(actual.contains("drop:lhs\nstep\n"), actual);
        assertTrue(actual.contains("drop:rhs_obj\nstep\n"), actual);
        assertTrue(actual.contains("drop:and_cond\nbefore_and_send\n"), actual);
        assertTrue(actual.contains("drop:tern_cond\nbefore_tern_send\n"), actual);
        assertTrue(actual.contains("eat_in\ndrop:arr_elem\neat_out\n"), actual);
        assertTrue(actual.contains("gdyn_w:Cannot create dynamic property Generator::$prop\n"), actual);
        assertTrue(actual.contains("gdyn_ref:Cannot create dynamic property Generator::$prop\n"), actual);
        assertTrue(actual.contains("gdyn_inc:Cannot create dynamic property Generator::$prop\n"), actual);
        assertTrue(actual.contains("gdyn_isset:0\n"), actual);
        assertTrue(actual.contains("fdyn_w:Cannot create dynamic property Fiber::$prop\n"), actual);
        assertTrue(actual.contains("fdyn_ref:Cannot create dynamic property Fiber::$prop\n"), actual);
        assertTrue(actual.contains("sent_err:Generator yielded from aborted, no return value available\n"), actual);
        assertTrue(actual.contains("drop:sent_leak\n"), actual);
        assertTrue(actual.contains("after_gc\nres:gc_box\ndrop:gc_box\n"), actual);
        assertEquals(0, ctx.liveDestructibleCount());
        assertEquals(0, ctx.gcRootCount());

        // 6. Truffle & B1 parity on generator returning a destructible object (no double-refcount leak)
        String retPhp = """
                <?php
                class Box { function __destruct() { echo "drop\\n"; } }
                function g() { yield 1; return new Box(); }
                $g = g(); $g->rewind(); $g->next();
                echo "before\\n";
                unset($g);
                echo "after\\n";
                """;
        String expectedRet = "before\ndrop\nafter\n";
        var b1Out = new ByteArrayOutputStream();
        var b1Ctx = new B1RequestContext(b1Out);
        var b1File = B1Compiler.compile(PhpIrLowerer.lowerFile("w2b_gen_ret.php", retPhp.getBytes(StandardCharsets.UTF_8)), b1Ctx);
        b1Ctx.runScript(b1File.entry());
        assertEquals(expectedRet, b1Out.toString(StandardCharsets.UTF_8));
        assertEquals(0, b1Ctx.liveDestructibleCount());
        assertEquals(expectedRet, runTruffle("w2b_gen_ret.php", retPhp));
    }

    private static String runTruffle(String name, String code) throws Exception {
        Path script = Files.createTempFile("truffle_" + name + "_", ".php");
        try {
            Files.writeString(script, code, StandardCharsets.UTF_8);
            var out = new ByteArrayOutputStream();
            try (var engine = Context.newBuilder("php").allowAllAccess(true).allowExperimentalOptions(true)
                    .option("php.Parser", "java").out(out).err(out)
                    .arguments("php", new String[] { script.toString() }).build()) {
                engine.eval(Source.newBuilder("php", script.toFile()).build());
            }
            return out.toString(StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(script);
        }
    }
}
