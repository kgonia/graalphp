<?php
// Loose comparison (==, !=, <, <=, >, >=, <=>) as zend_compare: objects property by property, objects of
// different classes, objects against scalars and arrays, recursion, numeric and overflowing strings, NAN, null
// and bools, arrays, resources, sort() and in_array() over objects.
class P { public $a; public $b; function __construct($a, $b) { $this->a = $a; $this->b = $b; } }
class Q { public $a; public $b; function __construct($a, $b) { $this->a = $a; $this->b = $b; } }
class S { public $v; function __construct($v) { $this->v = $v; } function __toString(): string { return (string) $this->v; } }
class E {}
class Child extends P {}

function show($label, $x, $y) {
    $r = [];
    foreach (['==', '!=', '<', '<=', '>', '>=', '<=>'] as $op) {
        try {
            if ($op === '==') {
                $v = @($x == $y);
            } elseif ($op === '!=') {
                $v = @($x != $y);
            } elseif ($op === '<') {
                $v = @($x < $y);
            } elseif ($op === '<=') {
                $v = @($x <= $y);
            } elseif ($op === '>') {
                $v = @($x > $y);
            } elseif ($op === '>=') {
                $v = @($x >= $y);
            } else {
                $v = @($x <=> $y);
            }
            $r[] = $op . ':' . var_export($v, true);
        } catch (\Throwable $e) {
            $r[] = $op . ':' . get_class($e) . '(' . $e->getMessage() . ')';
        }
    }
    echo str_pad($label, 34), implode(' ', $r), "\n";
}

show('P(1,2) P(1,2)', new P(1, 2), new P(1, 2));
show('P(1,2) P(1,3)', new P(1, 2), new P(1, 3));
show('P(1,3) P(1,2)', new P(1, 3), new P(1, 2));
show('P(2,1) P(1,9)', new P(2, 1), new P(1, 9));
show('P(1,2) Q(1,2)', new P(1, 2), new Q(1, 2));
show('P(1,2) Child(1,2)', new P(1, 2), new Child(1, 2));
show('P("1",2) P(1,2.0)', new P('1', 2), new P(1, 2.0));
show('P("abc",0) P("ABC",0)', new P('abc', 0), new P('ABC', 0));
show('P(null,0) P(false,0)', new P(null, 0), new P(false, 0));
show('P([1,2],0) P([1,2],0)', new P([1, 2], 0), new P([1, 2], 0));
show('P([1,2],0) P([1,3],0)', new P([1, 2], 0), new P([1, 3], 0));
show('P(P(1,1),0) P(P(1,1),0)', new P(new P(1, 1), 0), new P(new P(1, 1), 0));
show('P(P(1,1),0) P(P(1,2),0)', new P(new P(1, 1), 0), new P(new P(1, 2), 0));
show('E E', new E, new E);
$same = new P(1, 2);
show('same same', $same, $same);
$x = new P(1, 2);
$y = new P(1, 2);
@$x->extra = 1;
show('P+extra P', $x, $y);
show('P P+extra', $y, $x);
@$y->extra = 1;
show('P+extra P+extra', $x, $y);
$y->extra = 2;
show('P+extra=1 P+extra=2', $x, $y);
@$y->other = 1;
show('P+1dyn P+2dyn', $x, $y);
$u = new P(1, 2);
$v = new P(1, 2);
unset($u->b);
show('P(unset b) P', $u, $v);
show('P P(unset b)', $v, $u);
unset($v->b);
show('P(unset b) P(unset b)', $u, $v);
$o1 = new stdClass;
$o1->a = 1;
$o2 = new stdClass;
$o2->a = 1;
show('stdClass{a:1} stdClass{a:1}', $o1, $o2);
$o2->b = 2;
show('{a:1} {a:1,b:2}', $o1, $o2);
$o3 = new stdClass;
$o3->b = 2;
$o3->a = 1;
show('{a:1,b:2} {b:2,a:1}', $o2, $o3);
$o4 = new stdClass;
$o4->c = 1;
show('{a:1} {c:1}', $o1, $o4);
show('{c:1} {a:1}', $o4, $o1);
show('E stdClass', new E, new stdClass);
// objects against other types
foreach ([null, true, false, 0, 1, 2, 1.0, 0.5, '', 'abc', '1', [], [1]] as $scalar) {
    $label = var_export($scalar, true);
    show('E ' . $label, new E, $scalar);
    show($label . ' E', $scalar, new E);
}
foreach (['5', '10', 'abc', '', ' 5', '5.0'] as $s) {
    show('S(5) ' . var_export($s, true), new S(5), $s);
    show(var_export($s, true) . ' S(5)', $s, new S(5));
}
show('S(5) 5', new S(5), 5);
show('S(5) S(5)', new S(5), new S(5));
// recursion
$r1 = new stdClass;
$r1->self = $r1;
$r2 = new stdClass;
$r2->self = $r2;
show('recursive objects', $r1, $r2);
show('recursive same', $r1, $r1);
$c1 = new stdClass;
$c1->x = 1;
$c1->self = $c1;
$c2 = new stdClass;
$c2->x = 2;
$c2->self = $c2;
show('recursive differing early', $c1, $c2);
$a1 = [1];
$a1[] = &$a1;
$a2 = [1];
$a2[] = &$a2;
show('recursive arrays', $a1, $a2);
show('recursive array same', $a1, $a1);
// strings
$strings = ['1', '01', '1.0', '1e0', ' 1', '1 ', 'abc', 'ABC', '', '0', '-0', '0.0', 'null', '1e3', '1000',
    '9223372036854775807', '9223372036854775808', '9223372036854775809', '-9223372036854775809',
    '-9223372036854775810', '1e1000', '-1e1000', '9223372036854775808.0', '0x1A', '26', '.5', '0.5', '5.', 'INF'];
