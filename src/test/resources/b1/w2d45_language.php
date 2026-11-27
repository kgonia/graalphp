<?php
// B1 language level (PhpIrLowerer.lowerB1*): constructs the Week 2 harness needs, against PHP 8.5.13 output.
require __DIR__ . '/w2d45_included.php';
require_once __DIR__ . '/w2d45_included.php';
echo w2_included(), ' ', __LINE__, ' ', strlen(__DIR__) > 1 ? 'dir' : 'nodir', "\n";

define('W2_ANSWER', 42);
echo W2_ANSWER, ' ', defined('W2_ANSWER') ? 'yes' : 'no', ' ', defined('W2_NOPE') ? 'yes' : 'no', "\n";

// arithmetic and bitwise operators
$a = 17; $b = 5;
echo $a - $b, ' ', $a / $b, ' ', 20 / 4, ' ', $a << 2, ' ', $a >> 1, ' ', $a & $b, ' ', $a | $b, ' ', $a ^ $b, "\n";
echo -$a, ' ', -2.5 * 2, ' ', 1.0 / 4, ' ', -7 >> 1, ' ', 1 << 64, ' ', -1 >> 70, ' ', "ab" & "c", "\n";
echo PHP_INT_MAX - -1, ' ', 7 - "2", ' ', intdiv_like(9, 2), "\n";
try { echo 1 / 0; } catch (DivisionByZeroError $e) { echo get_message($e), "\n"; }
try { echo 1 << -1; } catch (Error $e) { echo get_message($e), "\n"; }
function intdiv_like($x, $y) { return ($x - $x % $y) / $y; }
function get_message($e) { return $e->getMessage(); }

// compound assignment on variables, dimensions, properties and static properties
$s = 10; $s -= 3; $s *= 2; $s /= 7; $s .= 'x'; echo $s, "\n";
$n = 6; $n <<= 2; $n >>= 1; $n &= 7; $n |= 8; $n ^= 3; $n %= 5; echo $n, "\n";
$arr = ['k' => 1, 'list' => [1, 2]];
$arr['k'] += 10;
$arr['list'][1] -= 5;
$arr['new'] .= 'made';
$i = 0;
$arr['list'][$i + 0] *= 7;
print_r_like($arr);
class Counter { public $n = 1; public static $total = 3; }
$c = new Counter();
$c->n += 4;
Counter::$total -= 1;
echo $c->n, ' ', Counter::$total, "\n";

// chained and expression assignments
$p = $q = $r = [1, 2];
$p[] = 3;
echo count($p), count($q), count($r), "\n";
$x = ($y = 5) + 1;
echo $x, $y, "\n";

// foreach by reference
$list = [1, 2, 3];
foreach ($list as $k => &$v) { $v = $v * 10 + $k; }
unset($v);
echo implode(',', $list), "\n";
$grid = [[1, 2], [3, 4]];
foreach ($grid as &$row) { foreach ($row as &$cell) { $cell += 100; } unset($cell); }
unset($row);
echo $grid[0][0], ' ', $grid[1][1], "\n";
$alias = $list;
foreach ($alias as &$v) { $v = 0; }
unset($v);
echo implode(',', $list), ' ', implode(',', $alias), "\n";

// by-reference array elements
$bodies = [[1], [2]];
$pairs = [];
$pairs[] = array(&$bodies[0], &$bodies[1]);
$first = &$pairs[0][0];
$first[0] = 99;
unset($first);
echo $bodies[0][0], "\n";

// nested unset
$tree = ['a' => ['b' => ['c' => 1, 'd' => 2]]];
unset($tree['a']['b']['c']);
unset($tree['x']['y']);
echo json_like($tree), "\n";

// instanceof, casts and static/visibility rules
final class Box {
    private $items = [];
    public static function make() { return new self(); }
    public function add($v) { $this->items[] = $v; return $this; }
    public function size() { return self::count($this->items); }
    private static function count($a) { return count($a); }
    public function total() { return $this->sum(); }
    private function sum() { $t = 0; foreach ($this->items as $i) { $t += $i; } return $t; }
}
$box = Box::make()->add(3)->add(4);
echo $box instanceof Box ? 'box' : 'nobox', ' ', $box->size(), ' ', $box->total(), ' ', 5 instanceof Box ? 'y' : 'n', "\n";
try { $box->sum(); } catch (Error $e) { echo get_message($e), "\n"; }
try { Box::size(); } catch (Error $e) { echo get_message($e), "\n"; }
try { Box::nope(); } catch (Error $e) { echo get_message($e), "\n"; }
echo json_like((array) 'one'), json_like((array) null), ' ', (float) '2.5abc', ' ', (bool) '0' ? 't' : 'f', "\n";

// function statics and magic constants
function counter() { static $calls = 0, $log = []; $calls++; $log[] = $calls; return __FUNCTION__ . ':' . implode('', $log); }
counter(); counter();
echo counter(), "\n";
class Named { public function who() { return __CLASS__ . ' ' . __METHOD__ . ' ' . __FUNCTION__; } }
echo (new Named())->who(), "\n";

// builtins
echo sprintf('%05d|%-4s|%x|%08x|%.3f|%0.9f|%5.1f|%s|%+d|%%|%c', 42, 'ab', 255, 3054, 3.14159, 1 / 3, -2.25, true, 7, 65), "\n";
echo sprintf("%d\t check: %d\n", 3, 4);
echo max(3, 9, 2), min([4, 1, 8]), max('apple', 'banana'), ' ', sqrt(16), ' ', sqrt(2), "\n";
echo json_like(array_fill(5, 3, 'v')), json_like(array_fill(0, 2, 0)), ' ', sizeof([1, 2, 3]), "\n";
$cur = ['x' => 1, 'y' => 2];
next($cur);
echo key($cur), ' ', reset($cur), ' ', key($cur), ' ', is_null(null) ? 'null' : 'set', "\n";

// dynamic properties: deprecated unless the class allows them
#[AllowDynamicProperties]
final class Loose {}
final class Strict {}
$loose = new Loose(); $loose->extra = 1;
$strict = new Strict(); $strict->extra = 2;
echo $loose->extra + $strict->extra, "\n";

// warnings of compound assignment on missing places
$fresh = [];
$fresh['missing'] += 1;
$undefinedVar[3] .= 'x';
echo json_like($fresh), json_like($undefinedVar), "\n";

function print_r_like($a) { echo json_like($a), "\n"; }
function json_like($v) {
    if (!is_array($v)) return is_string($v) ? '"' . $v . '"' : (string) $v;
    $parts = [];
    foreach ($v as $k => $item) $parts[] = $k . ':' . json_like($item);
    return '{' . implode(',', $parts) . '}';
}
