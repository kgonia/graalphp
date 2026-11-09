<?php
// Returned arrays keep their elements while the epilogue runs: a referenced variable, references to elements,
// an object freed in the epilogue that held the array. PHP 8.5 prints "| ~NAME" on every line.
class D { public $n; function __construct($n) { $this->n = $n; } function __destruct() { echo "~{$this->n} "; } }
function w1($a) { array_walk($a, function (&$d, $k) {}); return $a; }
$x = w1([new D('w1')]); echo "| "; unset($x); echo "\n";
function w2() { $a = [new D('w2')]; foreach ($a as &$d) {} unset($d); return $a; }
$x = w2(); echo "| "; unset($x); echo "\n";
function w3() { $a = [new D('w3')]; $r = &$a[0]; unset($r); return $a; }
$x = w3(); echo "| "; unset($x); echo "\n";
$x = (new ArrayObject([new D('ao')]))->getArrayCopy(); echo "| "; unset($x); echo "\n";
$o = new ArrayObject([new D('ao2')]); $x = $o->getArrayCopy(); unset($o); echo "| "; unset($x); echo "\n";
function f() { $b = [new D('f')]; $r = &$b; return $b; }
$x = f(); echo "| "; unset($x); echo "\n";
