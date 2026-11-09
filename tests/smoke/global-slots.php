<?php
// `global $x` sites that keep a global's reference by its name's id (several contexts: tests/smoke/run.sh's
// --contexts=3): a global created by the site, unset and created again, rebound by reference at the top level
// and through $GLOBALS, read before it exists, in a loop long enough to compile the sites.

function bump(): int { global $counter; return ++$counter; }
function peek(): string { global $missing; return var_export($missing, true); }
function has(): string { return var_export(array_key_exists('counter', $GLOBALS), true); }

$sum = 0;
for ($i = 0; $i < 3000; $i++) {
    $sum += bump();
    if ($i === 1000) {
        unset($counter);
        echo 'after unset: ', has(), ' ', bump(), "\n";
    }
    if ($i === 2000) {
        $other = 100;
        $counter = &$other;
        echo 'rebound: ', bump(), ' ', $other, "\n";
    }
    if ($i === 2500) {
        $fresh = 7;
        $GLOBALS['counter'] = &$fresh;
        echo 'via $GLOBALS: ', bump(), ' ', $fresh, "\n";
    }
}
echo $sum, ' ', $counter, ' ', peek(), "\n";
$missing = 'now';
echo peek(), "\n";
unset($GLOBALS['missing']);
echo peek(), ' ', var_export(isset($missing), true), "\n";
