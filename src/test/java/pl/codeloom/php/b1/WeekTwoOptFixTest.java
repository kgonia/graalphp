package pl.codeloom.php.b1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pl.codeloom.php.b1.runtime.B1Bytes;
import pl.codeloom.php.b1.runtime.B1Object;
import pl.codeloom.php.b1.runtime.B1RequestContext;
import pl.codeloom.php.ir.PhpIrLowerer;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regressions of the Pass 1 optimization review (fast frames, direct builtins, layouts, symbol ids), against
 * PHP 8.5.13's output, in fast mode and with every function on frames. */
class WeekTwoOptFixTest {
    private static final Path FIXTURES = Path.of("src/test/resources/b1");

    @ParameterizedTest
    @CsvSource({
            "w2d45_opt_operands,false", "w2d45_opt_operands,true",  // P0-2 array_unshift / __toString, P0-4 is_*()
            "w2d45_opt_assign,false", "w2d45_opt_assign,true",      // P0-3 assign TypeError, P0-5, P0-6 $o->$k
            "w2d45_opt_fiber,false", "w2d45_opt_fiber,true",        // P1-7 fast frames parked in a fiber
            "w2d45_opt_keys,false", "w2d45_opt_keys,true",          // P1-9 float keys, P2-10 named defaults
            "w2d45_opt_named,false", "w2d45_opt_named,true",        // re-review: $o->$k operands, chained builtin throws
            "w2d45_opt2_frames,false", "w2d45_opt2_frames,true",    // Pass 2: reused fast frames, sliced forwards
            "w2d45_opt2_objvars,false", "w2d45_opt2_objvars,true"}) // Pass 2: unindexed get_object_vars, defaults
    void matchesPhp(String fixture, boolean frames) throws Exception {
        String previous = System.getProperty("b1.slow");
        if (frames) System.setProperty("b1.slow", "true"); else System.clearProperty("b1.slow");
        try {
            var out = new ByteArrayOutputStream();
            var ctx = new B1RequestContext(out);
            B1Object.release(ctx, B1Compiler.runScript(ctx, FIXTURES.resolve(fixture + ".php"), 7500));
            ctx.shutdown();
            assertEquals(0, ctx.liveDestructibleCount());
            assertEquals(0, ctx.gcRootCount());
            String dir = FIXTURES.toAbsolutePath().toRealPath() + java.io.File.separator;
            String actual = out.toString(StandardCharsets.UTF_8).replace(dir, "/fixtures/");
            assertEquals(Files.readString(FIXTURES.resolve(fixture + ".stdout")), actual);
        } finally {
            if (previous == null) System.clearProperty("b1.slow"); else System.setProperty("b1.slow", previous);
        }
    }

    private static final String NAMED_WRITES = """
            <?php
            class Row { public $id; public $title; }
            function fill($row, $data) { foreach ($data as $name => $value) { $row->$name = $value; } return $row; }
            $row = fill(new Row(), ['id' => 1, 'title' => 'unique-reload-literal-%d']);
            $row->title = $row->title . '!';
            return $row->title;
            """;

    /** One request on its own hidden classes and symbol registry, checking the literal it produced. */
    private static void request(int nonce, List<WeakReference<Class<?>>> retired) {
        var ctx = new B1RequestContext(java.io.OutputStream.nullOutputStream());
        String source = NAMED_WRITES.formatted(nonce);
        var unit = PhpIrLowerer.lowerB1File("reload.php", source.getBytes(StandardCharsets.UTF_8), ctx::hasFunction, ctx::hasClass);
        var file = B1Compiler.compileB1(unit, ctx, 7500);
        Object result = file.entry().invoke0(ctx, null);
        for (var fn : file.entry().siblings) {
            assertTrue(fn.getClass().isHidden());
            retired.add(new WeakReference<>(fn.getClass()));
        }
        assertEquals("unique-reload-literal-" + nonce + "!", ((B1Bytes)result).toString());
        B1Object.release(ctx, result);
        ctx.shutdown();
        assertEquals(0, ctx.liveDestructibleCount());
        assertEquals(0, ctx.gcRootCount());
    }

    /** P0-1 / P1-8: $obj->$name writes through literal names keep no reference from shared strings to a class
     * layout, and literals are interned weakly: retired hidden classes and their literals are reclaimed. */
    @Test void namedWritesDoNotPinRetiredHiddenClasses() throws InterruptedException {
        var retired = new ArrayList<WeakReference<Class<?>>>();
        for (int i = 0; i < 5; i++) request(i, retired);
        assertTrue(retired.size() >= 10);
        int before = B1LiteralBlob.internedCount();
        for (int i = 0; i < 60 && retired.stream().anyMatch(ref -> ref.get() != null); i++) {
            var pressure = new byte[8][];
            for (int j = 0; j < pressure.length; j++) pressure[j] = new byte[256 * 1024];
            System.gc();
            java.lang.ref.Reference.reachabilityFence(pressure);
            Thread.sleep(20);
        }
        assertEquals(0, retired.stream().filter(ref -> ref.get() != null).count());
        assertTrue(B1LiteralBlob.internedCount() <= before, "retired literals stay interned");
    }

    /** P1-8: symbol ids belong to a code cache, or to a context without one; nothing accumulates process-wide. */
    @Test void symbolRegistriesAreScopedToTheirCacheOrContext() {
        var a = new B1RequestContext(java.io.OutputStream.nullOutputStream());
        var b = new B1RequestContext(java.io.OutputStream.nullOutputStream());
        assertNotSame(a.symbols(), b.symbols());
        a.symbols().of("only_in_a");
        assertEquals(0, b.symbols().size());
        var cache = new B1CodeCache();
        var c = new B1RequestContext(java.io.OutputStream.nullOutputStream());
        var d = new B1RequestContext(java.io.OutputStream.nullOutputStream());
        c.shareCode(cache);
        d.shareCode(cache);
        assertSame(cache.symbols(), c.symbols());
        assertSame(c.symbols(), d.symbols());
        assertThrows(IllegalStateException.class, () -> a.shareCode(cache));
    }
}
