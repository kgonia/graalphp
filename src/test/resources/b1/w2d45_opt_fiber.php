<?php
// Pass 1 review P1-7: a function called inside a fiber keeps a cycle in its variables across Fiber::suspend();
// once the fiber is unreachable, the cycle collector sees those variables and collects everything.
class Node {
    public $self;
    public $name;
    function __construct($name) { $this->name = $name; }
    function __destruct() { echo "Node {$this->name} gone\n"; }
}
function holder($tag) {
    $box = new Node($tag);
    $box->self = $box;
    $current = Fiber::getCurrent();
    $value = Fiber::suspend('parked ' . $tag);
    echo "resumed with $value\n";
    return $box->name;
}
function runParked() {
    $fiber = new Fiber(function () { return holder('a'); });
    echo $fiber->start(), "\n";
    unset($fiber);
    echo "unset\n";
    echo "collected ", gc_collect_cycles() > 0 ? "some" : "none", "\n";
}
runParked();
function runResumed() {
    $fiber = new Fiber(function () { return holder('b'); });
    echo $fiber->start(), "\n";
    $fiber->resume('go');
    echo "returned ", $fiber->getReturn(), "\n";
    unset($fiber);
    echo "collected ", gc_collect_cycles() > 0 ? "some" : "none", "\n";
}
runResumed();
// Fixed-arity calls inside a fiber (two and three arguments) bind on frames.
function three($a, $b, $c) { $s = Fiber::suspend("$a$b$c"); return $s + $a + $b + $c; }
function two($a, $b) { return three($a, $b, 3); }
$f = new Fiber(function () { return two(1, 2) + three(4, 5, 6); });
echo $f->start(), "\n";
echo $f->resume(10), "\n";
$f->resume(20);
echo $f->getReturn(), "\n";
echo "end\n";
