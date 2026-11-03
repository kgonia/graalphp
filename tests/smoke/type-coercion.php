<?php
// Parameter and return types in coercive mode, as WordPress and its plugins declare them: int, float, string and
// bool parameters converting scalars (a fraction lost is deprecated), nullable and defaulted ones, class, union,
// iterable and callable types, references, variadics, and the TypeErrors (with where the call was) for the rest.

function rel($text) {
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
}

set_error_handler(function ($no, $message, $file, $line) {
    echo "  [", $no, "] ", rel($message), " line ", $line, "\n";
    return true;
});

function try_call($label, callable $f) {
    try {
        $result = $f();
        echo $label, ": ";
        var_dump($result);
    } catch (TypeError $e) {
        echo $label, ": ", get_class($e), ": ", rel($e->getMessage()), " (line ", $e->getLine(), ")\n";
    }
}

function as_int(int $x) { return $x; }
function as_float(float $x) { return $x; }
function as_string(string $x) { return $x; }
function as_bool(bool $x) { return $x; }
function maybe_int(?int $x) { return $x; }
function int_default(int $x = 10) { return $x; }
function null_default(?string $x = null) { return $x; }
function int_or_string(int|string $x) { return $x; }
function int_or_float(int|float $x) { return $x; }
function number_string(float|string $x) { return $x; }
function an_array(array $x) { return count($x); }
function an_iterable(iterable $x) { $n = 0; foreach ($x as $v) { $n++; } return $n; }
function a_callable(callable $x) { return $x(2); }
function an_object(object $x) { return get_class($x); }
function by_ref(int &$x) { $x++; }
function many(int ...$xs) { return array_sum($xs); }

class Post { public $ID = 7; }
class Page extends Post {}
interface Shape {}
class Square implements Shape {}
class Label { function __toString(): string { return "label"; } }

function a_post(Post $p) { return $p->ID; }
function a_shape(Shape $s) { return get_class($s); }
function maybe_post(?Post $p = null) { return $p === null ? 'none' : $p->ID; }

$values = [7, 7.0, 7.5, "7", "7.5", " 7", "7 ", "1e3", "abc", "7abc", "", true, false, null, [], new Label];
foreach (['as_int', 'as_float', 'as_string', 'as_bool', 'maybe_int', 'int_or_string', 'int_or_float',
          'number_string'] as $f) {
    foreach ($values as $i => $v) {
        try_call("$f(#$i)", fn() => $f($v));
    }
}
try_call('int_default()', fn() => int_default());
try_call('int_default("3")', fn() => int_default("3"));
try_call('null_default()', fn() => null_default());
try_call('null_default(5)', fn() => null_default(5));
try_call('an_array', fn() => an_array([1, 2]));
try_call('an_array("x")', fn() => an_array("x"));
try_call('an_iterable', fn() => an_iterable(new ArrayIterator([1, 2, 3])));
try_call('an_iterable(1)', fn() => an_iterable(1));
try_call('a_callable', fn() => a_callable(fn($n) => $n * 3));
try_call('a_callable("strlen")', fn() => a_callable('strval'));
try_call('a_callable("nope")', fn() => a_callable('nope'));
try_call('an_object', fn() => an_object(new Page));
try_call('an_object(closure)', fn() => an_object(fn() => 1));
try_call('an_object(1)', fn() => an_object(1));
try_call('a_post(Page)', fn() => a_post(new Page));
try_call('a_post(Square)', fn() => a_post(new Square));
try_call('a_shape', fn() => a_shape(new Square));
try_call('a_shape(Post)', fn() => a_shape(new Post));
try_call('maybe_post()', fn() => maybe_post());
try_call('maybe_post(null)', fn() => maybe_post(null));
try_call('maybe_post("x")', fn() => maybe_post("x"));
$n = "41";
by_ref($n);
var_dump($n);
$s = "x";
try_call('by_ref("x")', function () use (&$s) { by_ref($s); return $s; });
try_call('many', fn() => many(1, "2", 3.0));
try_call('many(bad)', fn() => many(1, 2, "three"));

// returns
function r_int($v): int { return $v; }
function r_nullable($v): ?string { return $v; }
function r_post($v): Post { return $v; }
function r_none(): int { if (false) { return 1; } }
class Factory {
    static function make(): static { return new static; }
    function self(): self { return $this; }
}
class SubFactory extends Factory {}
foreach ([5, "5", 5.0, 5.5, "x", null, true] as $i => $v) {
    try_call("r_int(#$i)", fn() => r_int($v));
    try_call("r_nullable(#$i)", fn() => r_nullable($v));
}
try_call('r_post', fn() => get_class(r_post(new Page)));
try_call('r_post(1)', fn() => r_post(1));
try_call('r_none', fn() => r_none());
try_call('make', fn() => get_class(SubFactory::make()));
try_call('self', fn() => get_class((new SubFactory)->self()));
$closure = function (int $x): string { return $x * 2; };
try_call('closure', fn() => $closure("21"));
try_call('closure(bad)', fn() => $closure("bad"));
try_call('array_map', fn() => array_map(fn(int $x): int => $x + 1, ["1", 2.0]));
try_call('array_map(bad)', fn() => array_map(fn(int $x) => $x, ["x"]));
// a method called through call_user_func
class Hooks {
    function filter(string $value, int $priority = 10): string { return "$value@$priority"; }
}
try_call('call_user_func', fn() => call_user_func([new Hooks, 'filter'], 12, "5"));
try_call('call_user_func(bad)', fn() => call_user_func([new Hooks, 'filter'], [], 1));
