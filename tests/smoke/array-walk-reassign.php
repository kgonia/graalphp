<?php
// Issue 066: array_walk on an array the callback replaces goes on with the new array.
$a = [1, 2, 3];
array_walk($a, function (&$v, $k) use (&$a) { if ($k === 0) $a = ['x' => 1]; echo $k; });
echo "\n";
var_dump($a);
