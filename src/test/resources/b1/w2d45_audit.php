<?php
// Regressions for the Neon audit of W2 D4-5 Step 1, against PHP 8.5.13 output (w2d45_audit.stdout).

// P1-8: the B1-level expressions around yield in a generator body.
final class Probe {}
function gen_ops() {
    $a = [5];
    $a[0] += yield 1;
    echo 'dim-op ', $a[0], "\n";
    $f = (float) (yield 2);
    var_dump($f);
    $x = ($y = yield 3);
    echo 'assign ', $x, ' ', $y, "\n";
    $v = 'ref';
    $arr = [&$v, yield 4];
    $arr[0] = 'changed';
    echo 'by-ref item ', $v, ' ', $arr[1], "\n";
    $is = (yield 5) instanceof Probe;
    var_dump($is);
    $m = ['a' => [1, 2]];
    foreach ($m[yield 6] as &$e) { $e *= 3; }
    unset($e);
    echo 'foreach ', implode(',', $m['a']), "\n";
    $keyed = ['k' => yield 7, 'k' => &$v];
    $keyed['k'] = 'via-key';
    echo 'keyed ', $v, "\n";
    return 'done';
}
$g = gen_ops();
echo $g->current(), "\n";
foreach ([10, '2.5', 'second', 'item', new Probe(), 'a', 'k'] as $sent) {
    $g->send($sent);
    if ($g->valid()) echo 'yielded ', $g->current(), "\n";
}
echo $g->getReturn(), "\n";

// P1-3: a duplicate key in an array literal replaces the element, reference or not.
$a = 1; $b = 2;
$d = ['k' => &$a, 'k' => &$b];
$d['k'] = 20;
echo $a, ' ', $b, "\n";
$e = ['k' => &$a, 'k' => 5];
$e['k'] = 7;
echo $a, ' ', $e['k'], "\n";
$f = ['k' => 5, 'k' => &$a, 1 => 'x', 1 => 'y'];
$f['k'] = 9;
echo $a, ' ', $f[1], ' ', count($f), "\n";

// P1-4: max()/min() with a second argument of null.
var_dump(max(5, null), min(5, null), max(-5, null), min(-5, null), max([1, 2], null), min([1, 2], null), max(0.0, null), min('', null));
var_dump(min(0, null), min(null, 0), max(null, ''), min(['', null]), min(0, false, null), max(null, false, 0));
try { max(null); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }

// P1-5: array_fill() keys from a negative start, and past PHP_INT_MAX.
echo json_like(array_fill(-5, 3, 'v')), json_like(array_fill(-1, 3, 0)), json_like(array_fill(PHP_INT_MAX - 1, 2, 1)), "\n";
try { array_fill(PHP_INT_MAX, 2, 1); } catch (Error $e) { echo $e->getMessage(), "\n"; }
try { array_fill(PHP_INT_MAX - 1, 3, 1); } catch (Error $e) { echo $e->getMessage(), "\n"; }
echo json_like(array_fill(PHP_INT_MAX, 1, 'last')), count(array_fill(3, 0, 1)), "\n";

// P1-6: the result of $x = ($q = $payload) is held before the old value of $q is destroyed.
final class Payload { public function name() { return 'payload'; } public function __destruct() { echo "payload destroyed\n"; } }
final class Reenter { public function __destruct() { global $q, $payload; $q = null; $payload = null; echo "reenter\n"; } }
$payload = new Payload();
$q = new Reenter();
$x = ($q = $payload);
echo $x->name(), ' ', $q === null ? 'q-null' : 'q-set', "\n";
$x = null;
echo "after\n";

