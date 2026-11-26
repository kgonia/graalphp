<?php
class Drop {
    public $tag;
    function __construct($tag) { $this->tag = $tag; }
    function __destruct() { echo "drop:$this->tag\n"; $tag = $this->tag; throw new Exception($tag); }
}
function make($tag) { return new Drop($tag); }
function inner($a, $b, $c) { echo "unreachable"; }
function outer($a, $b) { echo "unreachable"; }
function boom() { throw new Exception("boom"); }
try { outer(make("outer"), inner(make("inner1"), make("inner2"), boom())); }
catch (Exception $a) {
    $b = $a->getPrevious();
    $c = $b->getPrevious();
    $d = $c->getPrevious();
    echo $a->getMessage() . "/" . $b->getMessage() . "/" . $c->getMessage() . "/" . $d->getMessage() . "\n";
}
