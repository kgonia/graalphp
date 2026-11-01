<?php
// match (php-src's zend_compile_match): strict comparison, arms in order, conditions evaluated only until one
// matches, the subject once; no arm matched throws UnhandledMatchError, its value shown as
// zend_match_unhandled_error shows it.
ob_start(function ($text) {
    // "/" after the directory: Truffle PHP runs the smoke tests on the host
    return str_replace([__FILE__, __DIR__ . "\\", __DIR__ . "/"], ["FILE", "DIR/", "DIR/"], $text);
});
function trace($label, $value) {
    echo "[$label]";
    return $value;
}
function describe($value) {
    return match ($value) {
        1, 2 => "one or two",
        "1" => "the string 1",
        1.0 => "the float 1",
        true => "true",
        null => "null",
        [] => "an empty array",
        [1, 2] => "a list",
        default => "something else",
    };
}
foreach ([1, 2, "1", 1.0, true, false, null, [], [1, 2], [2, 1], "2", 0] as $value) {
    echo var_export($value, true), ": ", describe($value), "\n";
}

// the subject is evaluated once, conditions until one matches
$r = match (trace("subject", 3)) {
    trace("a", 1), trace("b", 2) => "first",
    trace("c", 3) => "second",
    trace("d", 3) => "third",
    default => "default",
};
echo " => $r\n";
$r = match (trace("subject", 9)) {
    trace("a", 1) => "first",
    default => trace("default body", "default"),
    trace("c", 2) => "after the default",
};
echo " => $r\n";

// match(true) with expressions
function grade($score) {
    return match (true) {
        $score >= 90 => "A",
        $score >= 80 => "B",
        $score >= 70, $score >= 65 => "C",
        default => "F",
    };
}
foreach ([95, 85, 72, 66, 10] as $s) {
    echo $s, " ", grade($s), "\n";
}

// as an expression anywhere: nested, in arguments, in arrays, in a string, in an arrow function, with throw
$x = 2;
echo strtoupper(match ($x) { 1 => "a", 2 => "b" }), "\n";
echo match ($x) { 2 => match ($x * 2) { 4 => "nested four", default => "nested other" }, default => "outer" }, "\n";
$list = [match ($x) { 2 => "in", default => "out" } => match ($x) { 1 => 1, 2 => 22 }];
var_dump($list);
$f = fn($v) => match ($v) { 'a' => 'A', 'b' => 'B', default => '?' };
echo $f('a'), $f('b'), $f('c'), "\n";
echo "sum: ", match (1) { 1 => 10 } + match (2) { 2 => 20 }, "\n";
try {
    echo match ($x) { 1 => "one", default => throw new LogicException("thrown in an arm") };
} catch (LogicException $e) {
    echo "caught: ", $e->getMessage(), "\n";
}
$y = match ($x) { 2 => null };
var_dump($y);
echo match ($x) { 2, => "trailing commas", }, "\n";

// no arm: UnhandledMatchError
class Foo {}
$values = [5, -7, PHP_INT_MIN, 1.5, 2.0, -0.0, 1e100, 1.0E-10, 0.1 + 0.2, INF, -INF, NAN, true, false, null, "",
    "abc", "a long string over fifteen bytes", "fifteen bytes!!", "sixteen bytes!!!", "tab\tnew\nline\r",
    "esc\e\back\x00nul\x7f\xff\x0b\x0c", [], [1], new Foo, new stdClass, function () {}, fopen(__FILE__, "r")];
foreach ($values as $value) {
    try {
        echo match ($value) { "never" => 1 };
    } catch (\UnhandledMatchError $e) {
        echo get_class($e), " (", $e instanceof Error ? "an Error" : "not an Error", "): ", $e->getMessage(), "\n";
    }
}
try {
    match ($x) {};
} catch (UnhandledMatchError $e) {
    echo "empty: ", $e->getMessage(), " line ", $e->getLine(), "\n";
}
// the line: of the match
try {
    $r = match (
        $x
    ) {
        1 => "one",
        3
            => "three",
    };
} catch (UnhandledMatchError $e) {
    echo "line ", $e->getLine(), "\n";
}
try {
    $r = match ($x) {
        1 => "one",
        "x" . "y", strlen("ab") + 1 => "a computed one",
    };
} catch (UnhandledMatchError $e) {
    echo "line ", $e->getLine(), "\n";
}
// the same in a function
function unhandled($value) {
    return match ($value) {
        "never", "again" => 1,
    };
}
foreach ([5, "x", 1.5, null, [], new Foo] as $value) {
    try {
        unhandled($value);
    } catch (UnhandledMatchError $e) {
        echo $e->getMessage(), " line ", $e->getLine(), "\n";
    }
}
// the ini settings the message follows
ini_set('zend.exception_string_param_max_len', 3);
try { match ("abcdef") { 1 => 1 }; } catch (UnhandledMatchError $e) { echo $e->getMessage(), "\n"; }
ini_set('zend.exception_string_param_max_len', 0);
try { match ("abcdef") { 1 => 1 }; } catch (UnhandledMatchError $e) { echo $e->getMessage(), "\n"; }
try { match (12) { 1 => 1 }; } catch (UnhandledMatchError $e) { echo $e->getMessage(), "\n"; }
ini_set('zend.exception_string_param_max_len', 15);
ini_set('zend.exception_ignore_args', 1);
try { match ("abcdef") { 1 => 1 }; } catch (UnhandledMatchError $e) { echo $e->getMessage(), "\n"; }
try { match (12) { 1 => 1 }; } catch (UnhandledMatchError $e) { echo $e->getMessage(), "\n"; }
ini_set('zend.exception_ignore_args', 0);

// in a loop, each arm taken in turn (a compiled match deoptimizes for an arm met late)
$counts = [];
for ($i = 0; $i < 3000; $i++) {
    $k = match ($i % 7) { 0, 1 => "low", 2 => "two", 3, 4, 5 => "mid", default => "six" };
    $counts[$k] = ($counts[$k] ?? 0) + 1;
    if ($i == 2500) {
        $k = match ($i) { 2500 => "late", default => "never" };
        $counts[$k] = 1;
    }
}
ksort($counts);
print_r($counts);
// an uncaught one
match ("the end") { 1 => 1 };
