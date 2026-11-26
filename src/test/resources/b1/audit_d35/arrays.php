<?php
function bump(&$x) { ++$x; }
function exercise() {
    $a = ['s' => 1, -5 => 2, 3];
    foreach ($a as $k => $v) echo $k, ' ';
    $a = [3]; $b = $a; unset($b[0]); bump($a[0]); echo count($b), $a[0], ' ';
    $i = 0; $a = []; $a[$i++] = $i++; $a[$i] = $i++;
    foreach ($a as $k => $v) echo $k, '=', $v, ' ';
    try { $a = 42; $a[] = 1; } catch (Error $e) { echo $e->getMessage(), "\n"; }
    try { $a = [9223372036854775807 => []]; $a[][] = 1; } catch (Error $e) { echo $e->getMessage(), "\n"; }
    try { $a = [0,1,2,3,4,5,6,7,8,9,10,11,12,13,9223372036854775807 => 1,2]; } catch (Error $e) { echo $e->getMessage(), "\n"; }
}
exercise();
