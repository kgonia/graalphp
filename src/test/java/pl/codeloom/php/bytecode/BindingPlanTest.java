package pl.codeloom.php.bytecode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oracle.truffle.api.strings.TruffleString;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.Str;

/** The binding of named arguments (zend_handle_named_arg's rules) as plans per site and callee. */
class BindingPlanTest {
    private static final Object NP = PhpFunction.NOT_PASSED;

    private static PhpFunction.Param param(String name, boolean byRef, boolean variadic, boolean optional) {
        return new PhpFunction.Param(name, byRef, variadic, optional ? "= 0" : null, null, false, null);
    }

    /** function five($a, $b, $c = 'c', $d = 'd', $e = 'e') */
    private static final PhpFunction FIVE = new PhpFunction("five", new PhpFunction.Param[] {
            param("a", false, false, false), param("b", false, false, false), param("c", false, false, true),
            param("d", false, false, true), param("e", false, false, true)}, false, false, true, false, () -> null);
    /** function refs(&$x, $y = 0, &$z = null) */
    private static final PhpFunction REFS = new PhpFunction("refs", new PhpFunction.Param[] {
            param("x", true, false, false), param("y", false, false, true), param("z", true, false, true)},
            false, false, true, false, () -> null);
    /** function rest($a, &...$rest) */
    private static final PhpFunction REST = new PhpFunction("rest", new PhpFunction.Param[] {
            param("a", false, false, false), param("rest", true, true, false)}, false, false, true, false,
            () -> null);

    /** A site's names: null for a positional argument. */
    private static NamedSite site(boolean construct, boolean constant, String... names) {
        TruffleString[] interned = new TruffleString[names.length];
        for (int i = 0; i < names.length; i++) {
            interned[i] = names[i] == null ? null : Str.of(names[i]);
        }
        return new NamedSite(interned, construct, constant);
    }

    private static NamedSite site(String... names) {
        return site(false, false, names);
    }

    /** The frame arguments of a call passing values in source order. */
    private static Object[] frame(BindingPlan plan, Object... values) {
        Object[] arguments = new Object[PhpFunction.ARG_FIRST + values.length];
        System.arraycopy(values, 0, arguments, PhpFunction.ARG_FIRST, values.length);
        Object[] frame = plan.frame(arguments);
        Object[] bound = new Object[frame.length - PhpFunction.ARG_FIRST];
        System.arraycopy(frame, PhpFunction.ARG_FIRST, bound, 0, bound.length);
        return bound;
    }

    /** Runs test in an entered context (PhpError takes its trace and the context's options). */
    private static void inContext(Runnable test) {
        try (Context context = Context.newBuilder(PhpLanguage.ID).allowExperimentalOptions(true)
                .option("compiler.FirstTierUseEconomy", "false").build()) {
            context.initialize(PhpLanguage.ID);
            context.enter();
            try {
                test.run();
            } finally {
                context.leave();
            }
        }
    }

    @Test
    void namesTakeTheirParametersPositions() {
        BindingPlan plan = site(null, null, null, "e", "d").plan(FIVE);
        assertArrayEquals(new Object[] {"A", "B", "C", "D", "E"}, frame(plan, "A", "B", "C", "E", "D"));
    }

    @Test
    void namesInOrderKeepTheArguments() {
        BindingPlan plan = site("a", "b").plan(FIVE);
        Object[] arguments = new Object[PhpFunction.ARG_FIRST + 2];
        assertSame(arguments, plan.frame(arguments));
    }

    @Test
    void skippedParametersAreHolesUpToTheLastBound() {
        BindingPlan plan = site(null, null, "e").plan(FIVE);
        assertArrayEquals(new Object[] {"A", "B", NP, NP, "E"}, frame(plan, "A", "B", "E"));
        assertArrayEquals(new Object[] {NP, "B"}, frame(site("b").plan(FIVE), "B"));
    }

