<?php
// The count audit's fixture for references and moved elements (fuzzer seed 1000: p1021, p1668, p1112).
// PHP 8.5 prints, one line each: "a b ~self", "~y @1 ~x @2", "@3 ~p @4 ~o4", "@5 ~q", "@6 ~o ~pp".
class D { public $n; public $p; function __construct($n) { $this->n = $n; } function __destruct() { echo "~{$this->n} "; } }
// `$x = &$x`: zend_assign_to_variable_reference binds before it releases, so the reference never reaches zero
function selfRef() { $l = new D('self'); echo "a "; $l = &$l; echo "b "; }
selfRef(); echo "\n";
$w = [new D('x'), new D('y')]; $w = array_shift($w); echo "@1 "; $w = 1; echo "@2\n";
// a shifted (or popped) array keeps its elements on its way to the caller
$v1 = null; $v3 = null; $v1 = &$v3;
$v3 = [[new D('o4')], new D('p')];
echo "@3 "; $v3 = array_shift($v3); echo "@4 "; $v3 = null; echo "\n";
$s = [[new D('q')]]; $t = array_pop($s); echo "@5 "; unset($t); echo "\n";
// `$o->p = &$o->p` binds first too
$o = new D('o'); $o->p = new D('pp'); $o->p = &$o->p; echo "@6 "; unset($o); echo "\n";
