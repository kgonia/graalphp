<?php
// The built-ins compiled in place on bytecode (BytecodeTranslator.intrinsic): what their fast paths leave to the
// built-in, as the built-in's call shows it in traces (its frame, the arguments passed, no defaults filled in; no
// frame for a call php-src compiles into an opcode of its own), by-reference arguments that aren't places, the
// copy-on-write of a moved pointer, references kept by array_slice, and each in a loop long enough for the
// cached interpreter. Left out: the cases where the built-ins themselves differ from php-src (an array or an
// object where a string or int is required converts instead of a TypeError; next() of a string and array_pop() of
// an object return; explode()'s empty-separator message), which the call and the in-place forms share.
namespace {
    function frames(Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), ' line ', $e->getLine(), "\n";
        foreach ($e->getTrace() as $frame) {
            echo '  ', $frame['function'], isset($frame['line']) ? ' line ' . $frame['line'] : '',
                ' args ', json_encode($frame['args'] ?? null), "\n";
        }
    }

    function errors() {
        foreach ([
            fn() => strlen([]),
            fn() => strpos('abc', 'b', 9),
            fn() => in_array(1, 'x'),
            fn() => array_slice('x', 1),
            fn() => array_keys('x'),
        ] as $f) {
            try {
                $f();
            } catch (Throwable $e) {
                frames($e);
            }
        }
    }

    function refs() {
        $a = [1, 2, 3];
        $b = $a;
        echo json_encode([next($b), current($a), current($b), end($b), current($a)]), "\n";
        $x = 1;
        $list = [&$x, 2, 3];
        $slice = array_slice($list, 0, 2);
        $slice[0] = 'changed';
        echo json_encode([$x, $list, $slice]), "\n";
        echo json_encode(next(...[[1, 2]])), "\n";
    }

    function loops() {
        $sum = 0;
        $out = [];
        for ($i = 0; $i < 3000; $i++) {
            $s = str_repeat('ab ', $i % 5) . ($i % 3 ? 'X' : '');
            $list = range(0, $i % 6);
            $sum += strlen($s) + strpos(" $s", ' ') + (int) str_contains($s, 'X') + (int) str_starts_with($s, 'ab')
                + (int) str_ends_with($s, 'X') + strspn($s, 'ab') + strcspn($s, 'X', 1) + count(explode(' ', $s))
                + strlen(substr($s, 1, -1)) + strlen(trim($s)) + strlen(rtrim($s)) + strlen(ucfirst($s))
                + strlen(strtoupper($s)) + strlen(str_replace('ab', 'c', $s)) + (int) in_array($i % 4, $list)
                + count(array_slice($list, 1, 2)) + count(array_keys($list)) + (int) is_array($list)
                + (int) is_string($s) + (int) is_int($i);
            next($list);
            $sum += current($list) + key($list);
            array_push($list, $i);
            array_unshift($list, $i);
            $sum += array_pop($list) + end($list) + reset($list);
        }
        echo $sum, "\n";
    }

    errors();
    refs();
    loops();
}

namespace Shadow {
    // unqualified in a namespace: the global built-in while the namespace has none, then the namespace's
    function show($label, $value) {
        echo $label, ': ', json_encode($value), "\n";
    }

    function before() {
        return [strlen('abc'), trim(' x '), is_array([]), in_array(1, [1])];
    }

    show('before', before());
    function strlen($s) {
        return 'own strlen';
    }
    function trim($s) {
        return 'own trim';
    }
    show('after', [strlen('abc'), trim(' x '), is_array([]), \strlen('abc')]);
}
