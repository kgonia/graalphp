<?php
// Callable values called through one site: each call binds its own receiver and late-static-binding class,
// whatever the site met first.
class Greeter {
    function __construct(public string $name) {}
    function greet($greeting) { return "$greeting {$this->name} (" . static::class . ')'; }
    static function make($name) { return static::class . "::make($name)"; }
}
class LoudGreeter extends Greeter {
    function greet($greeting) { return strtoupper(parent::greet($greeting)); }
}
function plain($x) { return "plain($x)"; }
function call($callable, ...$args) { return $callable(...$args); }

$callables = [];
foreach (['ann', 'bob', 'cy'] as $name) {
    $callables[] = [new Greeter($name), 'greet'];
    $callables[] = [new LoudGreeter($name), 'greet'];
}
$callables[] = ['Greeter', 'make'];
$callables[] = ['LoudGreeter', 'make'];
$callables[] = 'Greeter::make';
$callables[] = 'plain';
$callables[] = fn($x) => "arrow($x)";
$callables[] = [new LoudGreeter('dee'), 'make'];
for ($round = 0; $round < 3; $round++) {
    foreach ($callables as $c) {
        $result = call($c, "hi$round");
        if ($round == 2) {
            echo $result, "\n";
        }
    }
}
echo implode(',', array_map([new Greeter('map'), 'greet'], ['a', 'b'])), "\n";
echo implode(',', array_map('plain', [1, 2])), "\n";
echo implode(',', array_filter([1, 2, 3, 4], fn($v) => $v % 2 == 0)), "\n";
