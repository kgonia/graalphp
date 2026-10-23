<?php
// Floats as serialize, var_export, json_encode and var_dump print them (serialize_precision=-1): zeros, signs,
// exponent forms, integral values, INF and NAN, and round trips through unserialize.
$values = [0.0, -0.0, 1.0, -1.0, 0.1, -0.1, 1.5, 100.0, 1e15, 1e16, 1e17, 1e20, 1e21, 1e100, -1e100, 1.5e300,
    1e-4, 1e-5, 1.25e-5, -2.5e-10, 0.001, 123456789012345678.0, 0.30000000000000004, 1/3, PHP_INT_MAX + 1.0,
    5e-324, 1e-323, 1.5e-323, 2e-323, 9e-323, 2.2250738585072014e-308, 1.7976931348623157e308, 2.0 ** 53, 7.0E-10, 4.35, 1e22, 1e23];
foreach ($values as $v) {
    echo serialize($v), ' ', var_export($v, true), ' ', json_encode($v), ' ',
        json_encode($v, JSON_PRESERVE_ZERO_FRACTION), ' ', $v, "\n";
    var_dump($v);
    $back = unserialize(serialize($v));
    var_dump($back === $v, $back);
}
foreach ([INF, -INF, NAN] as $v) {
    echo serialize($v), ' ', var_export($v, true), ' ', var_export(@json_encode($v), true), "\n";
    var_dump($v, unserialize(serialize($v)));
}
echo serialize([0.0, -0.0, 'k' => -0.0, 2 => [0.5, -0.0]]), "\n";
var_export([0.0, -0.0, 1.0, [-1.0]]);
echo "\n", json_encode(['a' => 0.0, 'b' => -0.0, 'c' => [1.0, -1.0]]), "\n";
echo json_encode([0.0, -0.0, 10.0, 1e25], JSON_PRESERVE_ZERO_FRACTION), "\n";
var_dump(unserialize('d:0;'), unserialize('d:-0;'), unserialize('d:0.0;'), unserialize('d:-0.0;'), unserialize('d:1.0E+25;'),
    unserialize('d:1e3;'), unserialize('d:INF;'), unserialize('d:-INF;'), unserialize('d:NAN;'));
$o = new stdClass;
$o->z = -0.0;
$o->f = 2.5;
echo serialize($o), "\n";
