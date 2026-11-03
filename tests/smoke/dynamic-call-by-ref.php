<?php
// Issue 001: a dynamic call ($f(), $obj->$m(), [$obj, 'm'](), 'A::m'(), $closure->__invoke(), an invokable object,
// new $cls) passes a by-reference parameter its argument's reference, as php-src's INIT_DYNAMIC_CALL resolves the
// callee before SEND_VAR_EX looks at its parameters; at file level (the tree) and in a function (bytecode).

set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (line $line)\n";
    return true;
});

function g(&$x) { $x .= 'g'; }
function gv($x) { $x .= 'v'; }
function &rr() { static $s = 'rr'; return $s; }
function plain() { return 'plain'; }
function keep(&$x) { echo gettype($x), "\n"; }

class A {
    public $p = 'p';
    public static $s = 's';
    function m(&$x) { $x .= 'm'; }
    static function sm(&$x, &$y = null) { $x .= 'sm'; $y = 'y'; }
    function __invoke(&$x) { $x .= 'i'; }
    function __call($n, $args) { $args[0] .= 'call'; return $n; }
    static function __callStatic($n, $args) { $args[0] .= 'cs'; return $n; }
}

class Ctor {
    function __construct(&$x) { $x .= 'ctor'; }
}

$f = function (&$x) { $x .= 'f'; };
$a = '';
$f($a);
echo "closure: $a\n";
$name = 'g';
$name($a);
echo "name: $a\n";
$name = 'gv';
$name($a);
echo "by value: $a\n";
$o = new A;
$m = 'm';
$o->$m($a);
echo "dynamic method: $a\n";
[$o, 'm']($a);
echo "array callable: $a\n";
['A', 'sm']($a, $b);
echo "static array callable: $a $b\n";
$sm = 'A::sm';
$sm($a);
echo "string static: $a\n";
$f->__invoke($a);
echo "closure __invoke: $a\n";
$o($a);
echo "invokable object: $a\n";
$o->__invoke($a);
echo "__invoke by name: $a\n";
$call = 'nothing';
$o->$call($a);
echo "__call by value: $a\n";
['A', 'nothing']($a);
echo "__callStatic by value: $a\n";
$cls = 'Ctor';
new $cls($a);
echo "new dynamic: $a\n";
$fc = $o->m(...);
$fc($a);
echo "first-class: $a\n";

// elements, properties, static properties as arguments
$arr = [];
$f($arr['k']);
$f($arr['n']['m']);
var_dump($arr);
$f($o->p);
$std = new stdClass;
$f($std->dyn);
echo "{$o->p} {$std->dyn}\n";
$f(A::$s);
echo A::$s, "\n";
$prop = 'p';
$f($o->$prop);
echo $o->p, "\n";

// a call's result: a reference when the callee returns one, else a notice
$f(rr());
echo rr(), "\n";
$f(plain());
// a value that isn't a variable or a call: php-src's Error (SEND_VAL_EX) once it is evaluated, the later
// arguments not; `new`, include and yield (IS_VAR results) are passed with a notice as a call's value is
function t($what, $f) {
    try {
        $f();
        echo "$what: ok\n";
    } catch (Error $e) {
        echo "$what: ", get_class($e), ': ', str_replace(__FILE__, 'FILE', $e->getMessage()), "\n";
    }
}
t('literal', function () use ($f) { $f('literal'); });
t('name', function () { $name = 'g'; $name(1); });
t('array callable', function () use ($o) { [$o, 'm'](2); });
t('static', function () { A::sm($a, 3); });
t('direct', function () { g(1); });
t('globals', function () { g($GLOBALS); });
t('in place', function () { g(strlen('x')); });
t('assignment', function () { g($a = 1); });
t('increment', function () { $a = 1; g($a++); });
t('cast', function () { g((int) '1'); });
t('ternary', function () { $a = 's'; g($a ?: 'x'); });
t('silenced', function () { $a = 's'; g(@$a); });
t('order', function () { g(1, print("not printed\n")); });
t('order dynamic', function () { $n = 'g'; $n(1, print("not printed\n")); });
t('constant', function () { g(PHP_EOL); });
t('array', function () { g([1]); });
t('clone', function () { keep(clone new A); });
t('new', function () { keep(new A); });
t('include', function () { g(include __DIR__ . '/dynamic-call-by-ref.inc'); });
t('yield', function () { $gen = (function () { g(yield); })(); $gen->current(); $gen->send('sent'); });
t('call', function () { g(plain()); });
t('this', function () { $x = new class { function run() { keep($this); } }; $x->run(); });
t('spread', function () { g(...[1]); });
t('reference assigned', function () { $b = 'b'; g($a = &$b); echo "$a $b\n"; });
t('reference assigned dynamic', function () { $n = 'g'; $b = 'b'; $n($a = &$b); echo "$a $b\n"; });
// at file level (the tree)
$rb = 'b';
g($ra = &$rb);
echo "$ra $rb\n";
try {
    $f('literal');
} catch (Error $e) {
    echo get_class($e), ': ', str_replace(__FILE__, 'FILE', $e->getMessage()), "\n";
}
try {
    g(1, print("not printed\n"));
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
keep(new A);
try {
    g($GLOBALS);
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

function inside() {
    $f = function (&$x) { $x .= 'f'; };
    $a = '';
    $f($a);
    $name = 'g';
    $name($a);
    $o = new A;
    $m = 'm';
    $o->$m($a);
    [$o, 'm']($a);
    ['A', 'sm']($a, $b);
    $sm = 'A::sm';
    $sm($a);
    $f->__invoke($a);
    $o($a);
    $o->__invoke($a);
    $call = 'nothing';
    $o->$call($a);
    $cls = 'Ctor';
    new $cls($a);
    $fc = $o->m(...);
    $fc($a);
    echo "inside: $a $b\n";
    $arr = [];
    $f($arr['k']);
    $f($o->p);
    $f(A::$s);
    var_dump($arr, $o->p, A::$s);
    $f(plain());
    try {
        $f('literal');
    } catch (Error $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
inside();
