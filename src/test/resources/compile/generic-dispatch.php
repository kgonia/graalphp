<?php
// Generic dispatch to functions not called before, as WP_Hook::apply_filters calls a hook's callbacks: the first
// call of a function made its call target and deoptimized the compiled caller, once per new function (a deopt
// cycle, DeoptCyclePatterns.md's dispatch node). Run with a low DeoptCycleDetectionThreshold, it fails as one.
function cb0($v) { return $v + 0; }
function cb1($v) { return $v + 1; }
function cb2($v) { return $v + 2; }
function cb3($v) { return $v + 3; }
function cb4($v) { return $v + 4; }
function cb5($v) { return $v + 5; }
function cb6($v) { return $v + 6; }
function cb7($v) { return $v + 7; }
function cb8($v) { return $v + 8; }
function cb9($v) { return $v + 9; }
function cb10($v) { return $v + 10; }
function cb11($v) { return $v + 11; }
function cb12($v) { return $v + 12; }
function cb13($v) { return $v + 13; }
function cb14($v) { return $v + 14; }
function cb15($v) { return $v + 15; }
function cb16($v) { return $v + 16; }
function cb17($v) { return $v + 17; }
function cb18($v) { return $v + 18; }
function cb19($v) { return $v + 19; }
function cb20($v) { return $v + 20; }
function cb21($v) { return $v + 21; }
function cb22($v) { return $v + 22; }
function cb23($v) { return $v + 23; }
function cb24($v) { return $v + 24; }
function cb25($v) { return $v + 25; }
function cb26($v) { return $v + 26; }
function cb27($v) { return $v + 27; }
function cb28($v) { return $v + 28; }
function cb29($v) { return $v + 29; }
function cb30($v) { return $v + 30; }
function cb31($v) { return $v + 31; }
function cb32($v) { return $v + 32; }
function cb33($v) { return $v + 33; }
function cb34($v) { return $v + 34; }
function cb35($v) { return $v + 35; }
function cb36($v) { return $v + 36; }
function cb37($v) { return $v + 37; }
function cb38($v) { return $v + 38; }
function cb39($v) { return $v + 39; }
function cb40($v) { return $v + 40; }
function cb41($v) { return $v + 41; }
function cb42($v) { return $v + 42; }
function cb43($v) { return $v + 43; }
function cb44($v) { return $v + 44; }
function cb45($v) { return $v + 45; }
function cb46($v) { return $v + 46; }
function cb47($v) { return $v + 47; }
function cb48($v) { return $v + 48; }
function cb49($v) { return $v + 49; }
function cb50($v) { return $v + 50; }
function cb51($v) { return $v + 51; }
function cb52($v) { return $v + 52; }
function cb53($v) { return $v + 53; }
function cb54($v) { return $v + 54; }
function cb55($v) { return $v + 55; }
function cb56($v) { return $v + 56; }
function cb57($v) { return $v + 57; }
function cb58($v) { return $v + 58; }
function cb59($v) { return $v + 59; }
function cb60($v) { return $v + 60; }
function cb61($v) { return $v + 61; }
function cb62($v) { return $v + 62; }
function cb63($v) { return $v + 63; }
function cb64($v) { return $v + 64; }
function cb65($v) { return $v + 65; }
function cb66($v) { return $v + 66; }
function cb67($v) { return $v + 67; }
function cb68($v) { return $v + 68; }
function cb69($v) { return $v + 69; }
function cb70($v) { return $v + 70; }
function cb71($v) { return $v + 71; }
function cb72($v) { return $v + 72; }
function cb73($v) { return $v + 73; }
function cb74($v) { return $v + 74; }
function cb75($v) { return $v + 75; }
function cb76($v) { return $v + 76; }
function cb77($v) { return $v + 77; }
function cb78($v) { return $v + 78; }
function cb79($v) { return $v + 79; }
function apply(array $callbacks, $value) {
    foreach ($callbacks as $callback) {
        $value = call_user_func_array($callback, [$value]);
    }
    return $value;
}
$sum = 0;
for ($i = 0; $i < 2000; $i++) {
    $sum += apply(['cb0', 'cb1'], $i);
}
for ($n = 2; $n < 80; $n++) {
    for ($i = 0; $i < 30; $i++) {
        $sum += apply(['cb0', 'cb' . $n], $i);
    }
}
echo $sum, "\n";
