<?php
class Holder { public $first; public $second; }
class Drop {
    public $tag;
    function __construct($tag) { $this->tag = $tag; }
    function __destruct() { echo "$this->tag\n"; }
}
$holder = new Holder();
$a = new Drop("A");
$b = new Drop("B");
$holder->first = $a;
$holder->second = $b;
