<?php
// The built-ins the tree compiles in place (Intrinsics), called from functions on bytecode
// (-Dpolyglot.php.Interpreter=bytecode) as ordinary calls: their results, their by-reference arguments, the
// TypeErrors and ValueErrors they raise and the traces through them and through the callbacks they call.

function show($label, $value) {
    echo $label, ': ', json_encode($value), "\n";
}

function report($label, $e) {
    echo $label, ': ', $e::class, ': ', $e->getMessage(), "\n";
}

class Countable1 implements Countable {
    function count(): int {
        return 42;
    }
}

class Str {
    function __toString(): string {
        return 'Str';
    }
}

function even($v) {
    return $v % 2 == 0;
}

function is_b($k) {
    return $k == 'b';
}

function one_or_b($v, $k) {
    return $v == 1 || $k == 'b';
}

function twice($v) {
    return $v * 2;
}

function types($v) {
    return [is_array($v), is_string($v), is_int($v), is_integer($v), is_long($v), is_float($v), is_double($v),
            is_bool($v), is_null($v), is_object($v), gettype($v)];
}

function type_tests($closure) {
    foreach ([1, 1.5, 'a', true, null, [1], new stdClass(), $closure] as $v) {
        show('types', types($v));
    }
}

function counts() {
    $a = [1, 2, [3, 4]];
    show('count', [count($a), sizeof($a), count($a, COUNT_RECURSIVE), count([]), count(new Countable1())]);
    foreach ([null, 'str', 5, new stdClass()] as $bad) {
        try {
            count($bad);
        } catch (TypeError $e) {
            report('count', $e);
        }
    }
}

function strings() {
    $s = 'Hello World';
    show('strlen', [strlen($s), strlen(''), strlen(123), strlen(1.5), strlen(true), strlen(null ?? ''),
                    strlen(new Str())]);
    show('substr', [substr($s, 0, 5), substr($s, 6), substr($s, -5), substr($s, -5, 2), substr($s, 20),
                    substr($s, 3, -3), substr($s, 0, null), substr('', 0), substr($s, 1, 0)]);
    show('strpos', [strpos($s, 'o'), strpos($s, 'z'), strpos($s, 'o', 5), strpos($s, ''), strpos($s, 'W', -6)]);
    show('contains', [str_contains($s, 'World'), str_contains($s, ''), str_contains($s, 'x'),
                      str_starts_with($s, 'Hell'), str_starts_with($s, 'World'), str_ends_with($s, 'World'),
                      str_ends_with($s, '')]);
    show('ord chr', [ord('A'), ord(6), chr(65), chr(0), chr(255),ord("\xff")]);
    show('case', [strtolower('ÄBC Def'), strtoupper('abc dÉf'), ucfirst('hello'), ucfirst(''), ucfirst('1a')]);
    show('trim', [trim("  a b \n"), ltrim("  a "), rtrim("  a  "), chop("x\n"), trim("\0\t\x0Bx")]);
    show('span', [strspn('42 is the answer', '1234567890'), strcspn('abcd', 'cd'), strspn('foo', 'o', 1, 2),
                  strcspn('hello', 'l', -3), strspn('abc', 'abc', 5)]);
    show('replace', [str_replace('a', 'b', 'banana'), str_replace(['a', 'n'], ['1', '2'], 'banana'),
                     str_replace('x', 'y', ['ax', 'bx']), str_replace(['a'], 'z', 'aaa')]);
    show('implode', [implode(',', [1, 2, 3]), implode([1, 2]), join('-', ['a', 'b']), implode(', ', []),
                     implode('', [true, false, null, 1.5])]);
    show('explode', [explode(',', 'a,b,,c'), explode(',', 'a,b,c', 2), explode(',', 'a,b,c', -1),
                     explode(',', ''), explode('x', 'abc')]);
    try {
        strpos('abc', 'b', 10);
    } catch (ValueError $e) {
        report('strpos', $e);
    }
}

