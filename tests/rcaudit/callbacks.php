<?php
// The count audit's fixture for results of PHP code called from runtime code (Refcount.pending, done, borrowed):
// callbacks of built-ins, ArrayAccess, __get. Compare with PHP 8.5 (OPcache off); the known difference left is a
// fresh object offsetGet or __get returns outside a built-in, whose count stays pending until the request's end.
class D { public $n; function __construct($n) { $this->n = $n; } function __destruct() { echo "~{$this->n} "; } }
class A implements ArrayAccess {
    public $kept;
    function __construct() { $this->kept = new D('kept'); }
    function offsetExists($k): bool { return true; }
    function offsetGet($k): mixed { return $k === 'kept' ? $this->kept : new D("fresh-$k"); }
    function offsetSet($k, $v): void {}
    function offsetUnset($k): void {}
}
class M { private $p; function __construct() { $this->p = new D('prop'); } function __get($n) { return $n === 'p' ? $this->p : new D("magic-$n"); } }
echo "walk: "; $a = [1, 2]; array_walk($a, function ($v) { return new D("walk$v"); }); echo "|\n";
echo "reduce: "; $r = array_reduce([1, 2, 3], fn($c, $v) => new D("carry$v")); echo "| "; unset($r); echo "\n";
echo "usort: "; $s = [2, 1]; usort($s, function ($x, $y) { $t = new D('cmp'); return $x <=> $y; }); echo "|\n";
echo "filter: "; $f = array_filter([1], fn($v) => new D('test')); echo "| "; unset($f); echo "\n";
echo "offsetGet kept: "; $o = new A; $x = $o['kept']; unset($x); echo "| "; unset($o); echo "\n";
echo "offsetGet fresh stored: "; $o = new A; $x = $o['f']; echo "| "; unset($x); echo "| "; unset($o); echo "\n";
echo "__get kept: "; $m = new M; $x = $m->p; unset($x); echo "| "; unset($m); echo "\n";
echo "__get fresh stored: "; $m = new M; $x = $m->q; echo "| "; unset($x); echo "| "; unset($m); echo "\n";
echo "handler: "; set_error_handler(function () { return new D('handler'); }); echo $undefined; echo "| "; restore_error_handler(); echo "\n";
echo "end\n";
