<?php
// Issue 059: The error handler copies the array while an element write is under way ("Undefined array
// key"): PHP drops the write, and the copy doesn't see it either.
set_error_handler(function () { global $a, $c; $c = $a; return true; });
$a = ['x' => 1]; $r = ($a['y'] .= 'z'); var_dump($r, $a, $c);
$a = ['x' => 1]; $r = $a['y']++; var_dump($r, $a);
$a = ['x' => 1]; $r = ++$a['y']; var_dump($r, $a);
$a = ['x' => 1]; $r = ($a['p']['q'] .= 'z'); var_dump($r, $a);
set_error_handler(function () { global $a; $a = 'gone'; return true; });
$a = ['x' => 1]; $r = ($a['y'] .= 'z'); var_dump($r, $a);
set_error_handler(function ($n, $m) { echo "warn: $m\n"; return true; });
$a = ['x' => 1]; $a['y'] .= 'z'; $a['p']['q'] .= 'w'; $a['n']++; var_dump($a);
class O { public $arr = []; }
set_error_handler(function () { global $o, $c; $c = $o->arr; return true; });
$o = new O; $o->arr['k'] .= 'v'; var_dump($o->arr, $c);
