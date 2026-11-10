<?php
// String-key element access under every form a site takes (ElementNodes.SlotCacheNode): the remembered slot, the
// probe, the call. Each block runs hot enough to be compiled; the output must equal php's, byte for byte.
// Usage: php keys_stress.php [rounds]
error_reporting(E_ALL);
$rounds = (int) ($argv[1] ?? 300);
set_error_handler(static function ($no, $message) {
    static $seen = [];
    $seen[$message] = ($seen[$message] ?? 0) + 1;
    if ($seen[$message] === 1) {
        echo "warning: $message\n";
    }
    return true;
});

function ks_get($array, $key) { return $array[$key]; }
function ks_isset($array, $key) { return isset($array[$key]); }
function ks_exists($array, $key) { return array_key_exists($key, $array); }
function ks_coalesce($array, $key) { return $array[$key] ?? 'none'; }
function ks_put($array, $key, $value) { $array[$key] = $value; return $array; }
function ks_put_ref(&$array, $key, $value) { $array[$key] = $value; }
function ks_show($label, $value) { echo $label, ': ', json_encode($value), "\n"; }

$small = ['id' => 1, 'name' => 'n', 'none' => null, 'list' => [1, 2], '7' => 'seven', 'zero' => 0];
$big = [];
for ($i = 0; $i < 40; $i++) {
    $big["k$i"] = $i;
}
$big['none'] = null;
$big[12] = 'twelve';
$holey = $big;
unset($holey['k3'], $holey['k17'], $holey['none']);
$holey['k3'] = 'again';
$decoded = json_decode('{"id":5,"name":"decoded","none":null,"7":"seven","nested":{"a":{"b":1}}}', true);
$packed = ['a', 'b', 'c'];
$fresh = static fn (string $s) => substr("x$s", 1); // an equal key of another instance
$keys = ['id', 'name', 'none', 'missing', '7', '07', '-0', '', 'k3', 'k17', 'k39', '12', 'zero', 'list', '1', ' 1',
    '9223372036854775807', '9223372036854775808', '-9223372036854775808', '0', '00', '1.5', 'nested'];
$arrays = ['small' => $small, 'big' => $big, 'holey' => $holey, 'decoded' => $decoded, 'packed' => $packed,
    'empty' => []];

// reads, isset, array_key_exists, ??: every key against every array, at shared sites (the functions) and hot
$sum = 0;
$report = [];
for ($r = 0; $r < $rounds; $r++) {
    foreach ($arrays as $name => $array) {
        foreach ($keys as $key) {
            $k = ($r & 1) ? $fresh($key) : $key;
            $a = ks_isset($array, $k);
            $b = ks_exists($array, $k);
            $c = ks_coalesce($array, $k);
            $d = $b ? ks_get($array, $k) : 'absent';
            if ($r < 2) {
                $report[] = "$name [" . $key . '] ' . json_encode([$a, $b, $c, $d]);
            }
            $sum += ($a ? 1 : 0) + ($b ? 2 : 0) + (is_int($c) ? $c : strlen(json_encode($c)));
        }
    }
}
echo implode("\n", $report), "\n";
ks_show('reads', $sum);

// a read of a missing key warns once per site and reads null
$nulls = 0;
for ($r = 0; $r < $rounds * 20; $r++) {
    $nulls += ks_get($small, ($r % 3) ? 'id' : 'absent') === null ? 1 : 0;
}
ks_show('missing reads', $nulls);

// one literal key, arrays of different layouts (the key at different slots, absent, in a packed array)
$layouts = [['a' => 1, 'b' => 2, 'c' => 3], ['c' => 4, 'a' => 5], ['b' => 6], ['x', 'y'], ['c' => null, 'b' => 7],
    $big + ['c' => 8]];
$sum = 0;
for ($r = 0; $r < $rounds * 40; $r++) {
    $layout = $layouts[$r % 6];
    $sum += isset($layout['c']) ? $layout['c'] : 100;
    $sum += array_key_exists('c', $layout) ? 1000 : 0;
    $sum += $layout['b'] ?? 10000;
}
ks_show('layouts', $sum);

// writes: existing keys, new keys, int-like keys, a reference slot, copy-on-write, a hash that grows
$row = ['id' => 0, 'name' => 'x', 'type' => 0, 'status' => 1];
$target = 5;
$row['ref'] = &$target;
$copy = $row;
$writeKeys = ['id', 'name', 'type', 'status', 'ref', 'new', '3', '03'];
for ($r = 0; $r < $rounds * 40; $r++) {
    $k = $writeKeys[$r & 7];
    $row[($r & 16) ? $fresh($k) : $k] = $r;
}
ks_show('row', $row);
ks_show('target', $target);
unset($copy['ref']);
ks_show('copy', $copy);

$grown = [];
for ($r = 0; $r < $rounds * 10; $r++) {
    $k = 'g' . ($r % 37);
    $grown[$k] = ($grown[$k] ?? 0) + 1;
    if ($r % 11 === 0) {
        unset($grown['g' . ($r % 5)]);
    }
}
ksort($grown);
ks_show('grown', $grown);

// a write through a shared site: by value (the caller's array stays), by reference, into nested arrays
$base = ['a' => 1, 'b' => ['c' => 2]];
$sum = 0;
for ($r = 0; $r < $rounds * 20; $r++) {
    $k = ($r % 3) ? 'a' : (($r % 2) ? 'z' : 'b');
    $changed = ks_put($base, $k, $r);
    $sum += count($changed) + (is_int($changed['a']) ? $changed['a'] : 0);
    ks_put_ref($changed, 'a', -1);
    $sum += $changed['a'];
    $nested = $base;
    $nested['b'][$k] = $r;
    if ($k !== 'a') {
        $nested[$k]['deep'] ??= $r;
    }
    $sum += count($nested['b']) + count($nested, COUNT_RECURSIVE);
}
ks_show('base', $base);
ks_show('writes', $sum);

// objects and closures as elements keep their counts through a replaced slot
class KsCounted {
    public static int $alive = 0;
    public function __construct() { self::$alive++; }
    public function __destruct() { self::$alive--; }
}
$holder = ['o' => null, 'p' => null];
$peak = 0;
for ($r = 0; $r < $rounds * 10; $r++) {
    $k = ($r & 1) ? 'o' : 'p';
    $holder[$k] = new KsCounted();
    $peak = max($peak, KsCounted::$alive);
}
ks_show('alive', [KsCounted::$alive, $peak]);
$holder = null;
ks_show('alive after', KsCounted::$alive);
