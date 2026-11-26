<?php
class D { public $tag; function __construct($tag) { $this->tag = $tag; } function __destruct() { echo $this->tag; } }
function marker() { echo "mark "; return 1; }
function dropA(&$a) { $a = []; return 0; }
function fail() { throw new Exception('failed'); }
function exercise() {
    $x = null ?? new D("coal ");
    $y = $x ?: new D("unused ");
    echo "body "; unset($x); unset($y);
    $a = [0 => new D("first "), 0 => 0, 1 => marker()];
    $a = [[new D("inner ")]];
    $d = $a[0][dropA($a)];
    echo "saved "; unset($d);
    try { foreach ([new D("setup "), fail()] as $v) {} } catch (Exception $e) { echo "caught "; }
    foreach ([7] as $x => $x) {} echo $x;
}
exercise();
