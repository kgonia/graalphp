package pl.codeloom.php.b1;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.b1.runtime.B1RequestContext;
import pl.codeloom.php.bytecode.TruffleIrAdapter;
import pl.codeloom.php.ir.PhpIrLowerer;
import pl.codeloom.php.parser.Translator;
import pl.codeloom.php.parser.zend.ZendParser;
import pl.codeloom.php.runtime.PhpFunction;

import java.io.ByteArrayOutputStream;
import java.lang.classfile.*;
import java.lang.classfile.constantpool.ConstantDynamicEntry;
import java.lang.classfile.instruction.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class WeekOneKernelsTest {
    private static final Path FIXTURES = Path.of("src/test/resources/b1");

    @ParameterizedTest
    @ValueSource(strings = {"aget","hooks","post","eval_mega","sideblob","outline"})
    void differential(String name) throws Exception {
        differentialFixture("w1d35_" + name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"arrays","ownership","closures","contracts","eval_scope","finally_trace"})
    void auditRegression(String name) throws Exception {
        differentialFixture("audit_d35/" + name);
    }

    private void differentialFixture(String stem) throws Exception {
        String path = "/fixtures/" + stem + ".php";
        byte[] php = Files.readAllBytes(FIXTURES.resolve(stem + ".php"));
        byte[] expected = Files.readAllBytes(FIXTURES.resolve(stem + ".stdout"));
        var unit = PhpIrLowerer.lowerFile(path,php);
        for (int budget : new int[]{7500,220}) {
            var out = new ByteArrayOutputStream();
            var ctx = new B1RequestContext(out);
            if (stem.equals("audit_d35/closures")) {
                var fn = unit.functions().stream().filter(f -> f.captures().stream().anyMatch(c -> c.byRef())).findFirst().orElseThrow();
                var emission = B1ClassFileEmitter.measured(B1CfgLowerer.lower(fn),budget);
                assertEquals(budget == 220,emission.helpers() > 0);
            }
            var compiled = B1Compiler.compile(unit,ctx,budget);
            assertEquals(unit.functions().size(), compiled.lintedClasses());
            assertTrue(compiled.maxMethodBytes() <= budget);
            if (budget == 220) assertTrue(compiled.outlinedHelperCount() >= 2);
            else assertEquals(0,compiled.outlinedHelperCount());
            ctx.runScript(compiled.entry());
            assertTrue(ctx.largestMethod() <= budget);
            assertEquals(0,ctx.liveDestructibleCount());
            if (stem.equals("w1d35_eval_mega")) assertTrue(ctx.lintedClasses() > compiled.lintedClasses());
            assertArrayEquals(expected,out.toByteArray());
        }
        var out = new ByteArrayOutputStream();
        try (var context = Context.newBuilder("php").allowAllAccess(true).allowExperimentalOptions(true)
                .option("php.Parser","java").out(out).build()) {
            context.initialize("php"); context.enter();
            try {
                var ctx = PhpContext.get(null);
                var source = com.oracle.truffle.api.source.Source.newBuilder("php",new String(php,StandardCharsets.UTF_8),path).build();
                var parsed = ZendParser.parse(php);
                var root = new Translator(ctx.getLanguage(),path,source,false)
                        .translateFile(parsed.ast(),() -> ZendParser.parse(php).ast(),parsed.diagnostics());
                var main = TruffleIrAdapter.counts(ctx.getLanguage(),path);
                assertTrue(main.ir() > 0);
                assertEquals(0,main.fallback());
                root.getCallTarget().call(new Object[PhpFunction.ARG_FIRST]);
                if (stem.equals("w1d35_eval_mega")) {
                    var lines = new String(php, StandardCharsets.UTF_8).lines().toList();
                    for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("eval(")) {
                        String evaluated = path + "(" + (i + 1) + ") : eval()'d code";
                        var counts = TruffleIrAdapter.counts(ctx.getLanguage(),evaluated);
                        assertEquals(8,counts.ir(),evaluated);
                        assertEquals(0,counts.fallback(),evaluated);
                    }
                }
                ctx.endScript();
                assertEquals(0,ctx.liveDestructibleCount());
            } finally { context.leave(); }
        }
        assertArrayEquals(expected,out.toByteArray(),out::toString);
    }

    @Test void largeEvalLiteralsAndCapturesRespectCallerBudget() throws Exception {
        String path = "/fixtures/audit_d35/large_eval.php";
        var fixture = FIXTURES.resolve("audit_d35/large_eval.php");
        var unit = PhpIrLowerer.lowerFile(path,Files.readAllBytes(fixture));
        var expected = Files.readAllBytes(FIXTURES.resolve("audit_d35/large_eval.stdout"));
        for (int budget : new int[]{7500,220}) {
            var output = new ByteArrayOutputStream();
            var ctx = new B1RequestContext(output);
            var file = B1Compiler.compile(unit,ctx,budget);
            ctx.runScript(file.entry());
            assertTrue(ctx.lintedClasses() > file.lintedClasses());
            assertTrue(ctx.largestMethod() <= budget);
            assertArrayEquals(expected,output.toByteArray());
        }
        byte[] huge = ("<?php return '" + "a".repeat(70000) + "';").getBytes(StandardCharsets.US_ASCII);
        var body = PhpIrLowerer.lowerFile("text.php",huge).functions().getFirst();
        var bytes = B1ClassFileEmitter.measured(B1CfgLowerer.lower(body),7500);
        assertEquals(1,bytes.blobs().size());
        assertTrue(bytes.maxCode() < 256);
        assertTrue(ClassFile.of().parse(bytes.bytes()).constantPool().size() < 128);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fatal_function","fatal_class","fatal_class_duplicate","fatal_isset"})
    void compileFatalsBypassPhpCatch(String name) throws Exception {
        String stem = "audit_d35/" + name;
        String path = "/fixtures/" + stem + ".php";
        byte[] source = Files.readAllBytes(FIXTURES.resolve(stem + ".php"));
        var out = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(out);
        var fatal = assertThrows(pl.codeloom.php.ir.PhpCompileFatal.class,() -> {
            var file = B1Compiler.compile(PhpIrLowerer.lowerFile(path,source),ctx);
            ctx.runScript(file.entry());
        });
        out.write(fatal.diagnostic().getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(Files.readAllBytes(FIXTURES.resolve(stem + ".stdout")),out.toByteArray());
    }

    @Test void outlinesEvenWhenTheFirstPassExceedsTheJvmCodeLimit() {
        String source = "<?php function wide() { $n = 0; " + "$n += 1; ".repeat(3500) + "return $n; } echo wide();";
        var unit = PhpIrLowerer.lowerFile("wide.php",source.getBytes(StandardCharsets.UTF_8));
        var out = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(out);
        var compiled = B1Compiler.compile(unit,ctx);
        assertTrue(compiled.outlinedHelperCount() >= 2);
        assertTrue(compiled.maxMethodBytes() <= 7500);
        ctx.runScript(compiled.entry());
        assertEquals("3500",out.toString(StandardCharsets.US_ASCII));
    }

    @Test void literalDataIsLoadedByCondy() throws Exception {
        var file = FIXTURES.resolve("w1d35_sideblob.php");
        var unit = PhpIrLowerer.lowerFile(file.toString(),Files.readAllBytes(file));
        var emission = B1ClassFileEmitter.measured(B1CfgLowerer.lower(unit.functions().get(1)),7500);
        B1Lint.requireClean(emission.bytes(),B1Lint.Kind.EMITTED);
        assertEquals(1,emission.blobs().size());
        var model = ClassFile.of().parse(emission.bytes());
        assertTrue(model.methods().stream().noneMatch(m -> m.methodName().equalsString("<clinit>")));
        assertTrue(model.methods().stream().flatMap(m -> m.code().orElseThrow().elementStream())
                .anyMatch(e -> e instanceof ConstantInstruction.LoadConstantInstruction ldc
                        && ldc.constantEntry() instanceof ConstantDynamicEntry dynamic
                        && dynamic.asSymbol().bootstrapMethod().methodName().equals("bootstrap")
                        && dynamic.typeSymbol().displayName().equals("B1Array")));
        // The literal is no bytecode. (A leaf function's runLeaf is padded with unreachable code to C2's inline
        // limit, B1ClassFileEmitter.LEAF_INLINE_LIMIT; its executable part is smaller than run's.)
        assertTrue(model.methods().stream().filter(m -> !m.methodName().equalsString("runLeaf"))
                .mapToInt(m -> m.findAttribute(java.lang.classfile.Attributes.code()).orElseThrow().codeLength()).max().orElseThrow() < 256);
    }

    @Test void runtimeDispatchAndSliceFusionAreLintClean() throws Exception {
        Path runtime = Path.of(B1RequestContext.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("pl/codeloom/php/b1/runtime");
        try (var files = Files.walk(runtime)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList())
                B1Lint.requireClean(Files.readAllBytes(file),B1Lint.Kind.RUNTIME);
        }
        var source = "<?php function apply($fn,$args,$n) { return call_user_func_array($fn,array_slice($args,0,$n)); }";
        var unit = PhpIrLowerer.lowerFile("slice.php",source.getBytes(StandardCharsets.UTF_8));
        var model = ClassFile.of().parse(B1ClassFileEmitter.emit(B1CfgLowerer.lower(unit.functions().get(1))));
        var calls = model.methods().stream().flatMap(m -> m.code().orElseThrow().elementStream())
                .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).toList();
        assertTrue(calls.stream().anyMatch(c -> c.name().equalsString("forward")));
        assertFalse(calls.stream().anyMatch(c -> c.name().equalsString("slice") || c.name().equalsString("function")));
    }

    @Test void fixedArityEntriesAndCalls() {
        var unit = PhpIrLowerer.lowerFile("fixed.php", """
                <?php function zero() { return 0; } function one($x) { return $x; }
                function two($x,$y) { return $x + $y; } echo two(zero(),one(4));
                """.getBytes(StandardCharsets.UTF_8));
        var invoked = new java.util.HashSet<String>();
        for (var fn : unit.functions()) {
            var bytes = B1ClassFileEmitter.emit(B1CfgLowerer.lower(fn));
            B1Lint.requireClean(bytes,B1Lint.Kind.EMITTED);
            var cls = ClassFile.of().parse(bytes);
            assertTrue(cls.methods().stream().anyMatch(m -> m.methodName().equalsString("invoke" + fn.params().size())));
            for (var method : cls.methods()) for (var op : method.code().orElseThrow()) {
                assertFalse(op instanceof NewReferenceArrayInstruction);
                if (op instanceof InvokeInstruction call && call.name().stringValue().matches("invoke[012]")) {
                    assertEquals(Opcode.INVOKEVIRTUAL,call.opcode());
                    invoked.add(call.name().stringValue());
                }
            }
        }
        assertEquals(java.util.Set.of("invoke0","invoke1","invoke2"),invoked);
    }
}
