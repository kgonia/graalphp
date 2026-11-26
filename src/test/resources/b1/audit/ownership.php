<?php
class Box {
    public $tag;
    function __construct($tag) { $this->tag = $tag; }
    function __destruct() { echo "drop:$this->tag\n"; }
    function consume($unused) { echo "method:$this->tag\n"; }
}
function replace(&$x) { $x = 7; echo "replaced\n"; }
function consume($x, $unused) { echo "consume:$x->tag\n"; $x = 0; echo "body\n"; }
function aliasOf($x) { return $x; }
$x = new Box("file");
consume($x, replace($x));
function local() {
    $x = new Box("local");
    consume($x, replace($x));
    $x = new Box("returned");
    consume(aliasOf($x), replace($x));
    $x = new Box("receiver");
    $x->consume(replace($x));
}
local();
