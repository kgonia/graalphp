package pl.codeloom.php.b1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.codeloom.php.b1.runtime.B1Frame;
import pl.codeloom.php.b1.runtime.B1Object;
import pl.codeloom.php.b1.runtime.B1RequestContext;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Regressions of the Pass 2 review: frozen data built by get_object_vars, and pooled fast frames. */
class WeekTwoOptPassTwoFixTest {

    /** P0: get_object_vars() builds an unindexed array. Frozen request data (a snapshot's global, a define()
     * constant) is read by concurrent requests, so freezing must index it first; a probe of a frozen
     * unindexed array would race (and now throws). Non-empty and empty results, never probed before the freeze. */
    @Test void frozenObjectVarsAreIndexedBeforeConcurrentRequestsProbeThem(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("seed.php"), """
                <?php
                $o = new stdClass(); $o->a = 1; $o->{'7'} = 'seven'; $o->b = [10, 20];
                $vars = get_object_vars($o);
                $none = get_object_vars(new stdClass());
                define('VARS', get_object_vars($o));
                define('NONE', get_object_vars(new stdClass()));
                unset($o);
                """);
        Path request = dir.resolve("request.php");
        Files.writeString(request, """
                <?php
                $out = '';
                for ($i = 0; $i < 200; $i++) {
                    $out = $vars['a'] . $vars[7] . $vars['b'][1] . (isset($vars['zz']) ? 'y' : 'n') . count($none)
                        . (isset($none['a']) ? 'y' : 'n') . VARS['a'] . VARS['7'] . VARS['b'][0]
                        . (array_key_exists('b', VARS) ? 'y' : 'n') . count(NONE) . (isset(NONE[0]) ? 'y' : 'n');
                }
                $copy = $vars;
                $copy['c'] = 3;
                unset($copy['a']);
                $none[] = 'x';
                echo $out, ' ', implode(',', array_keys($copy)), ' ', count($vars), ' ', count($none), "\\n";
                """);
        var seed = new B1RequestContext(java.io.OutputStream.nullOutputStream());
        B1Object.release(seed, B1Compiler.runScript(seed, dir.resolve("seed.php"), 7500));
        var snapshot = seed.freezePureData();
        seed.shutdown();

        var pool = Executors.newFixedThreadPool(2);
        try {
            var runs = new ArrayList<Future<String>>();
            for (int t = 0; t < 2; t++) {
                Callable<String> run = () -> {
                    var out = new ByteArrayOutputStream();
                    var ctx = new B1RequestContext(out);
                    for (int r = 0; r < 3; r++) {
                        ctx.restoreFrom(snapshot);
                        B1Object.release(ctx, B1Compiler.runScript(ctx, request, 7500));
                    }
                    ctx.shutdown();
                    assertEquals(0, ctx.liveDestructibleCount());
                    assertEquals(0, ctx.gcRootCount());
                    return out.toString(StandardCharsets.UTF_8);
                };
                runs.add(pool.submit(run));
            }
            String line = "1seven20n0n1seven10y0n 7,b,c 3 1\n";
            for (var run : runs) assertEquals(line.repeat(3), run.get());
        } finally { pool.shutdownNow(); }
    }

    /** P1: a finally region in a fast function parks the pending throw and its exits on the (pooled) frame.
     * Leaving the call must drop them, so a reused frame starts clean and the pool retains no PHP value. */
    @Test void pooledFramesDropFinallyStateWhenTheyLeave() throws Exception {
        String previous = System.getProperty("b1.slow");
        System.clearProperty("b1.slow");
        try {
            var out = new ByteArrayOutputStream();
            var ctx = new B1RequestContext(out);
            var unit = pl.codeloom.php.ir.PhpIrLowerer.lowerB1File("finally.php", """
                    <?php
                    function f($n) { try { if ($n) throw new Exception("e$n"); return "ok"; } finally { echo "finally $n\\n"; } }
                    function g($n) { try { return f($n); } catch (Exception $e) { return "caught " . $e->getMessage(); } }
                    echo g(1), "\\n", g(0), "\\n", g(2), "\\n";
                    """.getBytes(StandardCharsets.UTF_8), ctx::hasFunction, ctx::hasClass);
            var file = B1Compiler.compileB1(unit, ctx, 7500);
            B1Object.release(ctx, file.entry().invoke0(ctx, null));
            assertEquals("finally 1\ncaught e1\nfinally 0\nok\nfinally 2\ncaught e2\n", out.toString(StandardCharsets.UTF_8));

            var poolField = B1RequestContext.class.getDeclaredField("fastFrames");
            poolField.setAccessible(true);
            var pending = B1Frame.class.getDeclaredField("pending");
            var suspended = B1Frame.class.getDeclaredField("suspended");
            var result = B1Frame.class.getDeclaredField("result");
            suspended.setAccessible(true);
            int pooled = 0;
            for (B1Frame frame : (B1Frame[]) poolField.get(ctx)) {
                if (frame == null) continue;
                pooled++;
                assertNull(pending.get(frame), "pending throw retained by a pooled frame");
                assertNull(suspended.get(frame), "finally exits retained by a pooled frame");
                assertNull(result.get(frame));
                assertNull(frame.file);
                assertNull(frame.function);
            }
            assertTrue(pooled >= 2, "f and g ran on pooled fast frames");
            ctx.shutdown();
            assertEquals(0, ctx.liveDestructibleCount());
            assertEquals(0, ctx.gcRootCount());
        } finally {
            if (previous != null) System.setProperty("b1.slow", previous);
        }
    }
}
