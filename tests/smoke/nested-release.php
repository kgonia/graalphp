<?php
// An array freed by php-src frees the arrays nested in it, so the references in them lose a holder and a copy
// elsewhere gets their values (wiki/tla-cow.md, F4). Truffle PHP releases an array's elements at its last holder
// and takes them back at its next (RefCounts, the destructors prototype); these check that copy-on-write stays as
// php-src's on the way.

// F4's trace: the nested array freed with its container
$y = 1; $p = [&$y]; $q = [&$y];
$o = [$q]; unset($q);
unset($o);
unset($y);
$r = $p; $r[0] = 9;
echo json_encode($p), " ", json_encode($r), "\n";

// the same in a function, the container freed at its end
function nested() {
    $y = 1; $p = [&$y]; $q = [&$y];
    $o = [$q]; unset($q);
    return $p;
}
$p = nested();
$r = $p; $r[0] = 9;
echo json_encode($p), " ", json_encode($r), "\n";

// a returned array of arrays: its elements are taken back when it is stored, so a write copies
function arrays() {
    $inner = [1, 2];
    $outer = [$inner, $inner];
    return $outer;
}
$a = arrays();
$b = $a;
$b[0][] = 3;
echo json_encode($a), " ", json_encode($b), "\n";

// a nested array popped and pushed back
$stack = [[1], [2]];
$top = array_pop($stack);
$copy = $top;
$top[] = 5;
$stack[] = $top;
echo json_encode($stack), " ", json_encode($copy), "\n";

// list() of a literal swapping two arrays
$x = [1]; $z = [2];
[$x, $z] = [$z, $x];
$x[] = 3;
echo json_encode($x), " ", json_encode($z), "\n";

// a temporary array of arrays dropped, the arrays in it still held
$keep = [7];
[$keep, [8]];
$alias = $keep;
$alias[] = 9;
echo json_encode($keep), " ", json_encode($alias), "\n";

// foreach over a temporary array of arrays, writing the values
foreach ([[1], [2]] as $v) {
    $v[] = 0;
    echo json_encode($v);
}
echo "\n";

// an array held by a property and an element, the property overwritten
class Box { public $items; }
$box = new Box;
$box->items = [[1], [2]];
$held = $box->items;
$box->items = null;
$held[0][] = 3;
echo json_encode($held), "\n";

// array_map building an array of arrays from a callback that keeps them
$acc = [1];
$r = array_map(function ($v) use (&$acc) { $acc[] = $v; return $acc; }, [2, 3]);
echo json_encode($r), " ", json_encode($acc), "\n";
