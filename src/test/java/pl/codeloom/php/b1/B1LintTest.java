package pl.codeloom.php.b1;

import org.junit.jupiter.api.Test;
import pl.codeloom.php.b1.runtime.B1Callable;
import pl.codeloom.php.b1.runtime.B1RequestContext;
import pl.codeloom.php.b1.runtime.HotPath;
import pl.codeloom.php.ir.PhpIrLowerer;

import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;
import static org.junit.jupiter.api.Assertions.*;

class B1LintTest {
    private static final ClassDesc SAMPLE = ClassDesc.of("sample.ArbitraryName");
    private static final ClassDesc BASE = ClassDesc.of(B1Callable.class.getName());
    private static final ClassDesc CTX = ClassDesc.of(B1RequestContext.class.getName());

    static final class BadRuntime {
        @HotPath(lookup = true)
        static Object[] dispatch(java.util.Map<String, Object> table, String name, long value) {
            return new Object[]{table.get(name), table.get(name), name.getBytes(StandardCharsets.UTF_8), value};
        }
    }

    @Test void hotRuntimeAnnotationsEnableTheDispatchAndLookupChecks() throws Exception {
        try (var bytes = BadRuntime.class.getResourceAsStream("B1LintTest$BadRuntime.class")) {
            assertNotNull(bytes);
            var violations = B1Lint.inspect(bytes.readAllBytes(), B1Lint.Kind.RUNTIME);
            assertEquals(Set.of("B1-04", "B1-05", "B1-06", "B1-07"), violations.stream()
                    .map(s -> s.substring(0, 5)).collect(Collectors.toSet()));
        }
    }

    interface AlternateCallable { Object apply(B1RequestContext context); }

    static class AlternativeDispatcher {
        static Object callSlice(AlternateCallable target, B1RequestContext context) { return target.apply(context); }
    }

    @Test void checksAlternativeCallableOwnersAndDispatchSignatures() throws Exception {
        try (var bytes = AlternativeDispatcher.class.getResourceAsStream("B1LintTest$AlternativeDispatcher.class")) {
            var errors = B1Lint.inspect(bytes.readAllBytes(), B1Lint.Kind.RUNTIME);
            assertTrue(errors.stream().anyMatch(e -> e.startsWith("B1-03")));
            assertTrue(errors.stream().anyMatch(e -> e.startsWith("B1-04") && e.contains("callSlice")));
        }
    }

    static class FunctionalDispatcher {
        @HotPath
        static Object hot(B1RequestContext ctx, java.util.function.Function<Object, Object> fn, Object arg) {
            return fn.apply(arg);
        }

        static Object cold(java.util.function.Function<Object, Object> fn, Object arg) { return fn.apply(arg); }
    }

    @Test void rejectsFunctionalInterfacesInEmittedCodeAndHotRuntimeMethods() throws Exception {
        try (var stream = FunctionalDispatcher.class.getResourceAsStream("B1LintTest$FunctionalDispatcher.class")) {
            byte[] bytes = stream.readAllBytes();
            var runtime = B1Lint.inspect(bytes, B1Lint.Kind.RUNTIME);
            assertTrue(runtime.stream().anyMatch(e -> e.startsWith("B1-03") && e.endsWith(".hot")));
            assertFalse(runtime.stream().anyMatch(e -> e.endsWith(".cold")));
            assertTrue(B1Lint.inspect(bytes, B1Lint.Kind.EMITTED).stream()
                    .anyMatch(e -> e.startsWith("B1-03") && e.endsWith(".cold")));
        }
    }

    @Test void rejectsNonvirtualCallableDispatch() {
        var object = ClassDesc.of("pl.codeloom.php.b1.runtime.B1Object");
        var signature = MethodTypeDesc.of(CD_Object, CTX, object, CD_Object);
        byte[] bytes = ClassFile.of().build(SAMPLE, cb -> cb.withSuperclass(BASE)
                .withMethodBody("run", signature, ACC_PUBLIC, c -> c.aload(0).aload(1).aload(2).aload(3)
                        .invokespecial(BASE, "invoke1", signature).areturn()));
        for (var kind : B1Lint.Kind.values())
            assertTrue(B1Lint.inspect(bytes, kind).stream().anyMatch(e -> e.startsWith("B1-03 nonvirtual")));
    }

