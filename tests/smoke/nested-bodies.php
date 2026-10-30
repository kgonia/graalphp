<?php
// Function bodies inside function bodies: an arrow function captures the variables its nested arrow functions use,
// a closure that uses $this only in a nested arrow function still binds it, and functions, closures and methods
// declared inside other bodies run their own bodies when called.
$x = 1;
$y = 2;
$twice = fn() => fn() => $x + $y;
var_dump($twice()());

$deep = fn($a) => fn($b) => fn($c) => $a + $b + $c + $x;
var_dump($deep(10)(20)(30));

class Box {
    public $value = 42;

    public function reader() {
        return function () {
            return fn() => $this->value;
        };
    }

    public function counter() {
        $count = 0;
        return function () use (&$count) {
            $inner = function () use (&$count) {
                return ++$count;
            };
            return $inner() + $inner();
        };
    }
}
$box = new Box();
var_dump($box->reader()()());
var_dump($box->counter()());

function outer($n) {
    if (!function_exists('declared_inside')) {
        function declared_inside($m) {
            $square = fn($k) => $k * $k;
            return $square($m) + 1;
        }
    }
    return declared_inside($n);
}
var_dump(outer(3), declared_inside(4));

function makeGenerator() {
    $make = function () {
        yield 1;
        yield 2;
    };
    // not a generator itself: the yields are in the closure
    return $make();
}
foreach (makeGenerator() as $value) {
    echo $value, "\n";
}

$levels = function ($a) {
    return function ($b) use ($a) {
        return function ($c) use ($a, $b) {
            return "$a-$b-$c";
        };
    };
};
var_dump($levels("a")("b")("c"));