foreach ($strings as $i => $s) {
    foreach ([$strings[($i + 1) % count($strings)], $strings[($i + 7) % count($strings)], $s] as $t) {
        show(var_export($s, true) . ' ' . var_export($t, true), $s, $t);
    }
}
foreach (['9223372036854775807', '9223372036854775808', '1e1000', 'abc', '', '1'] as $s) {
    show(var_export($s, true) . ' PHP_INT_MAX', $s, PHP_INT_MAX);
    show(var_export($s, true) . ' 9.2233720368547758E18', $s, 9.2233720368547758E18);
    show(var_export($s, true) . ' INF', $s, INF);
}
// NAN, null, bools, numbers
$scalars = [NAN, INF, -INF, null, true, false, 0, 1, -1, 0.0, -0.0, 1.5, '', 'a', '0', 'NAN', [], [0]];
foreach ($scalars as $p) {
    foreach ($scalars as $q) {
        show(var_export($p, true) . ' ' . var_export($q, true), $p, $q);
    }
}
// arrays
show('[1,2] [1,2]', [1, 2], [1, 2]);
show('[1,2] [2,1]', [1, 2], [2, 1]);
show('[a=>1,b=>2] [b=>2,a=>1]', ['a' => 1, 'b' => 2], ['b' => 2, 'a' => 1]);
show('[a=>1] [b=>1]', ['a' => 1], ['b' => 1]);
show('[b=>1] [a=>1]', ['b' => 1], ['a' => 1]);
show('[1] [1,2]', [1], [1, 2]);
show('["1"] [1]', ['1'], [1]);
show('[[1]] [[2]]', [[1]], [[2]]);
show('[P(1,2)] [P(1,2)]', [new P(1, 2)], [new P(1, 2)]);
show('[1] 5', [1], 5);
show('5 [1]', 5, [1]);
show('[1] "a"', [1], 'a');
// resources
$f1 = fopen('php://memory', 'r');
$f2 = fopen('php://memory', 'r');
show('res res', $f1, $f2);
show('res same', $f1, $f1);
show('res int', $f1, 1);
show('res true', $f1, true);
show('res null', $f1, null);
// built-ins using loose comparison
$list = [new P(3, 1), new P(1, 2), new P(2, 0), new P(1, 1)];
sort($list);
foreach ($list as $p) {
    echo $p->a, ',', $p->b, ' ';
}
echo "\n";
var_dump(in_array(new P(1, 2), [new P(1, 2)]), in_array(new P(1, 2), [new Q(1, 2)]), in_array(new P(1, 2), [new P(1, 2)], true));
var_dump(array_search(new P(2, 0), $list), max(new P(1, 1), new P(1, 2))->b, min([new P(5, 5), new P(4, 9)])->a);
var_dump(array_keys([new P(1, 1), new P(2, 2), new P(1, 1)], new P(1, 1)));
switch (new P(1, 2)) {
    case new P(1, 3):
        echo "wrong\n";
        break;
    case new P(1, 2):
        echo "matched\n";
        break;
}
$d1 = new DateTime('2024-01-01 00:00:00', new DateTimeZone('UTC'));
$d2 = new DateTimeImmutable('2024-01-01 01:00:00', new DateTimeZone('Europe/Warsaw'));
$d3 = new DateTime('2024-06-01 00:00:00', new DateTimeZone('UTC'));
show('DateTime DateTimeImmutable', $d1, $d2);
show('DateTime later', $d1, $d3);
show('later DateTime', $d3, $d1);
var_dump(new DateTimeZone('UTC') == new DateTimeZone('UTC'), new DateTimeZone('UTC') == new DateTimeZone('Europe/Warsaw'));
var_dump(@(new DateInterval('P1D') == new DateInterval('P1D')));
