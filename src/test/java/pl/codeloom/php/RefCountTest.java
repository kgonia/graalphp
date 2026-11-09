package pl.codeloom.php;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Objects' reference counts (Refcount, the counting-only prototype of wiki/destructors.md; from the B prototype): the slots holding an
 * object after each kind of store, as php-src's refcount would be outside temporaries. truffle_refcount (php.Debug)
 * reads the count; passing an argument to a built-in counts nothing.
 */
class RefCountTest {
    @TempDir
    Path dir;

    @Test
    void countsSlots() throws IOException {
        String php = """
                <?php
                class A { public $p; }
                function rc($label, $o) { echo $label, '=', truffle_refcount($o) - 1, "\\n"; }
                function param($x) { return truffle_refcount($x); }
                function local() { $l = new A; return $l; }
                function arrayOf($o) { $x = [$o]; return $x; }
                $a = new A;
                rc('var', $a);
                $b = $a; rc('copy', $a);
                unset($b); rc('unset', $a);
                $arr = [$a]; rc('element', $a);
                unset($arr); rc('array freed', $a);
                $n = [[$a], 1]; unset($n); rc('nested array freed', $a);
                $o = new A; $o->p = $a; rc('property', $a);
                $o->p = null; rc('property overwritten', $a);
                echo 'param=', param($a), "\\n"; rc('after call', $a);
                $r = local(); rc('returned local', $r);
                $y = arrayOf($a); rc('returned array', $a);
                unset($y); rc('returned array freed', $a);
                $ref = &$a; rc('reference', $a);
                unset($ref); rc('reference unset', $a);
                $s = [$a]; $popped = array_pop($s); rc('popped', $a);
                unset($popped, $s);
                $p1 = [$a]; $p2 = $p1; $p2[] = 1; rc('copied array', $a);
                unset($p1, $p2); rc('copies freed', $a);
                """;
        assertEquals("""
                var=1
                copy=2
                unset=1
                element=2
                array freed=1
                nested array freed=1
                property=2
                property overwritten=1
                param=2
                after call=1
                returned local=1
                returned array=2
                returned array freed=1
                reference=1
                reference unset=1
                popped=2
                copied array=3
                copies freed=1
                """, run(php));
    }

    private String run(String php) throws IOException {
        Path script = dir.resolve("rc.php");
        Files.writeString(script, php);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(PhpLanguage.ID).allowExperimentalOptions(true)
                .option("php.Parser", "java").option("php.Debug", "true").out(out).err(out)
                .arguments(PhpLanguage.ID, new String[] {script.toString()}).allowIO(IOAccess.ALL).build()) {
            context.eval(Source.newBuilder(PhpLanguage.ID, script.toFile()).build());
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
