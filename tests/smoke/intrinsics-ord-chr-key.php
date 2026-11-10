<?php
// ord(), chr() and array_key_exists() are compiled in place for their common operands; every other operand takes
// the built-in's own path. Each in a loop, so the compiled form runs too.

function ords($values) {
    $r = [];
    foreach ($values as $v) {
        try {
            $r[] = var_export(ord($v), true);
        } catch (Throwable $e) {
            $r[] = get_class($e) . ': ' . $e->getMessage();
        }
    }
    return implode(' ', $r);
}

function chrs($values) {
    $r = [];
    foreach ($values as $v) {
        try {
            $r[] = bin2hex(chr($v));
        } catch (Throwable $e) {
            $r[] = get_class($e) . ': ' . $e->getMessage();
        }
    }
    return implode(' ', $r);
}

function keys($array, $values) {
    $r = [];
    foreach ($values as $v) {
        try {
            $r[] = var_export(array_key_exists($v, $array), true);
        } catch (Throwable $e) {
            $r[] = get_class($e) . ': ' . $e->getMessage();
        }
    }
    return implode(' ', $r);
}

class Named { public $a = 1; public function __toString() { return 'zed'; } }

$strings = ['', 'a', "\xff\x00", 'zażółć', "\0", '0', 65, 6.5, true, false, null, new Named];
$codes = [0, 65, 255, 256, -1, -257, 1 << 40, PHP_INT_MIN, '66', 67.0, 67.9, true, null];
$array = ['a' => 1, 'n' => null, 5 => 'five', '' => 'empty', '-3' => 'minus', '07' => 'seven', 'zed' => 0,
    '9223372036854775808' => 'big', 1 => 'one', 0 => 'zero'];
$probes = ['a', 'n', 'b', 5, '5', '05', 5.0, 5.9, '', null, -3, '-3', '07', 7, true, false, 'zed',
    '9223372036854775808', '1', ' 1', '1 ', '-0', 0, '0'];
$list = [10, 20, 30];
for ($i = 0; $i < 3; $i++) {
    echo ords($strings), "\n";
    echo chrs($codes), "\n";
    echo keys($array, $probes), "\n";
    echo keys($list, [0, 2, 3, -1, '1', '01', 'x', 1.0, null]), "\n";
    echo keys([], ['a', 0, null]), "\n";
}

// a hot loop of each: what utf8_uri_encode and _wp_array_get do
function encode($s) {
    $out = '';
    $sum = 0;
    for ($i = 0; $i < strlen($s); $i++) {
        $value = ord($s[$i]);
        $sum += $value;
        $out .= $value < 128 ? chr($value) : '%' . dechex($value);
    }
    return $out . ' ' . $sum;
}

function path_get($input, $path, $default = null) {
    foreach ($path as $element) {
        if (!is_array($input)) {
            return $default;
        }
        if (isset($input[$element])) {
            $input = $input[$element];
            continue;
        }
        if (array_key_exists($element, $input)) {
            $input = $input[$element];
            continue;
        }
        return $default;
    }
    return $input;
}

$settings = ['color' => ['palette' => ['default' => [1, 2, 3], 'none' => null], 'on' => true], 'x' => null, 7 => ['y' => 2]];
$sum = '';
for ($i = 0; $i < 300; $i++) {
    $sum = encode('hello-zażółć-' . $i);
    $sum .= ' ' . count(path_get($settings, ['color', 'palette', 'default'], []));
    $sum .= ' ' . var_export(path_get($settings, ['color', 'palette', 'none'], 'd'), true);
    $sum .= ' ' . var_export(path_get($settings, ['x'], 'd'), true);
    $sum .= ' ' . var_export(path_get($settings, ['color', 'missing'], 'd'), true);
    $sum .= ' ' . var_export(path_get($settings, [7, 'y'], 'd'), true);
    $sum .= ' ' . var_export(path_get($settings, ['7', 'y'], 'd'), true);
    $sum .= ' ' . var_export(path_get($settings, ['color', 'on', 'deeper'], 'd'), true);
}
echo $sum, "\n";

// an object where an array belongs, and strict types' view of the arguments, are the built-in's to refuse
try {
    var_dump(array_key_exists('a', new Named));
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    var_dump(array_key_exists([], ['a' => 1]));
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
var_dump(key_exists('a', ['a' => null]));