// P1-7: by-reference foreach sources are fetched for writing.
foreach ($undefinedSource as &$v) { echo 'never'; }
unset($v);
var_dump(isset($undefinedSource));
final class Typed { public array $list; public $plain; }
$t = new Typed();
try { foreach ($t->list as &$v) { echo 'never'; } } catch (Error $e) { echo $e->getMessage(), "\n"; }
unset($v);
foreach ($t->plain as &$v) { echo 'never'; }
unset($v);
foreach ($t->dynamic as &$v) { echo 'never'; }
unset($v);
$holder = [];
foreach ($holder['missing'] as &$v) { echo 'never'; }
unset($v);
foreach ($holder['a']['b'] as &$v) { echo 'never'; }
unset($v);
echo json_like($holder), "\n";
$text = 'str';
try { foreach ($text[0] as &$v) {} } catch (Error $e) { echo $e->getMessage(), "\n"; }
try { $refs = [&$text[0]]; } catch (Error $e) { echo $e->getMessage(), "\n"; }

// P1-9: visibility of callables and constructors.
final class Vis {
    private static function hidden() { return 'hidden'; }
    private function inst() { return 'inst'; }
    private function __construct() {}
    public static function make() { return new self(); }
    public static function inside() {
        return json_like([is_callable(['Vis', 'hidden']), is_callable([self::make(), 'inst']), implode(',', array_map(['Vis', 'hidden'], [1]))]);
    }
}
var_dump(is_callable(['Vis', 'hidden']), is_callable([Vis::make(), 'inst']));
try { array_map(['Vis', 'hidden'], [1]); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { array_map([Vis::make(), 'inst'], [1]); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { new Vis(); } catch (Error $e) { echo $e->getMessage(), "\n"; }
echo Vis::inside(), "\n";

// P1-10 is a lowering check (WeekTwoBenchParityTest). P2-1: ArithmeticError is catchable.
try { echo 1 >> -1; } catch (ArithmeticError $e) { echo get_message($e), "\n"; }
try { echo intdiv_like(1, 0); } catch (ArithmeticError $e) { echo get_message($e), "\n"; }
function intdiv_like($x, $y) { return $x / $y; }
function get_message($e) { return $e->getMessage(); }

// P2-2: define() and defined().
var_dump(define('W2A_CI', 1, true), define('W2A_CS', 2, false), define('\W2A_LEAD', 3), defined('W2A_LEAD'), defined('\W2A_LEAD'));
var_dump(define('W2A_CI', 9), W2A_CI, defined('\W2A_CI'), defined('true'), defined('NULL'), defined('PHP_INT_MAX'), defined('INF'), defined('w2a_ci'));
$source = [1, [2]];
$alias = &$source[0];
define('W2A_ARR', $source);
$alias = 100;
$copy = W2A_ARR;
$copy[1][] = 3;
echo W2A_ARR[0], ' ', count(W2A_ARR[1]), ' ', count($copy[1]), "\n";
var_dump(define(5, 'five'), defined(5), define('', 'empty'), defined(null));
try { define('A::B', 1); } catch (ValueError $e) { echo $e->getMessage(), "\n"; }
try { define([], 1); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { defined([]); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { define('W2A_BAD', 1, []); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }

// P2-3: sprintf.
echo sprintf('[%5c|%-5c|%05c|%\'*5c]', 65, 66, 67, 68), sprintf('[%.s|%.0s|%.2s|%.f]', 'abc', 'abc', 'abc', 3.7), "\n";
foreach (['%99999999999999999999$s', '%2147483647$s', '%0$s', '%99999999999999999999d', '%2147483647d', '%.99999999999999999999f', '%.2147483647f', '%2147483646$s'] as $format) {
    try { echo sprintf($format, 1), "\n"; } catch (Error $e) { echo $e->getMessage(), "\n"; }
}
echo sprintf('%.1f', new Probe()), ' ', sprintf('none'), ' ', sprintf('%2$s-%1$s', 'a', 'b'), "\n";

// P2-7: compound assignment on an undefined property.
final class Bag {}
$bag = new Bag();
$bag->n += 1;
$bag->m['k'] .= 'x';
echo $bag->n, json_like($bag->m), "\n";

function json_like($v) {
    if (!is_array($v)) return is_string($v) ? '"' . $v . '"' : ($v === true ? 'true' : ($v === false ? 'false' : (string) $v));
    $parts = [];
    foreach ($v as $k => $item) $parts[] = $k . ':' . json_like($item);
    return '{' . implode(',', $parts) . '}';
}
echo "end\n";
