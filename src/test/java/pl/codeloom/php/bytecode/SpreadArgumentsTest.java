package pl.codeloom.php.bytecode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/** Unpacking and call_user_func_array()'s binding by position and by name (ZEND_SEND_UNPACK, ZEND_SEND_ARRAY). */
class SpreadArgumentsTest {
    private static final Object NP = PhpFunction.NOT_PASSED;

    private static PhpFunction.Param param(String name, boolean byRef, boolean variadic) {
        return new PhpFunction.Param(name, byRef, variadic, null, null, false, null);
    }

    /** function abc($a, $b, $c) */
    private static final PhpFunction ABC = new PhpFunction("abc", new PhpFunction.Param[] {
            param("a", false, false), param("b", false, false), param("c", false, false)}, false, false, true, false,
            () -> null);
    /** function bump($a, &$b) */
    private static final PhpFunction BUMP = new PhpFunction("bump", new PhpFunction.Param[] {
            param("a", false, false), param("b", true, false)}, false, false, true, false, () -> null);
    /** function rest($a, &...$rest) */
    private static final PhpFunction REST = new PhpFunction("rest", new PhpFunction.Param[] {
            param("a", false, false), param("rest", true, true)}, false, false, true, false, () -> null);

    private static PhpArray array(Object... keysAndValues) {
        PhpArray array = new PhpArray();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            Object key = keysAndValues[i] instanceof String name ? Str.of(name) : keysAndValues[i];
            array.put(key, keysAndValues[i + 1]);
        }
        return array;
    }

    private static Object[] bound(SpreadArguments list) {
        Object[] frame = list.frameArguments();
        Object[] values = new Object[frame.length - PhpFunction.ARG_FIRST];
        System.arraycopy(frame, PhpFunction.ARG_FIRST, values, 0, values.length);
        return values;
    }

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
    void stringKeysAreNamesAndIntKeysPositions() {
        SpreadArguments list = new SpreadArguments();
        list.addArray(array(1L, "one", 0L, "zero"), ABC, false);
        assertArrayEquals(new Object[] {"one", "zero"}, bound(list));
        SpreadArguments named = new SpreadArguments();
        named.addArray(array("c", "C", "a", "A"), ABC, false);
        assertArrayEquals(new Object[] {"A", NP, "C"}, bound(named));
        assertTrue(named.isNamed());
    }

    @Test
    void eachUnpackingStartsAfresh() {
        SpreadArguments list = new SpreadArguments();
        list.addArray(array(0L, "a", "b", "b"), ABC, false);
        list.addArray(array(0L, "c"), ABC, false);
        assertArrayEquals(new Object[] {"a", "b", "c"}, bound(list));
        assertFalse(list.isNamed());
    }

    @Test
    void thePositionalAfterANameAndTheBindingErrors() {
        inContext(() -> {
            PhpError unpacking = assertThrows(PhpError.class,
                    () -> new SpreadArguments().addArray(array(0L, "a", "b", "b", 1L, "c"), ABC, false));
            assertEquals("Cannot use positional argument after named argument during unpacking",
                    unpacking.getPhpMessage());
            PhpError sendArray = assertThrows(PhpError.class,
                    () -> new SpreadArguments().addSendArray(array("c", "C", 0L, "A"), ABC, null));
            assertEquals("Cannot use positional argument after named argument", sendArray.getPhpMessage());
            PhpError overwrites = assertThrows(PhpError.class,
                    () -> new SpreadArguments().addArray(array(0L, "a", "a", "A"), ABC, false));
            assertEquals("Named parameter $a overwrites previous argument", overwrites.getPhpMessage());
            PhpError unknown = assertThrows(PhpError.class,
                    () -> new SpreadArguments().addArray(array("zz", 1L), ABC, false));
            assertEquals("Unknown named parameter $zz", unknown.getPhpMessage());
            PhpError noConstructor = assertThrows(PhpError.class,
                    () -> new SpreadArguments(true).addArray(array("x", 1L), MethodSite.BY_VALUE, false));
            assertEquals("Unknown named parameter $x", noConstructor.getPhpMessage());
        });
    }

    @Test
    void extrasGoToTheVariadicAndToCall() {
        SpreadArguments list = new SpreadArguments();
        list.addArray(array("z", "Z", "a", "A"), REST, false);
        assertArrayEquals(new Object[] {"A"}, bound(list));
        assertTrue(list.isNamed());
        SpreadArguments magic = new SpreadArguments();
        magic.addArray(array(0L, 1L, "two", 2L), MethodSite.BY_VALUE, false);
        Object[] values = magic.magicValues(Str.of("m"));
        PhpArray arguments = (PhpArray) values[1];
        assertEquals(1L, arguments.get(0L));
        assertEquals(2L, arguments.get(Str.of("two")));
    }

    @Test
    void aVariableIsSeparatedWhenANameLandsOnAReference() {
        assertTrue(SpreadArguments.landsOnRef(BUMP, 0, array("b", 0L)));
        assertFalse(SpreadArguments.landsOnRef(BUMP, 0, array("a", 0L)));
        assertTrue(SpreadArguments.landsOnRef(BUMP, 0, array(0L, 0L, 1L, 0L)));
        // an unknown name looks at the variadic parameter
        assertTrue(SpreadArguments.landsOnRef(REST, 0, array("zz", 0L)));
        assertFalse(SpreadArguments.landsOnRef(ABC, 0, array("zz", 0L)));
    }

    @Test
    void sendArrayWarnsForAValueAndKeepsAReference() {
        inContext(() -> {
            PhpRef ref = new PhpRef(1L);
            SpreadArguments list = new SpreadArguments();
            PhpArray array = new PhpArray();
            array.put(Str.of("a"), "A");
            array.putRef(Str.of("b"), ref);
            list.addSendArray(array, BUMP, null);
            Object[] values = bound(list);
            assertEquals("A", values[0]);
            assertEquals(ref, values[1]);
            SpreadArguments value = new SpreadArguments();
            value.addSendArray(array(0L, "A", 1L, 5L), BUMP, null);
            assertInstanceOf(PhpRef.class, bound(value)[1]);
        });
    }
}
