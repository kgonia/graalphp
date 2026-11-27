<?php
// Pass 3 leaf calls: a small function runs without a frame until something needs one. Everything PHP can
// observe must stay exact: diagnostics and their lines, exception traces (with the current argument values),
// destructors run from inside the call, private members, discarded results, and calls that take the
// ordinary path (omitted defaults, callbacks).
class Tag {
    public $name;
    private $secret = 'hidden';
    function __construct($name) { $this->name = $name; }
    function __destruct() { echo "drop {$this->name}\n"; }
    function peek($suffix) { return $this->secret . $suffix; }
}
class Probe {
    function __destruct() {
        $e = new Exception('from destructor');
        foreach ($e->getTrace() as $i => $frame) echo "  #$i ", $frame['function'], '(', count($frame['args'] ?? []), ")\n";
    }
}

function add($a, $b) { return $a + $b; }
function undefined_read($a) { return $a . $missing; }
function warn($a, $b) {
    return $a + $b;
}
function type_error($a, $b) { return $a + $b; }
function trace_me($a, $b) {
    $a = $a * 2;
    return intdiv_like($a, $b);
}
function intdiv_like($a, $b) { return $a % $b; }
function replace_param($x) { $x = 0; return "replaced\n"; }
function drop_in_leaf($x) { $x = null; return 'after drop'; }
function with_default($a, $b = 10) { return $a + $b; }
function nothing() { return true; }
function wrap($a) { return [$a, $a]; }
function named($first, $second) { return "$first-$second"; }

echo add(1, 2), "\n";
echo add(1.5, 2), "\n";
echo undefined_read('x'), "|\n";
echo warn(3, '4 apples'), "\n";
try { type_error([], 1); } catch (TypeError $e) { echo 'TypeError: ', $e->getMessage(), ' @', $e->getLine(), "\n"; }
try { trace_me(5, 0); } catch (DivisionByZeroError $e) {
    echo $e->getMessage(), ' @', $e->getLine(), "\n";
    foreach ($e->getTrace() as $i => $frame) echo "  #$i ", $frame['function'], '(', implode(',', $frame['args']), ') line ', $frame['line'], "\n";
}
echo replace_param(new Tag('param'));
$t = new Tag('local');
echo drop_in_leaf($t), "\n";
unset($t);
echo drop_in_leaf(new Probe()), "\n";
$m = new Tag('method');
echo $m->peek('!'), "\n";
unset($m);
echo with_default(1), ' ', with_default(1, 2), "\n";
$f = 'add';
$g = 'named';
echo $f(20, 22), ' ', call_user_func_array($g, ['x', 'y']), "\n";
nothing();
wrap(new Tag('discarded'));
echo "discarded\n";
for ($i = 0; $i < 3; $i++) echo add($i, $i), ' ';
echo "\n";
echo "done\n";
