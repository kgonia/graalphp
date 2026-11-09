<?php
// Issue 066: array_walk on an array the callback replaces goes on with the new array.
$a = [1, 2, 3];
array_walk($a, function (&$v, $k) use (&$a) { if ($k === 0) $a = ['x' => 1]; echo $k; }); echo "\n"; var_dump($a);
$a = [1, 2, 3];
array_walk($a, function (&$v, $k) use (&$a) { if ($k === 0) unset($a[1]); echo $k; $v *= 10; }); echo "\n"; var_dump($a);
$a = [1, 2];
array_walk($a, function (&$v, $k) use (&$a) { if ($k === 0) $a[] = 9; echo $k; }); echo "\n";
$a = [1, 2];
try { array_walk($a, function (&$v, $k) use (&$a) { $a = 'str'; echo $k; }); } catch (TypeError $e) { echo "\n", $e->getMessage(), "\n"; }
$a = ['a' => 1, 'b' => 2];
array_walk($a, function (&$v, $k, $x) { $v = "$k$x"; }, '!'); var_dump($a);
$b = $a; array_walk($a, function (&$v) { $v = 0; }); var_dump($a, $b);
