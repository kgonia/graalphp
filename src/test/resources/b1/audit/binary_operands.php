<?php
class Piece {
    function __toString() { echo "string\n"; return "left"; }
    function __destruct() { echo "drop\n"; }
}
class Box { public $p; }
function replacePart($box) { $box->p = 0; echo "replace\n"; return "right"; }
function change(&$x) { $x = 5; return 2; }
function calculate() { $x = 1; echo $x + change($x); }
$box = new Box();
$box->p = new Piece();
echo $box->p . replacePart($box);
echo "\n";
calculate();
$x = 1;
echo "/" . ($x + change($x)) . "\n";
