<?php
// Generators resume after the yield (issue 004): what an expression evaluated before its yield isn't evaluated
// again, in every position a yield can take; switch, match, foreach by reference and list() around a yield.

function f(...$args) { echo "f(", implode(',', array_map('json_encode', $args)), ")\n"; return count($args); }
function side($label, $value) { echo "side $label\n"; return $value; }

function drive($gen, ...$sent)
{
    echo json_encode($gen->current()), "\n";
    foreach ($sent as $value) {
        echo json_encode($gen->send($value)), "\n";
    }
    echo $gen->valid() ? "valid\n" : "done\n";
}

// issue 004's two cases
function issue004() { $a = 0; $x = f($a++, yield 1); var_dump($a, $x); }
drive(issue004(), 'sent');

function appendTarget() {
    $log = [];
    $log[side('target', 0)] = yield;
    $log[] = yield;
    $log[side('key', 'k')][side('inner', 'i')] = yield;
    var_dump($log);
}
drive(appendTarget(), 'one', 'two', 'three');

// yield in calls, nested calls, method calls, static calls, new
class Box
{
    public $items = [];
    public static $count = 0;
    public function __construct(...$args) { echo "new Box(", implode(',', $args), ")\n"; $this->items = $args; }
    public function add($a, $b) { echo "add($a, $b)\n"; return $this; }
    public static function make($a, $b) { echo "make($a, $b)\n"; return new Box($a, $b); }
}

function calls() {
    $i = 0;
    echo f(side('a', ++$i), f(side('b', ++$i), yield 'inner'), side('c', ++$i)), "\n";
    $box = new Box(side('x', 'x'), yield 'ctor');
    $box->add(side('y', 'y'), yield 'method')->add(yield 'chained', side('z', 'z'));
    Box::make(side('s', 's'), yield 'static');
    var_dump($i, $box->items);
}
drive(calls(), 'I', 'C', 'M', 'H', 'S');

// yield in array literals, string building, operators
function operands() {
    $n = 1;
    $array = [side('first', $n++), 'k' => yield 'array', side('last', $n++), ...[yield 'spread']];
    var_dump($array);
    $s = side('left', 'L') . (yield 'concat') . side('right', 'R');
    var_dump($s);
    $t = "a{$s}b" . (yield 'interp') . "c{$n}";
    var_dump($t);
    $sum = side('one', 1) + (yield 'plus') * side('two', 2);
    var_dump($sum);
    $cmp = side('cmp', 5) <=> (yield 'cmp');
    var_dump($cmp);
}
drive(operands(), 'A', ['S'], 'C', 'I', 10, 7);

// ternaries, short circuits, null coalescing
function logic() {
    $a = side('cond', true) ? yield 'then' : yield 'else';
    var_dump($a);
    $b = side('and', true) && (yield 'and');
    var_dump($b);
    $c = side('or', false) || (yield 'or');
    var_dump($c);
    $d = side('elvis', 0) ?: yield 'elvis';
    var_dump($d);
    $e = side('coalesce', null) ?? yield 'coalesce';
    var_dump($e);
    $f = !(yield 'not');
    var_dump($f);
}
drive(logic(), 'T', true, 0, 'E', 'C', false);

// assignments: compound, to properties, to list(), by reference
function assignments() {
    $x = 10;
    $x += yield 'compound';
    var_dump($x);
    $o = new stdClass;
    $o->{side('prop', 'p')} = yield 'property';
    $o->list[side('idx', 3)] = yield 'property element';
    var_dump($o);
    [$p, [$q, $r]] = [side('l1', 1), yield 'list'];
    var_dump($p, $q, $r);
    ['a' => $s, 'b' => $t] = yield 'keyed list';
    var_dump($s, $t);
    $u = &$o->list;
    $u[] = yield 'ref';
    var_dump($o->list);
}
drive(assignments(), 5, 'P', 'E', [2, 3], ['a' => 'A', 'b' => 'B'], 'R');

// switch and match around and in a yield
function choose($which) {
    switch (side('subject', $which)) {
        case side('case 1', 1):
            echo "one: ", yield 'one', "\n";
        case 2:
            echo "two: ", yield 'two', "\n";
            break;
        case yield 'case value':
            echo "matched a yielded case\n";
            break;
        default:
            echo "default: ", yield 'default', "\n";
    }
    $m = match (yield 'match subject') {
        side('arm a', 'a') => (yield 'arm a'),
        'b', (yield 'arm condition') => 'b or yielded',
        default => 'none',
    };
    var_dump($m);
}
drive(choose(1), 'O', 'T', 'b');
drive(choose(3), 3, 'z', 'q');
drive(choose(4), 0, 'D', 'a', 'A');

// foreach by reference and into list() around a yield
function loops() {
    $data = [1, 2, 3];
    foreach ($data as $i => &$v) {
        $v *= yield "by ref $i";
    }
    unset($v);
    var_dump($data);
    foreach ([[1, 2], [3, 4]] as [$a, $b]) {
        echo "pair $a $b: ", yield "pair $a", "\n";
    }
    foreach ([['x' => 1, 'y' => 2]] as ['x' => $x, 'y' => $y]) {
        echo "keyed $x $y: ", yield 'keyed', "\n";
    }
}
drive(loops(), 10, 20, 30, 'P1', 'P2', 'K');

// try/catch/finally around yields, yields in catch and finally
function guarded() {
    try {
        echo "try: ", yield 'try', "\n";
        throw new Exception('boom');
    } catch (Exception $e) {
        echo "caught ", $e->getMessage(), ": ", yield 'catch', "\n";
    } finally {
        echo "finally: ", yield 'finally', "\n";
    }
    try {
        try {
            yield 'inner try';
        } finally {
            echo "inner finally\n";
        }
    } finally {
        echo "outer finally\n";
    }
    return 'end';
}
$g = guarded();
drive($g, 'T', 'C', 'F', 'I');
var_dump($g->getReturn());

// loops resumed in the middle: while, do-while, for with yields in the condition and the step
function loopParts() {
    $i = 0;
    while (side("while $i", $i) < (yield 'while cond')) {
        $i++;
    }
    do {
        $i--;
    } while (yield "do $i");
    for ($j = yield 'for init'; $j < (yield 'for cond'); $j += yield 'for step') {
        echo "for body $j\n";
    }
    echo "i=$i j=$j\n";
}
drive(loopParts(), 2, 2, 3, true, false, 0, 2, 1, 2, 1, 2);
