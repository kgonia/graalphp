package pl.codeloom.php.b1;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codeloom.php.ir.PhpIrLowerer;
import pl.codeloom.php.parser.Translator;
import pl.codeloom.php.parser.zend.ZendParser;
import pl.codeloom.php.runtime.PhpFunction;
import org.junit.jupiter.api.io.TempDir;
import pl.codeloom.php.b1.runtime.B1Object;
import pl.codeloom.php.b1.runtime.B1RequestContext;
import pl.codeloom.php.bytecode.TruffleIrAdapter;
import pl.codeloom.php.PhpContext;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DualBackendContractTest {
    @Test void normalFileEntryAndEveryFunctionMatchPhp8513() throws Exception {
        Path file = Path.of("src/test/resources/b1/contract_d17.php").toRealPath();
        byte[] expected = Files.readAllBytes(file.resolveSibling("contract_d17.stdout"));
        assertArrayEquals(expected, truffle(file, true, "a", "r"), "Truffle vs PHP 8.5.13");
        assertArrayEquals(expected, b1(file, 6, "a", "r"), "B1 vs PHP 8.5.13");
    }

    @Test void destructorDuringArgumentUnwindChainsTheOriginalException(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("unwind.php");
        Files.writeString(file, """
                <?php
                class Drop {
                    public function __destruct() { throw new Exception("during cleanup"); }
                }
                function abortCall() { throw new Exception("original"); }
                function consume($a, $b) { echo "unreachable"; }
                try {
                    consume(new Drop(), abortCall());
                } catch (Exception $e) {
                    $previous = $e->getPrevious();
                    echo $e->getMessage() . "/" . $previous->getMessage() . "\\n";
                }
                """);
        byte[] expected = "during cleanup/original\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(expected, b1(file, 4));
        assertArrayEquals(expected, truffle(file, true));
        // Translate the same path again with an unsupported construct. The diagnostic must change.
        Files.writeString(file, "<?php echo implode('', ['legacy']);");
        assertArrayEquals("legacy".getBytes(java.nio.charset.StandardCharsets.UTF_8), truffle(file, false));
    }

    private static final Path AUDIT = Path.of("src/test/resources/b1/audit");

    @ParameterizedTest(name = "{0} matches pinned PHP on both backends")
    @ValueSource(strings = {"ownership", "nested", "sends", "return_cleanup", "catch_binding",
            "construction", "trace", "operations", "shutdown", "binary_operands", "drain_collections",
            "shutdown_repeat", "handler_shutdown", "exception_signatures", "late_class"})
    void auditContract(String fixture) throws Exception {
        String file = "/fixtures/" + fixture + ".php";
        byte[] php = Files.readAllBytes(AUDIT.resolve(fixture + ".php"));
        byte[] expected = Files.readAllBytes(AUDIT.resolve(fixture + ".stdout"));
        var output = new ByteArrayOutputStream();
        // Use the Docker mount's logical source name on every OS, including inside errors and traces.
        var b1 = new B1RequestContext(output);
        var compiled = B1Compiler.compile(PhpIrLowerer.lowerFile(file, php), b1);
        b1.runScript(compiled.entry());
        assertEquals(0, b1.liveDestructibleCount());
        assertArrayEquals(expected, output.toByteArray(), () -> "B1 output\\n" + output);
        output.reset();
        try (var context = Context.newBuilder("php").allowAllAccess(true).allowExperimentalOptions(true)
                .option("php.Parser", "java").out(output).build()) {
            context.initialize("php");
            context.enter();
            try {
                var ctx = PhpContext.get(null);
                var source = com.oracle.truffle.api.source.Source.newBuilder("php",
                        new String(php, java.nio.charset.StandardCharsets.UTF_8), file).build();
                var parsed = ZendParser.parse(php);
                var root = new Translator(ctx.getLanguage(), file, source, false)
                        .translateFile(parsed.ast(), () -> ZendParser.parse(php).ast(), parsed.diagnostics());
                assertTrue(TruffleIrAdapter.wasTranslatedViaIr(file));
                root.getCallTarget().call(new Object[PhpFunction.ARG_FIRST]);
                ctx.endScript();
            } finally { context.leave(); }
        }
        assertArrayEquals(expected, output.toByteArray(), () -> "Truffle output\\n" + output);
    }

    @Test void topLevelHandlerRunsBeforeShutdown() throws Exception {
        var file = AUDIT.resolve("handler_top.php").toRealPath();
        byte[] expected = Files.readAllBytes(AUDIT.resolve("handler_top.stdout"));
        var output = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(output);
        ctx.runScript(B1Compiler.compile(file, ctx).entry());
        assertArrayEquals(expected, output.toByteArray());
        assertArrayEquals(expected, truffle(file, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy_binding", "legacy_construction", "magic_read"})
    void legacyFallbackMatchesPhp(String fixture) throws Exception {
        var file = AUDIT.resolve(fixture + ".php");
        assertArrayEquals(Files.readAllBytes(AUDIT.resolve(fixture + ".stdout")), truffle(file, false));
        if (fixture.equals("magic_read")) {
            assertThrows(PhpIrLowerer.UnsupportedIr.class,
                    () -> PhpIrLowerer.lowerFile(file.toString(), Files.readAllBytes(file)));
        }
    }

    @Test void irCallerReceivesLegacyArraysAndReferences() throws Exception {
        assertArrayEquals(Files.readAllBytes(AUDIT.resolve("mixed_legacy.stdout")),
                truffle(AUDIT.resolve("mixed_legacy.php"), false));
        assertTrue(TruffleIrAdapter.wasTranslatedViaIr(AUDIT.resolve("mixed_caller.php").toRealPath().toString()));
    }

    @Test void legacyNamedCallerUsesIrDeclarationDefaults() throws Exception {
        var file = AUDIT.resolve("named_caller.php");
        assertArrayEquals(Files.readAllBytes(AUDIT.resolve("named_caller.stdout")), truffle(file, false));
        assertTrue(TruffleIrAdapter.wasTranslatedViaIr(AUDIT.resolve("named_decl.php").toRealPath().toString()));
    }

    @Test void lexicalGlobalSlotsKeepTheirShutdownOrder() throws Exception {
        assertArrayEquals(Files.readAllBytes(AUDIT.resolve("symbol_order.stdout")),
                truffle(AUDIT.resolve("symbol_order.php"), false));
    }

    @Test void reservedClassUsesFrontendCompileDiagnostic() throws Exception {
        Path file = AUDIT.resolve("reserved.php").toRealPath();
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder("php").allowAllAccess(true).allowExperimentalOptions(true)
                .option("php.Parser", "java").out(output).build()) {
            var error = assertThrows(org.graalvm.polyglot.PolyglotException.class,
                    () -> context.eval(Source.newBuilder("php", file.toFile()).build()));
            assertTrue(error.isExit());
            assertEquals(255, error.getExitStatus());
        } catch (org.graalvm.polyglot.PolyglotException close) {
            assertTrue(close.isExit());
            assertEquals(255, close.getExitStatus());
        }
        assertEquals(Files.readString(AUDIT.resolve("reserved.stdout")).replace("/fixtures/reserved.php", file.toString()),
                output.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] truffle(Path file, boolean expectIr, String... integerResults) throws Exception {
        var truffle = new ByteArrayOutputStream();
        try (var context = Context.newBuilder("php").allowAllAccess(true).allowExperimentalOptions(true)
                .option("php.Parser", "java").option("compiler.FirstTierUseEconomy", "false").out(truffle).build()) {
            context.eval(Source.newBuilder("php", file.toFile()).build());
            assertEquals(expectIr, TruffleIrAdapter.wasTranslatedViaIr(file.toRealPath().toString()));
            context.enter();
            try {
                for (String name : integerResults) assertEquals(15L, PhpContext.get(null).findGlobal(name).get());
            } finally { context.leave(); }
        }
        return truffle.toByteArray();
    }

    private static byte[] b1(Path file, int classes, String... integerResults) throws Exception {
        var b1 = new ByteArrayOutputStream();
        var ctx = new B1RequestContext(b1);
        var compiled = B1Compiler.compile(file, ctx);
        assertEquals(classes, compiled.lintedClasses(), "each file, function and method must pass pre-definition lint");
        assertTrue(compiled.entry().getClass().isHidden());
        assertSame(compiled.entry(), ctx.file(file.toString()));
        try {
            B1Object.release(ctx, ctx.file(file.toString()).invoke0(ctx, null));
            for (String name : integerResults) assertEquals(15L, ctx.findGlobal(name).get());
        }
        finally { ctx.shutdown(); }
        assertEquals(0, ctx.liveDestructibleCount());
        return b1.toByteArray();
    }
}
