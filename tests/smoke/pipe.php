<?php
// PHP 8.5's `|>`: the operand first, as a value, then the callable (by kind), then the call; at top level (the
// tree) and in a function (bytecode)

function side($label, $value) { echo "side $label\n"; return $value; }
function double(int $x): int { return $x * 2; }
function addRef(&$x) { $x++; return $x; }

class Calc {
    public function __construct(private int $by) {}
    public function times(int $x): int { return $x * $this->by; }
    public static function plus1(int $x): int { return $x + 1; }
    public function __invoke(int $x): int { return $x - 3; }
}

$calc = new Calc(7);
$f = fn($x) => $x * 10;

var_dump(5 |> double(...) |> 'double' |> $calc->times(...) |> [$calc, 'times'] |> Calc::plus1(...)
    |> [Calc::class, 'plus1'] |> $calc |> $f |> (fn($x) => $x + 0.5) |> strval(...) |> 'strlen');
var_dump(side('operand', 3) |> (side('callable', true) ? double(...) : 'strlen'));
var_dump(side('operand', 'ab') |> side('callable', 'strtoupper'));
var_dump("x" |> str_repeat(...) ?? 'none');
var_dump(1 + 2 |> double(...) == 6);
try {
    5 |> 'nope';
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
$n = 1;
try {
    $n |> addRef(...);
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
var_dump($n);
try {
    "a" |> double(...);
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}

function inFunction($calc, $f) {
    var_dump(5 |> double(...) |> 'double' |> $calc->times(...) |> [$calc, 'times'] |> Calc::plus1(...)
        |> $calc |> $f |> (fn($x) => $x + 0.5) |> strval(...) |> 'strlen');
    var_dump(side('operand', 3) |> (side('callable', false) ? double(...) : 'strval'));
    $n = 1;
    try {
        $n |> addRef(...);
    } catch (Error $e) {
        echo $e->getMessage(), "\n";
    }
    var_dump($n);
    return [1, 2, 3] |> array_reverse(...) |> (fn($a) => implode(',', $a));
}
var_dump(inFunction($calc, $f));
