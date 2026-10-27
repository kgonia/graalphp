<?php
// Arithmetic, bitwise and increment operands as zend_operators.c takes them: TypeErrors for what isn't a number
// (zend_binop_error), the non-numeric and precision warnings and deprecations, strings combined byte by byte.
set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (line $line)
";
    return true;
});
$cases = [['abc', 1, '+'], ['', 1, '+'], [' 5 ', 1, '+'], ['5 apples', 1, '+'], [[1], 1, '+'], [1, [1], '-'],
    [new stdClass, 1, '+'], ['abc', 2, '*'], ['x', 'y', '-'], ['1e3', 1, '+'], [null, 'abc', '+'], [true, 'a', '/'],
    ['abc', 2, '%'], ['abc', 2, '**'], ['abc', 1, '<<'], ['abc', 1, '|'], ['abc', 'def', '|'], ['abc', 1.5, '+'],
    [[1], [2], '+'], [[1], [2], '*'], [fopen('php://memory', 'r'), 1, '+'], ['0x1A', 1, '+'], [' ', 1, '+']];
foreach ($cases as [$a, $b, $op]) {
    try {
        switch ($op) {
            case '+': $r = $a + $b; break;
            case '-': $r = $a - $b; break;
            case '*': $r = $a * $b; break;
            case '/': $r = $a / $b; break;
            case '%': $r = $a % $b; break;
            case '**': $r = $a ** $b; break;
            case '<<': $r = $a << $b; break;
            default: $r = $a | $b;
        }
        echo $op, ' => ', var_export($r, true), "\n";
    } catch (Throwable $e) {
        echo $op, ' ', get_class($e), ': ', $e->getMessage(), "\n";
    }
}
foreach (['abc', '', [1]] as $v) {
    try { $x = -$v; var_dump($x); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
    try { $y = $v; $y++; var_dump($y); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
    try { $z = $v; $z += 1; var_dump($z); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
}
foreach ([1.7, 'ab', true, null, [1], new stdClass] as $v) {
    try { var_dump(~$v); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
}
var_dump('abc' & 'de', 'abc' ^ 'de', 'ab' | 'c', '12' | 1, 1.5 | 2);
$s = 'ab'; $s |= 'c'; var_dump($s);
$t = 'x'; try { $t >>= 1; } catch (Throwable $e) { echo $e->getMessage(), "\n"; }
var_dump(pow('2', 3)); try { pow('a', 2); } catch (Throwable $e) { echo $e->getMessage(), "\n"; }
foreach (['', 'a', 'z', 'Az', 'zz', 'a9', 'Zz', 'z9Z', '9', ' ', '-', 'a-', ' 5', '5 ', '5.5', '1e2', '9223372036854775807', true, false, null, 1.5, [1], new stdClass] as $v) {
    $a = $v; $b = $v;
    try { $a++; } catch (Throwable $e) { $a = get_class($e) . ': ' . $e->getMessage(); }
    try { $b--; } catch (Throwable $e) { $b = get_class($e) . ': ' . $e->getMessage(); }
    echo json_encode($v), ' ++ ', var_export($a, true), ' -- ', var_export($b, true), "\n";
}
var_dump(str_increment('Az'), str_decrement('Ba'), str_increment('zz9'), str_increment('Zz'), str_decrement('aa'),
    str_decrement('10'), str_decrement('A0'));
foreach (['', 'a-b', '0a', 'a', 'A', '0'] as $s) {
    try {
        var_dump(str_increment($s), str_decrement($s));
    } catch (ValueError $e) {
        echo $e->getMessage(), "\n";
    }
}
var_dump('9.9' % 4, '1e30' | 0, 7.0 | 0);
