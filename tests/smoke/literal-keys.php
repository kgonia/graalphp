<?php
// Array literals with literal keys (the bytecode's LiteralPutKey and LiteralAdd: keys normalized by the
// translator, a record built as a hash directly) and variables as arguments (SendVar's cached callee), run often
// enough to be compiled, then once more with what the compiled code hasn't met.

function record($v, $w) {
    return ['name' => $v, 'icon' => $w, '10' => $v, 'x' => [$w => $v], 'n' => null];
}

function mixed_keys($v) {
    // duplicate keys (the last wins, at the first's place), int-like strings, an int key then an append
    return ['a' => 1, '7' => $v, 'a' => $v, 7 => 'seven', -3 => $v, $v, '08' => $v, '-0' => $v, 'z' => $v];
}

function ints($v) {
    return [3 => $v, 1 => $v, $v, 0 => 'zero'];
}

function order(&$log) {
    // values evaluated in order, each inserted as it is evaluated
    $a = ['first' => ($log[] = 1), 'second' => ($log[] = 2), 'third' => count($log)];
    return $a;
}

function holder() {
    $inner = [1, 2];
    $outer = ['k' => $inner, 'j' => $inner];
    $outer['k'][] = 3;
    return [$inner, $outer];
}

function by_value($a) {
    $a[] = 'changed';
    return count($a);
}

function by_ref(&$a) {
    $a[] = 'changed';
    return count($a);
}

function send($x) {
    $n = by_value($x);
    return $n . ':' . count($x);
}

function send_ref($x) {
    $n = by_ref($x);
    return $n . ':' . count($x);
}

$out = [];
for ($i = 0; $i < 3000; $i++) {
    $out = [record($i, 'w'), mixed_keys($i), ints($i), send([1]), send_ref([1])];
}
var_dump($out);
var_dump(record(1.5, true), mixed_keys('s'), ints(null));
$log = [];
var_dump(order($log), $log);
var_dump(holder());
var_dump(array_keys(record('a', 'b')), json_encode(record('a', 'b')));
$r = record(1, 2);
$r['new'] = 1;
unset($r['name']);
$r[] = 'appended';
var_dump($r);
