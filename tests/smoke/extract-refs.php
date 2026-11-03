<?php
// extract() with EXTR_REFS (php_extract_ref_*): the variables are bound to the array's entries, made references in
// the variable's array (separated first); a temporary array's entries are its copy's.
ob_start(function ($text) {
    // "/" after the directory: Truffle PHP runs the smoke tests on the host
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
});
function inFunction() {
    $data = ['p' => 1, 'q' => 2, 7 => 'int key'];
    $copy = $data;
    $n = extract($data, EXTR_REFS);
    $p = 10;
    $data['q'] = 20;
    var_dump($n, $p, $q, $data, $copy);
    $more = ['p' => 'new', 'r' => 3];
    var_dump(extract($more, EXTR_SKIP | EXTR_REFS), $p, $r);
    $r = 30;
    var_dump($more);
    var_dump(extract($more, EXTR_PREFIX_ALL | EXTR_REFS, 'x'), $x_p, $x_r);
    $x_p = 'via prefix';
    var_dump($more['p']);
    var_dump(extract(['t' => 'temporary'], EXTR_REFS), $t);
    $flags = EXTR_REFS;
    $w = ['wv' => 1];
    extract($w, $flags);
    $wv = 2;
    var_dump($w);
}
inFunction();

$top = ['a' => 1, 'b' => 2];
extract($top, EXTR_REFS);
$a = 100;
var_dump($top);
$nested = ['in' => ['k' => 'v']];
extract($nested['in'], EXTR_REFS | EXTR_OVERWRITE);
$k = 'changed';
var_dump($nested);
$plain = ['m' => 1];
extract($plain);
$m = 2;
var_dump($plain);
