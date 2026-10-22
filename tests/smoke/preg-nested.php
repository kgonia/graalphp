<?php
// preg_* nested inside preg_replace_callback on large subjects: inner calls must not clobber the outer subject.
$outer = str_repeat("alpha <b>beta</b> gamma\n", 400);
$inner = str_repeat("x1y2z3 ", 1000);
$calls = 0;
$result = preg_replace_callback('/<b>(\w+)<\/b>/', function ($m) use ($inner, &$calls) {
    $calls++;
    preg_match_all('/\d/', $inner . $calls, $digits);
    $deeper = preg_replace_callback('/z(\d)/', fn($n) => preg_replace('/\d/', 'D', $n[0]), substr($inner, 0, 64));
    return '[' . strtoupper($m[1]) . count($digits[0]) . $deeper . ']';
}, $outer);
echo strlen($result), ' ', crc32($result), ' ', $calls, "\n";
echo substr($result, 0, 120), "\n";
for ($i = 0; $i < 20; $i++) {
    $big = str_repeat(chr(97 + $i), 5000) . '<p>' . $i;
    echo preg_match('/<p>(\d+)/', $big, $m), $m[1], ' ';
}
echo "\n";
