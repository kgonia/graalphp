<?php
// `?->`: the whole chain short-circuits (arguments, later links, static members), isset/empty/?? read it quietly,
// a chain is a value as an argument; at top level (the tree) and in functions (bytecode)

class Foo {
    public $bar = 'bar';
    public $child;
    public static $s = 'static';
    function null() { echo "Foo::null()\n"; return null; }
    function self() { echo "Foo::self()\n"; return $this; }
    function value($v) { return $v; }
}

function side($label) { echo "side $label\n"; return $label; }
function takesRef(&$x) { $x = 'set'; }
function show($label, $f) {
    try {
        var_dump($f());
    } catch (Throwable $e) {
        echo $label, ': ', get_class($e), ': ', $e->getMessage(), "\n";
    }
}

$foo = new Foo();
$null = null;

var_dump(null?->bar(side('not run')));
var_dump($null?->a->b->c(side('not run')));
var_dump($foo->null()?->bar(side('not run'))->baz);
var_dump($foo?->self(side('run'))->null()?->bar());
var_dump($null?->bar()::$s);
var_dump($foo?->self()::$s);
var_dump(isset($null?->bar), isset($foo?->bar), isset($foo?->child->x), empty($null?->bar), empty($foo?->bar));
var_dump($undefined?->x ?? 'default', $foo?->bar ?? 'default');
var_dump("{$null?->bar}|{$foo?->bar}");
$a = ['x' => null, 'y' => $foo];
var_dump($a['x']?->bar, $a['y']?->bar, $a['z']?->bar ?? 'missing');
foreach ($foo?->child ?? [1] as $v) {
    var_dump($v);
}
try {
    takesRef($foo?->bar);
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    false?->bar();
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}

function inFunction($foo, $null) {
    var_dump(null?->bar(side('not run')));
    var_dump($null?->a->b->c(side('not run')));
    var_dump($foo->null()?->bar(side('not run'))->baz);
    var_dump($foo?->self(side('run'))->null()?->bar());
    var_dump($null?->bar()::$s);
    var_dump(isset($null?->bar), isset($foo?->bar), isset($foo?->child->x), empty($null?->bar),
        empty($foo?->bar));
    var_dump($undefined?->x ?? 'default', $foo?->bar ?? 'default');
    var_dump($foo?->value(5) + $null?->value(5));
    try {
        takesRef($foo?->bar);
    } catch (Error $e) {
        echo $e->getMessage(), "\n";
    }
    try {
        takesRef($null?->bar);
    } catch (Error $e) {
        echo $e->getMessage(), "\n";
    }
    try {
        (0)?->bar();
    } catch (Error $e) {
        echo $e->getMessage(), "\n";
    }
}
inFunction($foo, $null);
show('arrow', fn() => $null?->value(side('not run')));
var_dump($foo->bar);
