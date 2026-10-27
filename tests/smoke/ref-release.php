<?php
// A reference no variable is bound to any more is freed in php-src, and so is the array it held: the references
// in that array lose a holder, and a copy elsewhere gets their values. Found by models/cow (wiki/tla-cow.md, F5).

// a local made a reference ($alias = &$arr), released when the function returns
function aliased() {
    $x = 1;
    $arr = [&$x];
    $alias = &$arr;
    $keep = [&$x];
    return $keep;
}
$k = aliased();
$c = $k;
$c[0] = 5;
echo json_encode($k), " ", json_encode($c), "\n";

// a local made a reference by a by-reference argument
function byRefParam(&$v) {
}
function passed() {
    $x = 1;
    $arr = [&$x];
    byRefParam($arr);
    $keep = [&$x];
    return $keep;
}
$k = passed();
$c = $k;
$c[0] = 5;
echo json_encode($k), " ", json_encode($c), "\n";

// a local iterated by reference, whose elements were references
function iterated() {
    $x = 1;
    $arr = [&$x, 2];
    foreach ($arr as &$v) {
    }
    unset($v);
    $keep = [&$x];
    return $keep;
}
$k = iterated();
$c = $k;
$c[0] = 5;
echo json_encode($k), " ", json_encode($c), "\n";

// top level: the reference outlives its variables only through an array
$x = 1;
$arr = [&$x];
$alias = &$arr;
$keep = [&$x];
unset($arr, $alias, $x);
$c = $keep;
$c[0] = 5;
echo json_encode($keep), " ", json_encode($c), "\n";

// still bound somewhere: the copy shares it
function stillBound() {
    static $s = null;
    $x = 1;
    $s = [&$x];
    $keep = [&$x];
    return $keep;
}
$k = stillBound();
$c = $k;
$c[0] = 5;
echo json_encode($k), " ", json_encode($c), "\n";
