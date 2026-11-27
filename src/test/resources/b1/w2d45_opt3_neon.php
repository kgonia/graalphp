<?php
// Pass 3 float CVs, Neon review regressions: an op= that is not float arithmetic makes its target an ordinary
// CV (P0-1); a CV read on the left of an operator whose right operand assigns it is read after that operand
// (P0-2); a by-value closure capture reads the CV, so a possibly undefined one is no float local (P0-3).
function concat_update() {
    $x = 1.0;
    $y = ($x .= 'abc');
    return $y . ' ' . $x;
}
function mod_update() {
    $x = 1.5;
    return ($x %= 2);
}
function bit_updates() {
    $a = 6.0; $a &= 3;
    $b = 6.0; $b |= 1;
    $c = 6.0; $c ^= 3;
    $d = 6.0; $d <<= 2;
    $e = 6.0; $e >>= 1;
    var_dump($a, $b, $c, $d, $e);
    $f = 1.0;
    $g = ($f &= 1);
    var_dump($f, $g);
}
function deferred_add() { $x = 1.0; $y = $x + ($x = 10.0); return $y; }
function deferred_sub() { $x = 1.0; $y = $x - ($x = 10.0); return $y; }
function deferred_mul() { $x = 2.0; $y = $x * ($x = 10.0); return $y; }
function deferred_div() { $x = 2.0; $y = $x / ($x = 10.0); return $y; }
function deferred_update() { $x = 1.0; $x -= ($x = 10.0); return $x; }
function deferred_update_add() { $x = 1.0; $x += ($x = 2.5) + 1.0; return $x; }
function deferred_compare() {
    $x = 1.0;
    $eq = $x == ($x = 10.0) ? 'eq' : 'ne';
    $x = 1.0;
    $lt = $x < ($x = 10.0) ? 'lt' : 'ge';
    $x = 1.0;
    $id = $x === ($x = 10.0) ? 'same' : 'diff';
    return "$eq $lt $id";
}
function deferred_ternary($c) {
    $x = 1.0;
    $y = $x + ($c ? ($x = 10.0) : 2.0);
    return $y;
}
function capture($c) {
    if ($c) { $d = 1.0; }
    $fn = function() use ($d) { return $d === null ? 'NULL' : $d; };
    return $fn();
}
function capture_loop($n) {
    $out = [];
    for ($i = 0; $i < $n; $i++) {
        $fn = function() use ($d) { return $d === null ? 'NULL' : $d; };
        $out[] = $fn();
        $d = $i + 0.5;
    }
    return implode(',', $out);
}
echo concat_update(), "\n";
echo mod_update(), "\n";
bit_updates();
var_dump(deferred_add(), deferred_sub(), deferred_mul(), deferred_div(), deferred_update(), deferred_update_add());
echo deferred_compare(), "\n";
var_dump(deferred_ternary(true), deferred_ternary(false));
echo capture(false), "\n";
echo capture(true), "\n";
echo capture_loop(3), "\n";
