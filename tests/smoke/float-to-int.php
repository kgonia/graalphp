<?php
// Floats and numeric strings converted to int: in-range truncation, out-of-range floats wrapping modulo 2^64
// (zend_dval_to_lval), float-looking strings saturating (zend_dval_to_lval_cap), INF and NAN as 0.
$floats = [0.0, -0.0, 1.9, -1.9, 1e18, -1e18, 9.2233720368547e18, PHP_INT_MAX + 1.0, (float) PHP_INT_MIN,
    -9.3e18, 1.8446744073709552e19, 2e19, -2e19, 1.5e300, -1.5e300, 1e100, 3.6893488147419103e19, 5e-324,
    INF, -INF, NAN];
foreach ($floats as $f) {
    echo var_export($f, true), ' => ', @intval($f), ' ', @(int) $f, ' ', @sprintf('%d', $f), "\n";
}
$strings = ['1e30', '-1e30', '9223372036854775807', '9223372036854775808', '-9223372036854775809', '1.5e300',
    '1e999', '-1e999', ' 12', '12abc', '0x1A', '1.9', '-1.9', '9.3e18', 'abc', ''];
foreach ($strings as $s) {
    echo var_export($s, true), ' => ', @intval($s), ' ', @(int) $s, ' ', @sprintf('%d', $s), "\n";
}
$a = [];
foreach ([1.7, -1.7, 2e19, 1e100] as $k) {
    @$a[$k] = true;
}
var_dump(array_keys($a));
