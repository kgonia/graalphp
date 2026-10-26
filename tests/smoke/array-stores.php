<?php
// Array storage strategies: literal records, small hashes and their growth, keys normalized on the way in,
// order across unset and re-add, copies, and foreach over each layout.

function record($v) {
    return ['id' => $v, 'title' => "t$v", '5' => 'five', 7 => 'seven', 'nested' => [$v, $v + 1]];
}
$r = record(1);
var_dump($r);
$r[] = 'appended';                 // after the largest int key, 7
var_dump(array_keys($r));
var_dump($r['5'], $r[5], isset($r['title']), isset($r['missing']), $r['nested'][1]);

// string keys into an empty array, past the small store's 8 slots, then reads by computed keys
$a = [];
for ($i = 0; $i < 20; $i++) {
    $a['k' . $i] = $i;
}
$sum = 0;
for ($i = 0; $i < 20; $i++) {
    $sum += $a['k' . ($i * 7 % 20)];
}
var_dump(count($a), $sum, $a['k19'], $a['k3'] ?? 'none', $a['k20'] ?? 'none');

// unset and re-add moves a key to the end; numeric strings are int keys
$b = ['a' => 1, 'b' => 2, 'c' => 3];
unset($b['a']);
$b['a'] = 4;
$b['10'] = 'ten';
$b[] = 'eleven';
var_dump($b);

// a write separates a shared record; the original keeps its values
$c = record(2);
$d = $c;
$d['title'] = 'changed';
$d['extra'] = true;
var_dump($c['title'], $d['title'], count($c), count($d));

// foreach by value over each layout: changes to the array in the body don't change the iteration
foreach ([[1, 2, 3], ['x', 'y'], ['p' => 1, 'q' => 2]] as $array) {
    $seen = [];
    foreach ($array as $k => $v) {
        $array[] = 'more';
        unset($array[$k]);
        $seen[] = "$k=$v";
    }
    echo implode(',', $seen), ' / ', count($array), "\n";
}

// foreach by reference sees elements added in the body
$e = ['a' => 1, 'b' => 2];
foreach ($e as $k => &$v) {
    $v *= 10;
    if ($k === 'a') {
        $e['c'] = 3;
    }
}
unset($v);
var_dump($e);

// the same key written twice by one site keeps one slot
$f = [];
foreach (['x', 'y', 'x', 'z', 'y'] as $i => $key) {
    $f[$key] = $i;
}
var_dump($f);

// references in a small hash receive writes
$g = ['r' => 0];
$ref = &$g['r'];
$g['r'] = 5;
var_dump($ref);