function arrays() {
    $a = ['x' => 1, 'y' => null, 3 => 'three'];
    show('key_exists', [array_key_exists('x', $a), array_key_exists('y', $a), array_key_exists('z', $a),
                        key_exists(3, $a), array_key_exists('3', $a), array_key_exists('', ['' => 1])]);
    show('in_array', [in_array(1, $a), in_array('1', $a), in_array('1', $a, true), in_array(null, $a, true),
                      in_array('three', $a), in_array(0, ['a']), in_array('abc', [0])]);
    show('keys', [array_keys($a), array_keys([])]);
    show('slice', [array_slice([1, 2, 3, 4], 1), array_slice([1, 2, 3, 4], 1, 2), array_slice($a, 1, null, true),
                   array_slice([5 => 'a', 6 => 'b'], 0), array_slice([5 => 'a', 6 => 'b'], 0, 1, true),
                   array_slice([1, 2, 3], -2)]);
    show('filter', [array_filter([0, 1, 2, null, '', 'a']), array_filter([1, 2, 3, 4], 'even'),
                    array_filter(['a' => 1, 'b' => 2], 'is_b', ARRAY_FILTER_USE_KEY),
                    array_filter(['a' => 1, 'b' => 2], 'one_or_b', ARRAY_FILTER_USE_BOTH)]);
    show('map', [array_map('twice', [1, 2, 3]), array_map('strtoupper', ['a' => 'x', 'b' => 'y']),
                 array_map(null, [1, 2])]);
    try {
        array_map('no_such_function', [1]);
    } catch (TypeError $e) {
        report('array_map', $e);
    }
}

function by_reference() {
    $a = [1, 2, 3];
    show('pointer', [current($a), next($a), next($a), next($a), current($a), key($a), reset($a), end($a),
                     prev($a), key($a), pos($a)]);
    $b = $a;
    show('push', [array_push($b, 4), $b, $a]);
    show('pop', [array_pop($b), $b, array_pop($b), $b]);
    show('unshift', [array_unshift($b, 0), $b]);
    $empty = [];
    show('empty', [array_pop($empty), current($empty), key($empty), next($empty), reset($empty), end($empty)]);
    $o = new stdClass();
    $o->list = ['p', 'q'];
    array_push($o->list, 'r');
    show('property', [array_pop($o->list), $o->list, end($o->list), $o->list]);
    $nested = ['in' => [1, 2]];
    array_push($nested['in'], 3);
    show('element', [array_pop($nested['in']), $nested]);
    try {
        $str = 'x';
        array_push($str, 1);
    } catch (TypeError $e) {
        report('array_push', $e);
    }
}

function thrower($v) {
    throw new Exception("in callback $v");
}

function traces() {
    try {
        array_map('thrower', [1]);
    } catch (Exception $e) {
        echo $e->getMessage(), "\n";
        foreach ($e->getTrace() as $frame) {
            echo '  ', $frame['function'], isset($frame['line']) ? ' line ' . $frame['line'] : '', "\n";
        }
    }
    try {
        array_filter([1], 'thrower');
    } catch (Exception $e) {
        echo $e->getMessage(), "\n";
        foreach ($e->getTrace() as $frame) {
            echo '  ', $frame['function'], isset($frame['line']) ? ' line ' . $frame['line'] : '', "\n";
        }
    }
    try {
        in_array(1, 2);
    } catch (TypeError $e) {
        foreach ($e->getTrace() as $frame) {
            echo '  ', $frame['function'], isset($frame['line']) ? ' line ' . $frame['line'] : '', "\n";
        }
    }
}

function gen() {
    $items = yield count([1, 2]);
    yield strlen($items) . ':' . substr($items, 1) . ':' . strtoupper($items);
    $list = [];
    array_push($list, yield 'push me');
    yield $list;
}

$closure = function () {
};
type_tests($closure);
counts();
strings();
arrays();
by_reference();
traces();
$g = gen();
show('gen', $g->current());
show('gen', $g->send('abc'));
show('gen', $g->send('pushed'));
