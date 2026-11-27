package pl.codeloom.php.b1;

import org.junit.jupiter.api.Test;
import pl.codeloom.php.b1.runtime.B1Frame;
import pl.codeloom.php.b1.runtime.B1Object;
import pl.codeloom.php.b1.runtime.B1RequestContext;
import pl.codeloom.php.ir.IrUnit;
import pl.codeloom.php.ir.PhpIrLowerer;

import java.io.ByteArrayOutputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pass 3: leaf calls without a frame, float CVs in JVM doubles, and the tighter emitted bytecode (fall-through
 * block order, merged exception ranges, lazy line stores, no counting of scalar temporaries). The PHP fixtures'
 * .stdout files are PHP 8.5.13's output; each mode must produce it byte for byte. */
class WeekTwoOptPassThreeTest {
    private static final Path FIXTURES = Path.of("src/test/resources/b1");
    private static final String[] MODES = {"", "b1.noLeaf", "b1.noDoubles", "b1.slow"};

    private static String run(Path file) {
        var out = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(out);
        B1Object.release(ctx, B1Compiler.runScript(ctx, file, 7500));
        ctx.shutdown();
        assertEquals(0, ctx.liveDestructibleCount());
        assertEquals(0, ctx.gcRootCount());
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void fixture(String name) throws Exception {
        Path file = FIXTURES.resolve(name + ".php").toAbsolutePath();
        String expected = Files.readString(FIXTURES.resolve(name + ".stdout")).replace("/fixtures/" + name + ".php", file.toString());
        for (String mode : MODES) {
            if (!mode.isEmpty()) System.setProperty(mode, "true");
            try { assertEquals(expected, run(file), name + " with " + (mode.isEmpty() ? "every optimization" : mode)); }
            finally { if (!mode.isEmpty()) System.clearProperty(mode); }
        }
    }

    /** Diagnostics, errors and traces raised inside leaf calls (which build their frame on demand), destructors
     * run from a leaf, private members, discarded results and the calls that take the ordinary path. */
    @Test void leafCallsBehaveLikeFramedCalls() throws Exception { fixture("w2d45_opt3_leaf"); }

    /** Float CVs: NaN comparisons, signed zero, infinities, int operands, division by zero (DivisionByZeroError at
     * its line) and mixed operands through the runtime (numeric strings, null, bool, TypeErrors). */
    @Test void floatLocalsKeepPhpFloatSemantics() throws Exception { fixture("w2d45_opt3_float"); }

    /** Neon review: op= that is no float arithmetic (.= %= &= |= ^= <<= >>=), a left CV that its right operand
     * assigns (+ - * / -= == < === and a ternary), and by-value captures of a possibly undefined CV. */
    @Test void floatLocalsExcludeNonFloatUpdatesAndReadInPhpOrder() throws Exception { fixture("w2d45_opt3_neon"); }

    private static final String SOURCE = """
            <?php
            function leaf($a, $b) { return $a + $b; }
            function caller($a) { return leaf($a, 1) + strlen('x'); }
            function series($n) {
                $sum = 0.0;
                $sign = 1.0;
                for ($i = 0; $i < $n; $i++) { $sum += $sign / (2 * $i + 1); $sign = -$sign; }
                return $sum;
            }
            function statements($a) {
                $x = $a + 1;
                $y = $x * 2;
                $z = $y - $x;
                $w = $z . 'w';
                $v = strlen($w);
                return $v + $x + $y + $z;
            }
            """;

    private static List<B1Cfg> functions() {
        IrUnit unit = PhpIrLowerer.lowerB1File("pass3.php", SOURCE.getBytes(StandardCharsets.UTF_8), name -> false, name -> false);
        return unit.functions().stream().filter(f -> !f.fileBody()).map(B1CfgLowerer::lower).toList();
    }
    private static B1Cfg function(String name) {
        return functions().stream().filter(c -> c.function().name().equals(name)).findFirst().orElseThrow();
    }
    private static java.lang.classfile.attribute.CodeAttribute method(B1Cfg cfg, String method) {
        byte[] bytes = B1ClassFileEmitter.measured(cfg, 7500).bytes();
        return ClassFile.of().parse(bytes).methods().stream().filter(m -> m.methodName().stringValue().equals(method))
                .findFirst().flatMap(m -> m.findAttribute(Attributes.code())).orElse(null);
    }

    /** A function calling no PHP code gets a leaf body, padded past C2's FreqInlineSize so that it compiles as its
     * own unit; a function with a PHP call does not. */
    @Test void leafBodiesAreSeparateCompilationUnits() {
        var leaf = method(function("leaf"), "runLeaf");
        assertNotNull(leaf);
        assertTrue(leaf.codeLength() >= B1ClassFileEmitter.LEAF_INLINE_LIMIT, "leaf body " + leaf.codeLength() + " B");
        assertNull(method(function("caller"), "runLeaf"));
        // The leaf entry neither enters a pooled frame nor binds arguments through one.
        var called = leaf.elementStream().filter(e -> e instanceof InvokeInstruction).map(e -> ((InvokeInstruction) e).name().stringValue()).toList();
        assertTrue(called.contains("enterLeaf"));
        assertFalse(called.contains("enterFast"));
        assertFalse(called.contains("bindFast"));
    }

    /** $sum and $sign live in JVM doubles: the loop body adds and negates them with JVM double operations (the
     * division by the int expression still goes through the runtime, which returns a float). */
    @Test void floatLocalsUseJvmDoubleArithmetic() {
        var run = method(function("series"), "run");
        var ops = run.elementStream().filter(e -> e instanceof java.lang.classfile.Instruction)
                .map(e -> ((java.lang.classfile.Instruction) e).opcode()).toList();
        assertTrue(ops.contains(Opcode.DADD), "dadd");
        assertTrue(ops.contains(Opcode.DMUL), "dmul (negation)");
        assertTrue(ops.contains(Opcode.DSTORE) || ops.contains(Opcode.DSTORE_0) || ops.contains(Opcode.DSTORE_1)
                || ops.contains(Opcode.DSTORE_2) || ops.contains(Opcode.DSTORE_3), "dstore");
    }

    /** Adjacent blocks unwinding to the same handler share one exception-table range, and straight-line
     * statements fall through to each other instead of jumping. */
    @Test void straightLineCodeSharesExceptionRangesAndFallsThrough() {
        B1Cfg cfg = function("statements");
        var run = method(cfg, "run");
        long ranges = run.elementStream().filter(e -> e instanceof ExceptionCatch).count();
        assertTrue(ranges * 2 < cfg.blocks().size(), ranges + " ranges for " + cfg.blocks().size() + " blocks");
        // What jumps remains: the two of the parameter binding, one per handler stub (astore pending; goto), and
        // the exceptional cleanup's jump to the Leave block (which follows the return statement instead).
        long handlers = run.elementStream().filter(e -> e instanceof ExceptionCatch).map(e -> ((ExceptionCatch) e).handler()).distinct().count();
        long jumps = run.elementStream().filter(e -> e instanceof java.lang.classfile.Instruction i && i.opcode() == Opcode.GOTO).count();
        assertEquals(2 + handlers + 1, jumps, "no statement ends with a jump");
    }

    /** Leaf calls that built their frame (an error, a destructor, a warning) return it to the pool like any fast
     * frame: after the fixture every pooled frame is retired. */
    @Test void materializedLeafFramesLeaveThePoolClean() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(out);
        B1Object.release(ctx, B1Compiler.runScript(ctx, FIXTURES.resolve("w2d45_opt3_leaf.php").toAbsolutePath(), 7500));
        var poolField = B1RequestContext.class.getDeclaredField("fastFrames");
        poolField.setAccessible(true);
        var depth = B1RequestContext.class.getDeclaredField("fastDepth");
        depth.setAccessible(true);
        assertEquals(0, depth.getInt(ctx));
        int pooled = 0;
        for (B1Frame frame : (B1Frame[]) poolField.get(ctx)) {
            if (frame == null) continue;
            pooled++;
            assertNull(frame.file);
            assertNull(frame.function);
            assertNull(frame.m0);
            assertNull(frame.m1);
        }
        assertTrue(pooled >= 1);
        var leaf = B1RequestContext.class.getDeclaredField("leaf");
        leaf.setAccessible(true);
        assertNull(leaf.get(ctx), "no leaf call left deferred");
        ctx.shutdown();
    }
}
