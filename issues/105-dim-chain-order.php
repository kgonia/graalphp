<?php
// php-src compiles the keys of `$a[k1][k2]` before it fetches anything (zend_delayed_compile_dim): a key's
// expression that changes $a, or a variable used as an earlier key, is seen by the fetch.
function reset_a() { global $a; $a = ['k' => ['x' => 'old', 'y' => ['x' => 'old deep']], 'j' => ['x' => 'old j']]; }
function g() { global $a; $a = ['k' => ['x' => 'new', 'y' => ['x' => 'new deep']], 'j' => ['x' => 'new j']]; return 'x'; }
function gk() { global $a; $a = ['k' => ['x' => 'new']]; return 'k'; }
class Box { public $p = ['k' => ['x' => 'old prop']]; static $s = ['k' => ['x' => 'old static']]; }
function gp() { global $o; $o->p = ['k' => ['x' => 'new prop']]; return 'x'; }
function gs() { Box::$s = ['k' => ['x' => 'new static']]; return 'x'; }
function go() { global $o; $o = new Box; $o->p = ['k' => ['x' => 'new object']]; return 'x'; }

function single() { global $a; return $a[gk()]; }
function two() { global $a; return $a['k'][g()]; }
function twoVar() { global $a; $k = 'k'; return $a[$k][g()]; }
function three() { global $a; return $a['k']['y'][g()]; }
function coalesce() { global $a; return $a['k'][g()] ?? 'default'; }
function issetIt() { global $a; unset($a['k']['x']); return isset($a['k'][gNew()]); }
function gNew() { global $a; $a = ['k' => ['x' => 'new']]; return 'x'; }
function emptyIt() { global $a; $a['k']['x'] = ''; return empty($a['k'][gNew()]); }
function keyOrder() { global $a; $i = 'k'; return $a[$i][$i = 'x']; }
function keyOrder2() { $b = [0 => [0 => 'zero-zero', 1 => 'zero-one'], 1 => [0 => 'one-zero', 1 => 'one-one']]; $i = 0; return $b[$i][$i++]; }
function keyOrder3() { $b = [0 => [0 => 'zero-zero', 1 => 'zero-one'], 1 => [0 => 'one-zero', 1 => 'one-one']]; $i = 0; return $b[$i++][$i]; }
function prop() { global $o; return $o->p['k'][gp()]; }
function staticProp() { return Box::$s['k'][gs()]; }
function objectVar() { global $o; return $o->p['k'][go()]; }
function write() { global $a; $a['k'][g()] = 'written'; return $a; }
function writeSingle() { global $a; $a[gk()] = 'written'; return $a; }
function append() { global $a; $a['k'][g()][] = 'appended'; return $a; }
function compound() { global $a; $a['k'][g()] .= ' more'; return $a; }
function unsetIt() { global $a; unset($a['k'][g()]); return $a; }
function local() { $b = ['k' => ['x' => 'old']]; $r = &$b; return $b['k'][(function () use (&$r) { $r = ['k' => ['x' => 'new']]; return 'x'; })()]; }
function callArg() { global $a; return strtoupper($a['k'][g()]); }
function inString() { global $a; return "{$a['k'][g()]}"; }

$o = new Box;
foreach (['single', 'two', 'twoVar', 'three', 'coalesce', 'issetIt', 'emptyIt', 'keyOrder', 'keyOrder2', 'keyOrder3',
        'prop', 'staticProp', 'objectVar', 'write', 'writeSingle', 'append', 'compound', 'unsetIt', 'local',
        'callArg', 'inString'] as $f) {
    reset_a();
    $o = new Box;
    Box::$s = ['k' => ['x' => 'old static']];
    try {
        echo $f, ': ', json_encode(@$f()), "\n";
    } catch (Throwable $e) {
        echo $f, ': ', get_class($e), ': ', $e->getMessage(), "\n";
    }
}
