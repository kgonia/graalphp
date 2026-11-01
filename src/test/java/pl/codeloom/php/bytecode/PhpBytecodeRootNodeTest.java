package pl.codeloom.php.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;

import java.util.function.Consumer;

/**
 * The bytecode root's operations, built by hand as BytecodeTranslator emits them. The roots start on the uncached
 * interpreter (threshold 16, which php.bytecode.uncached leaves at 0) and each case runs past it, so both tiers (and
 * the move between them, also in the middle of a loop) are covered.
 */
class PhpBytecodeRootNodeTest {
    private static final PhpFunction.Param VALUE = new PhpFunction.Param("p", false, false, null, (char) 0, false);
    private static final PhpFunction.Param BY_REF = new PhpFunction.Param("p", true, false, null, (char) 0, false);
    private static final PhpFunction.Param INT = new PhpFunction.Param("p", false, false, null, 'i', false);

    /** Runs test in an entered context of the language. */
    private static void inContext(Consumer<PhpLanguage> test) {
        try (Context context = Context.newBuilder(PhpLanguage.ID).build()) {
            context.initialize(PhpLanguage.ID);
            context.enter();
            try {
                test.accept(PhpLanguage.get(null));
            } finally {
                context.leave();
            }
        }
    }

    /** A function of one parameter (variable 0) and variables more variables, built by body. */
    private static RootCallTarget function(PhpLanguage language, PhpFunction.Param param, int variables,
                                           BytecodeParser<PhpBytecodeRootNodeGen.Builder> body) {
        return function(language, param, variables, 16, body);
    }

    /** The same, starting on the cached interpreter when uncached is 0 (php.bytecode.uncached's default). */
    private static RootCallTarget function(PhpLanguage language, PhpFunction.Param param, int variables, int uncached,
                                           BytecodeParser<PhpBytecodeRootNodeGen.Builder> body) {
        PhpBytecodeRootNode root = PhpBytecodeRootNodeGen.create(language, BytecodeConfig.DEFAULT, body).getNode(0);
        root.initialize("test", "test.php", false, null, new PhpFunction.Param[] {param}, new int[] {0}, (char) 0,
                false, lastVariables, new LocalAccessor[0], null);
        root.getBytecodeNode().setUncachedThreshold(uncached);
        return root.getCallTarget();
    }

    /** The variables prologue made last (the parser runs inside create, so they come back this way). */
    private static LocalAccessor[] lastVariables;

    private static Object call(RootCallTarget target, Object argument) {
        return target.call((Object) null, null, null, argument);
    }

    /** beginRoot, the variables, the parameter's binding; the caller emits the rest and ends the root. */
    private static BytecodeLocal[] prologue(PhpBytecodeRootNodeGen.Builder b, int variables) {
        b.beginRoot();
        BytecodeLocal[] locals = new BytecodeLocal[variables];
        lastVariables = new LocalAccessor[variables];
        for (int i = 0; i < variables; i++) {
            locals[i] = b.createLocal("v" + i, null);
            lastVariables[i] = LocalAccessor.constantOf(locals[i]);
        }
        b.beginBindParameter(locals[0], 0);
        b.emitArgument(0);
        b.endBindParameter();
        return locals;
    }

    /** `[e1, e2, ...]` as BytecodeTranslator emits it: each element appended as soon as it is evaluated. */
    private static void list(PhpBytecodeRootNodeGen.Builder b, Runnable... elements) {
        LiteralSite site = new LiteralSite(elements.length);
        b.beginLiteralEnd(site);
        for (int i = 0; i < elements.length; i++) {
            b.beginLiteralAppend();
        }
        b.emitLiteralStart(site);
        for (Runnable element : elements) {
            element.run();
            b.endLiteralAppend();
        }
        b.endLiteralEnd();
    }

    @Test
    void addsArguments() {
        inContext(language -> {
            var root = PhpBytecodeRootNodeGen.create(language, BytecodeConfig.DEFAULT, b -> {
                b.beginRoot();
                b.beginReturn();
                b.beginAdd();
                b.emitLoadArgument(PhpFunction.ARG_FIRST);
                b.emitLoadArgument(PhpFunction.ARG_FIRST + 1);
                b.endAdd();
                b.endReturn();
                b.endRoot();
            }).getNode(0);
            root.initialize("test", "test.php", false, null, new PhpFunction.Param[0], new int[0], (char) 0, false,
                    new LocalAccessor[0], new LocalAccessor[0], null);
            root.getBytecodeNode().setUncachedThreshold(16);
            RootCallTarget target = root.getCallTarget();
            assertEquals(5L, target.call((Object) null, null, null, 2L, 3L));
            assertEquals(Long.MAX_VALUE + 1.0, target.call((Object) null, null, null, Long.MAX_VALUE, 1L));
            assertEquals(2.5, target.call((Object) null, null, null, 1L, 1.5));
            for (int i = 0; i < 100; i++) {
                target.call((Object) null, null, null, (long) i, 1L);
            }
        });
    }

