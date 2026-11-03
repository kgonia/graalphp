<?php
// Issue 010: array_unshift keeps the array (and a foreach by reference over it moves with its element, as
// zend_hash_iterators_advance does), the elements' references and the internal pointer's reset.
$a = [1, 2, 3];
foreach ($a as &$v) {
    echo "$v\n";
    if ($v == 2) {
        array_unshift($a, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
unset($v);
var_dump(count($a));

// one value (the intrinsic's path)
$b = [1, 2, 3];
foreach ($b as &$v) {
    echo "$v\n";
    if ($v == 1) {
        array_unshift($b, 'x');
    }
}
unset($v);
echo implode(',', $b), "\n";

// string keys
$c = ['a' => 1, 5 => 2, 'b' => 3];
foreach ($c as $k => &$v) {
    echo "$k=$v\n";
    if ($v == 2) {
        array_unshift($c, 'p', 'q');
    }
}
unset($v);
var_dump($c);

// a reference element stays one
$d = [1, 2];
$r = &$d[1];
array_unshift($d, 0);
$r = 9;
var_dump($d);
array_unshift($d, 'y', 'z');
$r = 10;
var_dump($d);

// the internal pointer goes back to the start
$e = [1, 2, 3];
next($e);
next($e);
array_unshift($e, 0, -1);
var_dump(current($e), key($e));

// a nested loop over a copy keeps its own position
$f = [1, 2, 3];
foreach ($f as &$v) {
    $g = $f;
    foreach ($g as &$w) {
        if ($w == 2) {
            array_unshift($g, 7);
        }
        echo "$v.$w ";
    }
    unset($w);
}
unset($v);
echo "\n";

// unshifting the array into itself
$h = [1, 2];
array_unshift($h, $h);
var_dump($h);
$i = [1];
array_unshift($i, $i, $i);
var_dump($i);

// the same in a function (bytecode)
function inFunction() {
    $a = [1, 2, 3];
    foreach ($a as &$v) {
        echo "$v\n";
        if ($v == 2) {
            array_unshift($a, 0, 0, 0);
        }
    }
    unset($v);
    $b = [1, 2, 3];
    foreach ($b as &$v) {
        echo "$v\n";
        if ($v == 1) {
            array_unshift($b, 'x');
        }
    }
    var_dump(count($a), count($b));
}
inFunction();
