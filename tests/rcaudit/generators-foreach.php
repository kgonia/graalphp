<?php
// The count audit's fixtures: a generator's count from its call, foreach over objects holding a count (FE_RESET),
// generators destroyed at zero (finally blocks, frame freed). PHP 8.5 prints:
// [a] [b] [n] ~plain | [0] [1] ~it | [1] finally | finally | ret finally | end
class D { public $a = 1; public $b = 2; public $n; function __construct($n) { $this->n = $n; } function __destruct() { echo "~{$this->n} "; } }
class It implements Iterator { private $i = 0; function __destruct() { echo "~it "; }
  function current(): mixed { return $this->i; } function key(): mixed { return $this->i; }
  function next(): void { $this->i++; } function rewind(): void { $this->i = 0; } function valid(): bool { return $this->i < 2; } }
function gen() { try { yield 1; yield 2; } finally { echo "finally "; } }
$o = new D('plain'); foreach ($o as $k => $v) { unset($o); echo "[$k] "; } echo "| ";
$i = new It; foreach ($i as $v) { unset($i); echo "[$v] "; } echo "| ";
$g = gen(); foreach ($g as $v) { echo "[$v] "; break; } unset($g); echo "| ";
gen()->current(); echo "| ";
function f() { $g = gen(); $g->current(); echo "ret "; } f(); echo "| ";
echo "end\n";