    /**
     * `$s = 0; for ($i = 0; $i < $p; $i++) { $s += $i; } return [$s, $unset];` with ints, then a float; from the
     * uncached interpreter and from the cached one, whose first read of $unset finds the local cleared.
     */
    @ParameterizedTest
    @ValueSource(ints = {16, 0})
    void loopsOverVariables(int uncached) {
        inContext(language -> {
            RootCallTarget target = function(language, VALUE, 4, uncached, b -> {
                BytecodeLocal[] v = prologue(b, 4);
                b.beginWriteVar(v[1]);
                b.emitLoadConstant(0L);
                b.endWriteVar();
                b.beginWriteVar(v[2]);
                b.emitLoadConstant(0L);
                b.endWriteVar();
                b.beginWhile();
                b.beginLess();
                b.emitReadVar(v[2]);
                b.emitReadVar(v[0]);
                b.endLess();
                b.beginBlock();
                b.beginCompoundVar(v[1], 1);
                b.emitReadVar(v[2]);
                b.endCompoundVar();
                b.emitIncDecVar(v[2], true, false);
                b.endBlock();
                b.endWhile();
                b.beginReturn();
                list(b, () -> b.emitReadVar(v[1]), () -> b.emitReadVar(v[3]));
                b.endReturn();
                b.endRoot();
            });
            for (int i = 0; i < 40; i++) {
                PhpArray result = (PhpArray) call(target, 100L);
                assertEquals(4950L, result.get(0L));
                assertSame(PhpNull.NULL, result.get(1L));
            }
            PhpArray result = (PhpArray) call(target, 2.5);
            assertEquals(3L, result.get(0L));
        });
    }

    /** `$b = $p; $b[0] = 9; return [$p[0], $b];`: the write separates $b's array from $p's. */
    @Test
    void elementWriteSeparatesSharedArray() {
        inContext(language -> {
            RootCallTarget target = function(language, VALUE, 2, b -> {
                BytecodeLocal[] v = prologue(b, 2);
                b.beginWriteVar(v[1]);
                b.emitReadVar(v[0]);
                b.endWriteVar();
                b.beginWriteElement(v[1]);
                b.emitLoadConstant(9L);
                b.emitLoadConstant(0L);
                b.endWriteElement();
                b.beginReturn();
                list(b, () -> {
                    b.beginReadElement();
                    b.emitReadVar(v[0]);
                    b.emitLoadConstant(0L);
                    b.endReadElement();
                }, () -> b.emitReadVar(v[1]));
                b.endReturn();
                b.endRoot();
            });
            for (int i = 0; i < 40; i++) {
                PhpArray argument = PhpArray.list(1L, 2L);
                argument.addHolder();
                PhpArray result = (PhpArray) call(target, argument);
                assertEquals(1L, result.get(0L));
                assertEquals(9L, ((PhpArray) result.get(1L)).get(0L));
                assertEquals(1L, argument.get(0L));
                // the call released its variables: the caller's variable is the array's only holder again
                assertFalse(argument.isShared());
            }
        });
    }

    /** `function f(&$p) { $p[] = 3; $p = 5; }`: both writes go through the caller's reference. */
    @Test
    void byReferenceParameterWritesThroughTheReference() {
        inContext(language -> {
            RootCallTarget target = function(language, BY_REF, 1, b -> {
                BytecodeLocal[] v = prologue(b, 1);
                b.beginAppendElement(v[0]);
                b.emitLoadConstant(3L);
                b.endAppendElement();
                b.beginReturn();
                b.emitLoadConstant(PhpNull.NULL);
                b.endReturn();
                b.endRoot();
            });
            for (int i = 0; i < 40; i++) {
                PhpRef ref = new PhpRef(PhpArray.list(1L));
                ref.bind();
                call(target, ref);
                assertEquals(3L, ((PhpArray) ref.get()).get(1L));
                // the callee's binding went with its frame
                assertFalse(ref.isShared());
            }
        });
    }

