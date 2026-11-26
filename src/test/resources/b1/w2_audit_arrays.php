<?php
function change($v) { global $source; $source[1] = 77; return $v; }
function combine($x, $y, $z) { global $other; $other[1] = 88; return $x . '/' . $y . '/' . $z; }
$source = [1, 2];
echo implode(',', array_map('change', $source)), " vs ", implode(',', $source), "\n";
$other = [3, 4];
echo implode(',', array_map('combine', ['a' => 1], $other, [5, 6, 7])), " vs ", implode(',', $other), "\n";
foreach (array_map(null, ['a' => 1], [3, 4]) as $k => $tuple) echo $k, "=", implode('/', $tuple), "\n";
echo implode(',', array_keys(array_map(null, ['k' => 1]))), "\n";
foreach (['no_such_fn', 1, [], [new stdClass(), 'missing'], ['MissingClass','f']] as $callback) {
    try { array_map($callback, []); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
}
try { array_map(null, [], 1); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { implode([], ','); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
$fn = 'implode';
try { $fn([], ','); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { implode(',', null); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
echo implode(null, ['x', 'y']), "\n";
