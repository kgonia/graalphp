<?php
// Run by tests/shared/run.sh in many contexts at once on one engine: every context writes to arrays that start
// as language-level data (a class's default property, a class constant, a constant array literal) and checks
// that it only ever sees its own writes.
class Box
{
    const SEED = [1, 2, [3]];
    public $items = [10, 20];
    public static $log = ['start'];
}

$bad = 0;
for ($i = 0; $i < 20000; $i++) {
    $box = new Box();
    $box->items[] = $i;
    if (count($box->items) !== 3 || $box->items[0] !== 10) {
        $bad++;
    }
    $seed = Box::SEED;
    $seed[2][] = $i;
    $seed[] = $i;
    if (count(Box::SEED) !== 3 || count(Box::SEED[2]) !== 1 || count($seed[2]) !== 2) {
        $bad++;
    }
    $literal = ['a' => 1];
    $literal['b'] = $i;
    if (count(['a' => 1]) !== 1) {
        $bad++;
    }
    Box::$log[] = $i;
}
if (count(Box::$log) !== 20001) {
    $bad++;
}
echo $bad === 0 ? "isolated\n" : "corrupted $bad\n";
