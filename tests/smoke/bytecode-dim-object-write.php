<?php
// The object of a property write fetched from an element (FETCH_DIM_W): an ArrayAccess's offsetGet() alone, in a
// function as at the top level
class Offsets implements ArrayAccess {
    function offsetExists($o): bool { echo "offsetExists($o)\n"; return true; }
    function offsetGet($o): mixed { echo "offsetGet($o)\n"; return new stdClass; }
    function offsetSet($o, $v): void { echo "offsetSet($o)\n"; }
    function offsetUnset($o): void { echo "offsetUnset($o)\n"; }
}
function inFunction() {
    $o = new Offsets;
    $o['k']->p = 1;
    $a = ['k' => new stdClass];
    $a['k']->p = 2;
    var_dump($a['k']->p);
}
inFunction();
$o = new Offsets;
$o['k']->p = 1;
