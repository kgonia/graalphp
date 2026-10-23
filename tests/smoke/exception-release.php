<?php
// A function left by an exception releases what its locals held, as a returning one does: a by-reference
// parameter bound to an array element no longer keeps the element a shared reference, so copying the array
// copies the value, and arrays the function held are no longer counted as shared.
function fails(&$x, $array) {
    $local = $array;
    $local[] = 1;
    throw new Exception('fail');
}
function returns(&$x, $array) {
    $local = $array;
    return count($local);
}
function check($name, $throw) {
    $a = [1, 2];
    $list = [10, 20];
    try {
        if ($throw) {
            fails($a[0], $list);
        } else {
            returns($a[0], $list);
        }
    } catch (Exception $e) {
        $name .= ' threw';
    }
    $b = $a;
    $b[0] = 99;
    $list[] = 30;
    echo $name, ': ', $a[0], ' ', $b[0], ' ', count($list), "\n";
}
for ($i = 0; $i < 3; $i++) {
    check('returns', false);
    check('fails', true);
}
function failsEarly(&$x, int $required, $more) {
    return $x;
}
$c = [5, 6];
try {
    failsEarly($c[0]);
} catch (ArgumentCountError $e) {
    echo get_class($e), "\n";
}
$d = $c;
$d[0] = 7;
echo $c[0], ' ', $d[0], "\n";
