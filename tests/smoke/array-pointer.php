<?php
// next/prev/reset/end on places: a place with side effects (`$rows[$i++]`) is evaluated once.
$rows = [[1, 2, 3], [4, 5, 6], [7, 8, 9]];
$i = 0;
var_dump(end($rows[$i++]));
var_dump($i);
var_dump(reset($rows[$i++]), next($rows[$i - 1]), $i);
var_dump(prev($rows[--$i]), $i);
$keys = ['a' => [10, 20], 'b' => [30, 40]];
function key_of(&$calls) {
    $calls++;
    return $calls % 2 ? 'a' : 'b';
}
$calls = 0;
var_dump(end($keys[key_of($calls)]), $calls);
var_dump(current($keys['a']), current($keys['b']));
$plain = [1, 2, 3];
var_dump(next($plain), next($plain), next($plain), prev($plain), reset($plain), end($plain));
$o = new stdClass();
$o->list = ['x', 'y'];
var_dump(end($o->list), key($o->list), reset($o->list));
$empty = [];
var_dump(reset($empty), end($empty));