    @Test
    void byReferenceIsTheSlotsParameters() {
        BindingPlan plan = site("z", "x").plan(REFS);
        assertTrue(plan.takesRef(0));
        assertTrue(plan.takesRef(1));
        assertSame(NamedSite.BY_REFERENCE, plan.sendTarget(0));
        assertEquals(2, plan.parameter(0));
        assertArrayEquals(new Object[] {"X", NP, "Z"}, frame(plan, "Z", "X"));
        assertFalse(site("y").plan(REFS).takesRef(0));
        assertSame(MethodSite.BY_VALUE, site("y").plan(REFS).sendTarget(0));
    }

    @Test
    void anUnknownNameFailsAtItsArgument() {
        inContext(() -> {
            BindingPlan plan = site(null, "x", "b").plan(FIVE);
            plan.checkSend(0);
            PhpError error = assertThrows(PhpError.class, () -> plan.checkSend(1));
            assertEquals("Unknown named parameter $x", error.getPhpMessage());
        });
    }

    @Test
    void namesAreCaseSensitive() {
        inContext(() -> {
            PhpError error = assertThrows(PhpError.class, () -> site("A").plan(FIVE).checkSend(0));
            assertEquals("Unknown named parameter $A", error.getPhpMessage());
        });
    }

    @Test
    void aSlotSetTwiceIsOverwritten() {
        inContext(() -> {
            PhpError positional = assertThrows(PhpError.class, () -> site(null, "a").plan(FIVE).checkSend(1));
            assertEquals("Named parameter $a overwrites previous argument", positional.getPhpMessage());
            BindingPlan named = site("b", "b").plan(FIVE);
            named.checkSend(0);
            assertThrows(PhpError.class, () -> named.checkSend(1));
        });
    }

    @Test
    void aVariadicCollectsUnknownNamesByReference() {
        inContext(() -> {
            BindingPlan plan = site("z", "a").plan(REST);
            plan.checkSend(0);
            plan.checkSend(1);
            assertTrue(plan.takesRef(0));
            assertEquals(1, plan.parameter(0));
            // its own name isn't a parameter's: an extra too
            assertTrue(site("rest").plan(REST).takesRef(0));
            assertThrows(PhpError.class, () -> site("z", "z").plan(REST).checkSend(1));
        });
    }

    @Test
    void noConstructorTakesNoName() {
        inContext(() -> {
            assertThrows(PhpError.class, () -> site(true, false, "x").plan(MethodSite.BY_VALUE).checkSend(0));
            // __call's arguments are all extras
            site(false, false, "x").plan(MethodSite.BY_VALUE).checkSend(0);
        });
    }

    @Test
    void callsArgumentsHaveTheNamesAfterThePositionalOnes() {
        BindingPlan plan = site(null, "two", "one").plan(MethodSite.BY_VALUE);
        Object[] arguments = {null, null, null, 1L, 2L, 3L};
        Object[] values = plan.magicValues(Str.of("m"), plan.frame(arguments), arguments);
        assertEquals(Str.of("m"), values[0]);
        PhpArray args = (PhpArray) values[1];
        assertEquals(3, args.size());
        assertEquals(1L, args.get(0L));
        assertEquals(2L, args.get(Str.of("two")));
        assertEquals(3L, args.get(Str.of("one")));
    }

    @Test
    void aConstantExpressionFailsAtTheCallButForARepeatedName() {
        inContext(() -> {
            BindingPlan unknown = site(false, true, "zz", "b").plan(FIVE);
            unknown.checkSend(0);
            unknown.checkSend(1);
            assertFalse(unknown.takesRef(0));
            BindingPlan repeated = site(false, true, "zz", "b", "b").plan(FIVE);
            repeated.checkSend(1);
            PhpError error = assertThrows(PhpError.class, () -> repeated.checkSend(2));
            assertEquals("Named parameter $b overwrites previous argument", error.getPhpMessage());
        });
    }
}
