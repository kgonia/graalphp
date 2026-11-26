<?php
class C { function f() { echo 'C'; return static function () {}; } }
class Drop { function __destruct() { echo 'a'; throw new Exception('a'); } }
class Last { function __destruct() { echo 'b'; throw new Exception('b'); } }
function captures() {
    $a = new Drop(); $b = new Last();
    $c = static function () use ($a,$b) {};
    unset($a); unset($b); unset($c);
}
$x = 0;
$f = static function () use (&$x) {
    $x = 1; $x = 2; $x = 3; $x = 4; $x = 5; $x = 6; $x = 7; $x = 8; $x = 9;
};
$f(); echo $x;
$c = new C(); $c->f();
try { captures(); } catch (Exception $e) { $p = $e->getPrevious(); echo $e->getMessage(), $p->getMessage(); }