    /** A typed parameter converts its argument (coercive mode), as FunctionRootNode.bindParameters. */
    @Test
    void typedParameterCoerces() {
        inContext(language -> {
            RootCallTarget target = function(language, INT, 1, b -> {
                BytecodeLocal[] v = prologue(b, 1);
                b.beginReturn();
                b.emitReadVar(v[0]);
                b.endReturn();
                b.endRoot();
            });
            assertEquals(3L, call(target, 3.0));
            assertEquals(4L, call(target, 4L));
        });
    }

    /** foreach by value over an int list and a hash, with the key: the loop holds the array while it runs. */
    @Test
    void foreachBindsKeysAndValues() {
        inContext(language -> {
            RootCallTarget target = function(language, VALUE, 3, b -> {
                BytecodeLocal[] v = prologue(b, 3);
                BytecodeLocal state = b.createLocal();
                BytecodeLocal position = b.createLocal();
                b.beginForeachStart(state, position);
                b.emitReadVar(v[0]);
                b.endForeachStart();
                b.beginTryFinally(() -> b.emitForeachEnd(state, position));
                b.beginWhile();
                b.beginForeachNext(position, v[2], v[1], true);
                b.emitLoadLocal(state);
                b.endForeachNext();
                b.beginBlock();
                // $p[] = $value: the loop's array is unaffected
                b.beginAppendElement(v[0]);
                b.emitReadVar(v[2]);
                b.endAppendElement();
                b.endBlock();
                b.endWhile();
                b.endTryFinally();
                b.beginReturn();
                list(b, () -> b.emitReadVar(v[0]), () -> b.emitReadVar(v[1]));
                b.endReturn();
                b.endRoot();
            });
            for (int i = 0; i < 40; i++) {
                PhpArray list = (PhpArray) call(target, PhpArray.list(5L, 6L, 7L));
                assertEquals(6, ((PhpArray) list.get(0L)).size());
                assertEquals(2L, list.get(1L));
            }
            PhpArray hash = new PhpArray();
            hash.put(pl.codeloom.php.runtime.Str.of("k"), 1L);
            PhpArray result = (PhpArray) call(target, hash);
            assertEquals("k", result.get(1L).toString());
            assertTrue(((PhpArray) result.get(0L)).size() == 2);
        });
    }

    /**
     * `function f($p) { if ($p > 0) { $k = 1; return $k; } return 0; }`, compiled: a call that leaves $k unset
     * (the local's kind is Long by then) returns through the epilog without deoptimizing the compiled code.
     */
    @Test
    void compiledReturnLeavesAnUnsetVariableAlone() {
        Context.Builder builder = Context.newBuilder(PhpLanguage.ID).allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false");
        try (Context context = builder.build()) {
            context.initialize(PhpLanguage.ID);
            context.enter();
            try {
                RootCallTarget target = function(PhpLanguage.get(null), VALUE, 2, 0, b -> {
                    BytecodeLocal[] v = prologue(b, 2);
                    b.beginIfThen();
                    b.beginGreater();
                    b.emitReadVar(v[0]);
                    b.emitLoadConstant(0L);
                    b.endGreater();
                    b.beginBlock();
                    b.beginWriteVar(v[1]);
                    b.emitLoadConstant(1L);
                    b.endWriteVar();
                    b.beginReturn();
                    b.emitReadVar(v[1]);
                    b.endReturn();
                    b.endBlock();
                    b.endIfThen();
                    b.beginReturn();
                    b.emitLoadConstant(0L);
                    b.endReturn();
                    b.endRoot();
                });
                Assumptions.assumeTrue(target instanceof OptimizedCallTarget, "no optimizing runtime");
                OptimizedCallTarget compiled = (OptimizedCallTarget) target;
                // both branches profiled in the interpreter, and $k's kind is Long
                for (int i = 0; i < 20; i++) {
                    assertEquals(1L, call(target, 1L));
                    assertEquals(0L, call(target, 0L));
                }
                compiled.compile(true);
                assertTrue(compiled.isValid(), "compiled");
                for (int i = 0; i < 3; i++) {
                    assertEquals(0L, call(target, 0L));
                    assertTrue(compiled.isValid(), "a call leaving $k unset kept the compiled code");
                    assertEquals(1L, call(target, 1L));
                }
            } finally {
                context.leave();
            }
        }
    }
}
