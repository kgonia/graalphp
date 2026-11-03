<?php
function k($a, $b = 1, $c = 2, $d = 3, $e = 4) { return $a; }
function consts($v, $x, $ctx) {
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    $v = k(1, 2, 3, 4, 5);
    return $v;
}
function onevar($v, $x, $ctx) {
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    $v = k($x);
    return $v;
}
function threevar($v, $x, $ctx) {
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    $v = k($x, $x, $x);
    return $v;
}
function nouse($v, $x, $ctx) {
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    k($x);
    return $v;
}
function assignconst($v, $x, $ctx) {
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    $v = k(1);
    return $v;
}
for ($i = 0; $i < 5000; $i++) { consts('v','x','c'); onevar('v','x','c'); threevar('v','x','c'); nouse('v','x','c'); assignconst('v','x','c'); }
echo "ok
";
