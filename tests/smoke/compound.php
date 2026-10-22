<?php
// Compound assignment across int/float transitions: overflow to float, float operands, /= by zero, strings.
function run($start, $operands, $op) {
    $x = $start;
    foreach ($operands as $o) {
        switch ($op) {
            case '+': $x += $o; break;
            case '-': $x -= $o; break;
            case '*': $x *= $o; break;
            case '/': $x /= $o; break;
        }
    }
    return var_export($x, true);
}
foreach (['+', '-', '*', '/'] as $op) {
    echo $op, ' ', run(10, [2, 3, 0.5, 4, 1.25, '2', 7], $op), ' ', run(2.5, [2, 1.5, 3], $op), "\n";
}
echo run(PHP_INT_MAX - 1, [1, 1, 1], '+'), ' ', run(PHP_INT_MIN + 1, [1, 1], '-'), ' ', run(3037000500, [3037000500], '*'), "\n";
echo run(9, [3, 2], '/'), ' ', run(1.0, [3, 3], '/'), "\n";
$x = 1.5;
try {
    $x /= 0;
} catch (DivisionByZeroError $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
$s = 0.0;
for ($i = 0; $i < 1000; $i++) {
    $s += 1 / ($i + 1);
    $s -= 0.001;
}
printf("%.10f\n", $s);
