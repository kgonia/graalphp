<?php
// foreach by reference walks the array in the variable's reference at each step, as php-src's FE_FETCH_RW does:
// a copy made during the loop continues where the copy was made, another array starts at its internal pointer,
// a scalar ends the loop with a warning. Found by models/cow (wiki/tla-cow.md, F2).

set_error_handler(function ($type, $message) {
    echo "warning: $message\n";
    return true;
});

function show($label, $value) {
    echo $label, ": ", json_encode($value), "\n";
}

function main() {
    // separated during the loop: the loop goes on in the variable's new copy, at the same position
    $a = [1, 2, 3];
    foreach ($a as $k => &$v) { echo $k; $b = $a; $a[] = 9; $v = 7; if ($k > 4) break; }
    echo "\n"; unset($v); show('a', $a); show('b', $b);

    // replaced by another array: that array from its internal pointer
    $a = [1, 2, 3]; $n = 0;
    foreach ($a as $k => &$v) { echo $k; if ($n++ == 1) { $a = [5, 6, 7, 8]; } if ($n > 6) break; }
    echo "\n"; unset($v); show('a', $a);
    $a = [1, 2, 3]; $n = [5, 6, 7]; next($n);
    foreach ($a as $k => &$v) { echo $k; if ($k === 0 && $n) { $a = $n; $n = null; } }
    echo "\n"; unset($v); show('a', $a);

    // replaced by a scalar: the loop ends
    $a = [1, 2, 3];
    foreach ($a as $k => &$v) { echo $k; $a = 5; }
    echo "\n"; unset($v); show('a', $a);

    // rebound or unset: the loop keeps the reference it holds
    $a = [1, 2, 3]; $o = [8, 9];
    foreach ($a as $k => &$v) { echo $k; $v = 0; $a = &$o; }
    echo "\n"; unset($v); show('a', $a); show('o', $o);
    $a = [1, 2, 3]; $c = &$a;
    foreach ($a as $k => &$v) { echo $k; $v = 4; unset($a); }
    echo "\n"; unset($v); show('c', $c);

    // a copy into another variable, written there, then assigned back: the iterator's copy goes with it
    $a = [1, 2, 3];
    foreach ($a as $k => &$v) { echo $k; if ($k == 0) { $b = $a; $b[] = 4; $a = $b; } }
    echo "\n"; unset($v); show('a', $a);

    // elements deleted and the hash compacted while the loop runs: positions move with their elements
    $a = ['a' => 1, 'b' => 2, 'c' => 3, 'd' => 4, 'e' => 5, 'f' => 6, 'g' => 7, 'h' => 8];
    foreach ($a as $k => &$v) {
        echo $k;
        if ($k === 'c') { unset($a['a'], $a['b']); for ($i = 0; $i < 6; $i++) { $a["n$i"] = $i; } }
    }
    echo "\n"; unset($v);

    // the loop holds the iterated element's reference: a copy of its container keeps sharing it meanwhile
    $arr = ['x' => [1, 2]];
    foreach ($arr['x'] as &$v) { $copy = $arr; $copy['y'] = 1; }
    $arr['x'][0] = 'changed';
    unset($v);
    show('copy', $copy);

    // nothing to iterate
    $x = null; foreach ($x as &$v) {} show('x', $x);
    $x = false; foreach ($x as $v) {}
    $x = 3; foreach ($x as &$v) {} show('x', $x);
    $a = []; foreach ($a['k'] as &$v) {} show('a', $a);
}
main();
