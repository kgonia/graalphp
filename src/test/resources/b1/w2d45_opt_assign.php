<?php
// Pass 1 review P0-3, P0-5 and P0-6: ownership of assigned values and of $obj->$name operands.
class Dtor {
    public $n;
    function __construct($n) { $this->n = $n; }
    function __destruct() { echo "Dtor{$this->n} gone\n"; }
}
class H { public int $typedInt = 0; }

// ZEND_ASSIGN used as a value into a reference to a typed property: the TypeError frees the value at once.
function assignTyped() {
    $h = new H();
    $x = &$h->typedInt;
    try { $y = ($x = new Dtor(1)); } catch (TypeError $e) { echo "caught: ", $e->getMessage(), "\n"; }
    echo "after assign\n";
}
assignTyped();

// Dynamic properties on generators and fibers are refused before the value is counted.
function gen() { yield 1; }
function dynamicOnGenerator() {
    $g = gen();
    $k = "prop";
    try { $g->$k = new Dtor(2); } catch (Error $e) { echo "caught: ", $e->getMessage(), "\n"; }
    echo "after generator\n";
}
dynamicOnGenerator();
function dynamicOnFiber() {
    $f = new Fiber(function () {});
    $k = "prop";
    try { $f->$k = new Dtor(3); } catch (Error $e) { echo "caught: ", $e->getMessage(), "\n"; }
    echo "after fiber\n";
}
dynamicOnFiber();

// $o->$k = dropK(): the object and the name are fetched when the assignment executes, after the value.
#[AllowDynamicProperties]
class Box {
    public $id;
    public $x;
    function __construct($id) { $this->id = $id; }
    function __destruct() { echo "Box{$this->id} gone\n"; }
}
$o = new Box(1);
$k = 'x';
function dropK() { global $o, $k; $o = new Box(2); $k = 'y'; echo "dropK\n"; return 'v'; }
function writeNamed() {
    global $o, $k;
    $o->$k = dropK();
    var_dump($o->id, $o->x, $o->y);
}
writeNamed();
// A computed receiver is held by its temporary while the value runs.
function freshBox() { return new Box(3); }
function writeComputed() { $name = 'x'; freshBox()->$name = dropK(); echo "after computed\n"; }
writeComputed();
echo "end\n";