    @Test void distinguishesPhpStringDataFromSymbolicTypes() {
        var php = "<?php echo 'com/oracle/truffle/api/Node'; echo 'java/lang/ThreadLocal';";
        var unit = PhpIrLowerer.lowerFile("literal.php", php.getBytes(StandardCharsets.UTF_8));
        B1Lint.requireClean(B1ClassFileEmitter.emit(B1CfgLowerer.lower(unit.functions().getFirst())), B1Lint.Kind.EMITTED);
        var node = ClassDesc.of("com.oracle.truffle.api.Node");
        byte[] symbolic = ClassFile.of().build(SAMPLE, cb -> cb.withSuperclass(BASE).withMethodBody("test",
                MethodTypeDesc.of(CD_void, CTX), ACC_STATIC, c -> c.aconst_null()
                        .invokevirtual(node, "method", MethodTypeDesc.of(CD_void)).return_()));
        assertTrue(B1Lint.inspect(symbolic, B1Lint.Kind.EMITTED).stream().anyMatch(e -> e.startsWith("B1-08")));
    }

    static final class IndirectLookup {
        static Object read(java.util.Map<String,Object> map,String key) { return map.get(key); }
    }

    static final class BadBridge {
        public Object invoke1(B1RequestContext ctx,pl.codeloom.php.b1.runtime.B1Object self,Object value) {
            return wrap(value);
        }
        private Object wrap(Object value) { return new Object[]{value}; }
        @HotPath(lookup = true)
        static Object lookup(B1RequestContext ctx,java.util.Map<String,Object> map,String key) {
            if (IndirectLookup.read(map,key) == null) return null;
            return IndirectLookup.read(map,key);
        }
    }

    @Test void followsDispatchAndLookupHelpers() throws Exception {
        try (var stream = BadBridge.class.getResourceAsStream("B1LintTest$BadBridge.class")) {
            var findings = B1Lint.inspect(stream.readAllBytes(),B1Lint.Kind.RUNTIME);
            assertTrue(findings.stream().anyMatch(f -> f.startsWith("B1-06")));
            assertTrue(findings.stream().anyMatch(f -> f.startsWith("B1-07")));
        }
    }

    @Test void checksDebugOnlyDescriptorsAndGenericSignatures() {
        for (boolean generic : new boolean[]{false,true}) {
            byte[] bytes = ClassFile.of().build(SAMPLE,cb -> cb.withMethodBody("sample",
                    MethodTypeDesc.of(CD_void,CD_Object),ACC_STATIC,c -> {
                Label from = c.newBoundLabel();
                c.nop();
                Label to = c.newBoundLabel();
                if (generic) c.localVariableType(0,"v",Signature.parseFrom("Ljava/util/List<Lcom/oracle/truffle/api/Node;>;"),from,to);
                else c.localVariable(0,"v",ClassDesc.of("com.oracle.truffle.api.Node"),from,to);
                c.return_();
            }));
            assertTrue(B1Lint.inspect(bytes,B1Lint.Kind.RUNTIME).stream().anyMatch(f -> f.startsWith("B1-08")));
        }
    }

    @Test void rejectsViolationsBeforeAnyClassIsDefined() {
        byte[] bytes = ClassFile.of().build(SAMPLE, cb -> {
            cb.withSuperclass(BASE);
            cb.withField("state", ClassDesc.of("com.oracle.truffle.api.nodes.Node").arrayType(), ACC_STATIC);
            cb.withMethodBody("wrong", MethodTypeDesc.of(CD_void), ACC_STATIC, c -> {
                for (int i = 0; i < 7500; i++) c.nop();
                c.aconst_null().aconst_null().aconst_null().invokeinterface(BASE, "invoke0",
                        MethodTypeDesc.of(CD_Object, CTX, ClassDesc.of("pl.codeloom.php.b1.runtime.B1Object"))).pop().return_();
            });
            cb.withMethodBody("<clinit>", MethodTypeDesc.of(CD_void), ACC_STATIC, c -> {
                for (int i = 0; i < 4000; i++) c.nop();
                c.return_();
            });
            var concat = ofCallsiteBootstrap(ClassDesc.of("java.lang.invoke.StringConcatFactory"), "makeConcat",
                    CD_CallSite);
            cb.withMethodBody("dynamic", MethodTypeDesc.of(CD_String, CTX), ACC_STATIC, c ->
                    c.invokedynamic(DynamicCallSiteDesc.of(concat, "concat", MethodTypeDesc.of(CD_String))).areturn());
        });
        var findings = B1Lint.inspect(bytes, B1Lint.Kind.EMITTED);
        assertEquals(Set.of("B1-01", "B1-02", "B1-03", "B1-04", "B1-08"), findings.stream()
                .map(s -> s.substring(0, 5)).collect(Collectors.toSet()));
        assertTrue(findings.stream().anyMatch(s -> s.contains("<clinit>") && s.contains("4001")));
        assertTrue(findings.stream().anyMatch(s -> s.contains("context parameter 0")));
        assertThrows(IllegalStateException.class, () -> B1Lint.requireClean(bytes, B1Lint.Kind.EMITTED));
    }

