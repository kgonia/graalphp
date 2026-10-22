<?php
class B { public $innerBlocks = []; public $name; function __construct($n) { $this->name = $n; } }
class F { public $block; public $prev; function __construct(B $b, $p = null) { $this->block = $b; $this->prev = $p; } }
class P {
    public $stack = [];
    function run() {
        $this->stack = [];
        array_push($this->stack, new F(new B('outer')));
        array_push($this->stack, new F(new B('inner'), 3));
        $top = array_pop($this->stack);
        $parent = $this->stack[count($this->stack) - 1];
        $parent->block->innerBlocks[] = (array) $top->block;
        $parent->block->innerBlocks[] = ['x' => 1];
        return $parent;
    }
}
$p = new P();
$r = $p->run();
echo count($r->block->innerBlocks), " ", $r->block->innerBlocks[0]['name'], " ", count($p->stack), "\n";
$a = [1, 2];
$b = &$a;
array_push($b, 3);
echo implode(",", $a), "\n";
$o = new stdClass;
$o->list = [];
$o->list[] = 1;
$o->list['k']['j'] = 2;
print_r($o);
var_dump((array) new B('z'));
