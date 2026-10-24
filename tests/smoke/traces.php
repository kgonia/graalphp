<?php
// Stack traces: getTrace, getTraceAsString, debug_backtrace, debug_print_backtrace, as php-src builds them.

// the directory's paths, with "/" after it: Truffle PHP runs the smoke tests on the host
function rel($text) {
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
}

function show(Throwable $e) {
    echo rel($e->getTraceAsString()), "\n";
}

class A {
    public function m($x) { return B::s($x, [1, 2]); }
}
class B {
    public static function s($x, $arr) {
        $x = "modified";
        return array_map(function ($v) { return (new C)->boom($v); }, [1]);
    }
}
class C {
    public function boom($v) {
        throw new LogicException("deep");
    }
}
try {
    (new A)->m("a string longer than fifteen");
} catch (Exception $e) {
    show($e);
    $trace = $e->getTrace();
    var_dump(count($trace), array_keys($trace[0]), $trace[3]['args']);
}

function args(...$all) {
    return new Exception("args");
}
show(args(null, true, false, 1, 1.5, 0.1 + 0.2, "x\ny\\", [1], new stdClass, 2.0, -0.0, 1e100, "it's", "\x01\xff"));

function gen($n) {
    yield 1;
    throw new Exception("from generator");
}
try {
    foreach (gen(5) as $v) {
    }
} catch (Exception $e) {
    show($e);
}
try {
    $g = gen(6);
    $g->current();
    $g->next();
} catch (Exception $e) {
    show($e);
}

$f = function () { return new Exception("closure"); };
show($f());
$nested = function () { return (function () { return new Exception("nested"); })(); };
show($nested());

function thrower() { return str_repeat("x", -1); }
try {
    call_user_func('thrower');
} catch (ValueError $e) {
    show($e);
}

class Counter implements Countable {
    public function count(): int { throw new RuntimeException("count"); }
}
try {
    count(new Counter);
} catch (RuntimeException $e) {
    show($e);
}

class It implements Iterator {
    public function current(): mixed { throw new Exception("current"); }
    public function key(): mixed { return 0; }
    public function next(): void {}
    public function rewind(): void {}
    public function valid(): bool { return true; }
}
try {
    foreach (new It as $v) {
    }
} catch (Exception $e) {
    show($e);
}

class Box implements ArrayAccess {
    public function offsetExists($o): bool { return true; }
    public function offsetGet($o): mixed { throw new Exception("get $o"); }
    public function offsetSet($o, $v): void {}
    public function offsetUnset($o): void {}
}
try {
    $b = new Box;
    $b['k'];
} catch (Exception $e) {
    show($e);
}

class Magic {
    public function __get($name) { throw new Exception("magic $name"); }
}
try {
    (new Magic)->missing;
} catch (Exception $e) {
    show($e);
}

try {
    array_filter([3], function ($v) { return intdiv($v, 0); });
} catch (DivisionByZeroError $e) {
    show($e);
}

function where() {
    $bt = debug_backtrace();
    echo count($bt), " ", $bt[0]['function'], " ", $bt[0]['line'], " ", isset($bt[0]['object']) ? "object" : "-", "\n";
    $bt = debug_backtrace(DEBUG_BACKTRACE_IGNORE_ARGS, 1);
    var_dump(array_keys($bt[0]));
    ob_start();
    debug_print_backtrace();
    echo rel(ob_get_clean());
}
class D {
    public function call() { where(1, "two"); }
}
(new D)->call();
var_dump(debug_backtrace());
echo rel((new Exception("top"))->getTraceAsString()), "\n";

// the compiled-in array_map's fallback (not an array) is a call of the built-in, with its frame
try {
    array_map("%n", 0);
} catch (TypeError $e) {
    echo $e->getMessage(), "\n", rel($e->getTraceAsString()), "\n";
}