    @Test void permitsDynamicConstantsAndEmitsPrimitiveIntegerLocals() {
        byte[] condy = ClassFile.of().build(SAMPLE, cb -> cb.withSuperclass(BASE).withMethodBody("load",
                MethodTypeDesc.of(CD_Object, CTX), ACC_STATIC, c -> c.ldc(NULL).areturn()));
        assertDoesNotThrow(() -> B1Lint.requireClean(condy, B1Lint.Kind.EMITTED));

        var unit = PhpIrLowerer.lowerFile("integer.php", "<?php function f() { $x = 42; return $x; } echo f();"
                .getBytes(StandardCharsets.UTF_8));
        var bytes = B1ClassFileEmitter.emit(B1CfgLowerer.lower(unit.functions().get(1)));
        var instructions = ClassFile.of().parse(bytes).methods().stream().filter(m -> m.methodName().equalsString("run"))
                .findFirst().orElseThrow().code().orElseThrow().elementStream().filter(Instruction.class::isInstance)
                .map(Instruction.class::cast).map(Instruction::opcode).toList();
        assertTrue(instructions.contains(Opcode.LSTORE));
        assertTrue(instructions.contains(Opcode.LLOAD));
        assertDoesNotThrow(() -> B1Lint.requireClean(bytes, B1Lint.Kind.EMITTED));
    }

    static final class BadStaticAndVirtualThread {
        static final java.util.HashMap<String, Object> LEAK = new java.util.HashMap<>();
        static Thread spawn(Runnable r) { return Thread.ofVirtual().unstarted(r); }
    }

    @Test void enforcesB109ThroughB113() throws Exception {
        // B1-11 & B1-13 on runtime class
        try (var stream = BadStaticAndVirtualThread.class.getResourceAsStream("B1LintTest$BadStaticAndVirtualThread.class")) {
            assertNotNull(stream);
            var runtimeFindings = B1Lint.inspect(stream.readAllBytes(), B1Lint.Kind.RUNTIME);
            assertTrue(runtimeFindings.stream().anyMatch(f -> f.startsWith("B1-11")));
            assertTrue(runtimeFindings.stream().anyMatch(f -> f.startsWith("B1-13")));
        }
        // B1-09 (oversized inline string), B1-10 (outlined fragment calling leave/pushFrame), B1-12 (retained IR ref)
        var frame = ClassDesc.of("pl.codeloom.php.b1.runtime.B1Frame");
        var irUnit = ClassDesc.of("pl.codeloom.php.ir.IrUnit");
        byte[] badEmitted = ClassFile.of().build(SAMPLE, cb -> {
            cb.withSuperclass(BASE);
            cb.withField("retained", irUnit, ACC_PRIVATE | ACC_FINAL);
            cb.withMethodBody("fragment0", MethodTypeDesc.of(CD_Object, CTX, frame), ACC_PRIVATE | ACC_STATIC, c -> {
                c.ldc("x".repeat(16_385)).pop();
                c.aload(1).aconst_null().invokevirtual(frame, "leave", MethodTypeDesc.of(CD_void, CD_Object));
                c.aconst_null().areturn();
            });
        });
        var emittedFindings = B1Lint.inspect(badEmitted, B1Lint.Kind.EMITTED);
        assertTrue(emittedFindings.stream().anyMatch(f -> f.startsWith("B1-09")));
        assertTrue(emittedFindings.stream().anyMatch(f -> f.startsWith("B1-10")));
        assertTrue(emittedFindings.stream().anyMatch(f -> f.startsWith("B1-12")));
    }
}
