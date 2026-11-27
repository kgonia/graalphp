<?php
// Pass 2: fast frames are reused per depth. Traces, func_get_args, extras, results and destructors that call
// functions while a frame is leaving must all see exactly what PHP shows.
function leaf($a, $b = 2) { return func_get_args(); }
function thrower($x) { throw new Exception("boom $x"); }
function middle($x, ...$rest) { return thrower($x + count($rest)); }
function outer($x) { try { return middle($x, 'e1', 'e2'); } catch (Exception $e) { return $e->getTraceAsString(); } }
echo outer(5), "\n";
var_dump(leaf(1), leaf(1, 2, 3, 4));

class Noisy {
    public $name;
    public function __construct($name) { $this->name = $name; }
    public function __destruct() { echo "destruct {$this->name}: ", helper($this->name), "\n"; }
}
function helper($n) { return '[' . $n . ']:' . implode(',', leaf($n, 'x')); }
function withLocal($v) { $o = new Noisy("local$v"); return $v * 10; }
echo withLocal(3), "\n";
function extrasAndLocal($v) { $o = new Noisy("extra$v"); return func_get_args(); }
var_dump(extrasAndLocal(1, new Noisy('passed'), 'tail'));

function deep($n) {
    if ($n === 0) { $e = new Exception('deep'); return count($e->getTrace()); }
    return deep($n - 1);
}
echo deep(70), "\n";
echo deep(3), "\n";

function apply($value, ...$args) { array_unshift($args, $value); return $args; }
var_dump(apply('v', 1, 2));
class Cb { public static function s($a, $b = 'B') { return "s($a,$b)"; } public function m($a) { return "m($a)"; } }
$args = ['a', 'b', 'c'];
echo call_user_func_array(['Cb', 's'], array_slice($args, 0, 2)), "\n";
echo call_user_func_array([new Cb, 'm'], array_slice($args, 1, 1)), "\n";
echo call_user_func_array(['Cb', 's'], array_slice($args, -1)), "\n";
echo call_user_func_array(fn($x, $y) => "$x$y", array_slice($args, 1)), "\n";
echo call_user_func_array('trim', array_slice($args, 2, null)), "\n";
function rec($n) { return $n <= 1 ? $n : rec($n - 1) + rec($n - 2); }
echo rec(15), "\n";
