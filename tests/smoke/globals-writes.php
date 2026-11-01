<?php
// Writes through $GLOBALS['name'] (PHP 8.1+): php-src compiles $GLOBALS[key] in any write context as the global
// variable the key names (a FETCH_GLOBAL), with the key evaluated once; $GLOBALS itself is read-only.
ob_start(function ($text) {
    // "/" after the directory: Truffle PHP runs the smoke tests on the host
    return str_replace(__FILE__, "FILE", $text);
});
function key_of($name) {
    echo "[key $name]";
    return $name;
}
function writes() {
    $GLOBALS['a'] = 1;
    $GLOBALS['a'] += 2;
    $GLOBALS['a'] *= 3;
    $GLOBALS['a'] -= 1;
    $GLOBALS['a'] **= 2;
    $GLOBALS['a'] %= 7;
    $GLOBALS['a'] <<= 3;
    $GLOBALS['a'] |= 1;
    $GLOBALS['a'] ^= 2;
    $GLOBALS['a'] &= 0xff;
    $GLOBALS['a'] >>= 1;
    $GLOBALS['a'] /= 2;
    echo "a: ", var_export($GLOBALS['a'], true), "\n";
    $GLOBALS['s'] = "x";
    $GLOBALS['s'] .= "y";
    $GLOBALS['s'] .= 1;
    echo "s: $GLOBALS[s]\n";
    $GLOBALS['n'] ??= "set";
    $GLOBALS['n'] ??= "not set again";
    echo "n: ", $GLOBALS['n'], "\n";
    $GLOBALS['i'] = 5;
    $GLOBALS['i']++;
    ++$GLOBALS['i'];
    $GLOBALS['i']--;
    $before = $GLOBALS['i']++;
    $pre = --$GLOBALS['i'];
    echo "i: ", $GLOBALS['i'], " $before $pre\n";
    $GLOBALS['undefinedInc'] = null;
    $GLOBALS['undefinedInc']++;
    var_dump($GLOBALS['undefinedInc']);
    $GLOBALS['nested']['b'] = 1;
    $GLOBALS['nested']['c']['d'] = 2;
    $GLOBALS['nested']['b'] += 10;
    $GLOBALS['nested']['list'][] = 'first';
    $GLOBALS['nested']['list'][] = 'second';
    $GLOBALS['nested']['c']['d']++;
    $GLOBALS['nested']['c']['e'] ??= 'e';
    $GLOBALS['nested']['s'] = 'str';
    $GLOBALS['nested']['s'] .= 'ing';
    unset($GLOBALS['nested']['c']['e']);
    // the key evaluated once
    $GLOBALS[key_of('k')] = 1;
    $GLOBALS[key_of('k')] += 1;
    $GLOBALS[key_of('k')] .= "!";
    $GLOBALS[key_of('k')]++;
    $GLOBALS[key_of('kk')] ??= 7;
    $GLOBALS[key_of('kk')] ??= 8;
    $GLOBALS[key_of('arr')][key_of('x')] = 1;
    $GLOBALS[key_of('arr')][key_of('x')] += 1;
    echo "\n";
    // keys that aren't strings name the variable by their string
    $GLOBALS[1] = "one";
    $GLOBALS[2.5] = "two and a half";
    $GLOBALS[true] = "true";
    $GLOBALS[null] = "empty name";
    // references
    $local = 10;
    $GLOBALS['ref'] = &$local;
    $local++;
    $r = &$GLOBALS['target'];
    $r = "through a reference";
    // list() and foreach into $GLOBALS elements
    [$GLOBALS['l1'], [$GLOBALS['l2']]] = [1, [2]];
    ['k' => $GLOBALS['l3']] = ['k' => 3];
    foreach (['x' => 'y'] as $GLOBALS['fk'] => $GLOBALS['fv']) {
    }
    // unset and isset
    $GLOBALS['gone'] = 1;
    unset($GLOBALS['gone']);
    var_dump(isset($GLOBALS['gone']), isset($GLOBALS['a']), empty($GLOBALS['a']), isset($GLOBALS));
}
writes();
var_dump($a, $s, $n, $i, $nested, $k, $kk, $arr, $GLOBALS['1'], $GLOBALS['2.5'], $GLOBALS[''], $ref, $target, $l1, $l2, $l3,
    $fk, $fv, isset($gone));
// at the top level, $GLOBALS['x'] and $x are the same variable
$GLOBALS['top'] = 1;
$top += 1;
$GLOBALS['top'] .= "!";
echo $top, "\n";
// a global made in a function, read after
function inc_counter() {
    $GLOBALS['counter'] = ($GLOBALS['counter'] ?? 0) + 1;
    return $GLOBALS['counter'];
}
inc_counter();
inc_counter();
echo inc_counter(), " ", $counter, "\n";
// $GLOBALS read as a whole is a copy
$copy = $GLOBALS;
$copy['top'] = 'changed in the copy';
echo $top, "\n";
