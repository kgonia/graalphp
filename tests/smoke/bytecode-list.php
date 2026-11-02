<?php
// list() and [...] as assignment targets in functions on bytecode (-Dpolyglot.php.Interpreter=bytecode): nested,
// keyed and skipped entries, every kind of target, php-src's order (the value, then each element's key, read and
// target), a value that isn't an array, ArrayAccess values read through offsetGet(), a list writing the variable it
// is read from, and foreach into lists, elements and properties.

function side($label, $value) {
    echo "[$label]";
    return $value;
}

class Pairs implements ArrayAccess {
    function offsetExists($offset): bool {
        echo "[exists ", json_encode($offset), "]";
        return true;
    }

    function offsetGet($offset): mixed {
        echo "[get ", json_encode($offset), "]";
        return is_int($offset) ? $offset * 10 : ($offset === 'other' ? ['o'] : "v-$offset");
    }

    function offsetSet($offset, $value): void {
        echo "[set ", json_encode($offset), "]";
    }

    function offsetUnset($offset): void {
        echo "[unset ", json_encode($offset), "]";
    }
}

class Target {
    public $a;
    public $items = [];
}

function basics() {
    [$a, $b] = [1, 2];
    var_dump($a, $b);
    list($c, list($d, $e)) = [3, [4, 5]];
    var_dump($c, $d, $e);
    [, $f, , $g] = [6, 7, 8, 9];
    var_dump($f, $g);
    ['x' => $h, 'y' => $i] = ['y' => 'Y', 'x' => 'X'];
    var_dump($h, $i);
    [1 => $j, '0' => $k] = ['zero', 'one'];
    var_dump($j, $k);
    ['outer' => ['inner' => $l]] = ['outer' => ['inner' => 'deep']];
    var_dump($l);
    [$m, [, $n]] = [10, [20, 30]];
    var_dump($m, $n);
    // the value is the assignment's result
    var_dump([$o, $p] = ['first', 'second']);
    $q = [$r] = [99];
    var_dump($q, $r);
}
basics();

function swaps() {
    $a = 1;
    $b = 2;
    [$a, $b] = [$b, $a];
    var_dump($a, $b);
    $arr = [1, 2];
    // the right $arr is read before the targets write it
    [$arr[1], $arr[0]] = $arr;
    var_dump($arr);
    $nested = [[1, 2], [3, 4]];
    [$nested[1], $nested[0]] = $nested;
    var_dump($nested);
    $list = ['a', 'b', 'c'];
    [$list[2], $list[1], $list[0]] = $list;
    var_dump($list);
    $copy = $list;
    [$copy[0]] = $list;
    var_dump($copy, $list);
}
swaps();

function targets() {
    $o = new Target;
    $arr = [];
    [$o->a, $o->items[], $arr['k'], $arr['n']['m']] = ['prop', 'appended', 'element', 'nested'];
    var_dump($o, $arr);
    $i = 0;
    [$arr[$i++], $arr[$i++]] = ['zero', 'one'];
    var_dump($arr, $i);
    [$arr[side('k1', 'p')], [$arr[side('k2', 'q')]]] = side('value', ['P', ['Q']]);
    echo "\n";
    var_dump($arr['p'], $arr['q']);
    [side('key a', 'a') => $x, side('key b', 'b') => $y] = side('keyed', ['a' => 1, 'b' => 2]);
    echo "\n";
    var_dump($x, $y);
}
targets();

// Generators run on bytecode in either interpreter: what the tree does differently from php-src is tested in them
// (the tree's list() reads no ArrayAccess object, and its foreach assigns the key before the value).
function drive($generator) {
    foreach ($generator as $ignored) {
    }
}

function notArrays() {
    yield;
    [$a, $b] = null;
    var_dump($a, $b);
    $pairs = new Pairs;
    [$c, $d] = $pairs;
    echo "\n";
    var_dump($c, $d);
    ['name' => $e, 'other' => [$f]] = $pairs;
    echo "\n";
    var_dump($e, $f);
    [$g, [$h]] = [1, null];
    var_dump($g, $h);
}
drive(notArrays());

function loops() {
    foreach ([[1, 2], [3, 4]] as [$a, $b]) {
        echo "$a $b\n";
    }
    foreach (['p' => ['x' => 1, 'y' => 2], 'q' => ['x' => 3, 'y' => 4]] as $key => ['x' => $x, 'y' => $y]) {
        echo "$key: $x $y\n";
    }
    foreach ([[1, [2, 3]]] as [$a, [$b, $c]]) {
        echo "$a $b $c\n";
    }
    $o = new Target;
    foreach (['first', 'second'] as $o->a) {
        echo $o->a, "\n";
    }
    $arr = [];
    foreach (['a' => 1, 'b' => 2] as $arr[]) {
    }
    var_dump($arr);
    $keys = [];
    foreach (['x' => 1, 'y' => 2] as $keys[] => $v) {
    }
    var_dump($keys, $v);
    $o->items = [];
    foreach ([10, 20] as $o->items[]) {
    }
    var_dump($o->items);
    foreach ([[1, 2], [3, 4]] as [$arr['first'], $arr['second']]) {
        echo json_encode($arr), "\n";
    }
    $rows = [[1, 2], [3, 4]];
    foreach ($rows as [$p, $q]) {
        $rows[] = [$q, $p];
    }
    var_dump(count($rows), $p, $q);
    foreach ([[1, 2], [3]] as [$m, $n]) {
        var_dump($m);
        break;
    }
}
loops();

function loopOrder() {
    yield;
    $arr = [];
    // the value's target is assigned before the key's
    $k = 'start';
    foreach (['a' => 1, 'b' => 2] as $k => $arr[$k]) {
    }
    var_dump($arr);
    $o = new Target;
    $i = 5;
    foreach ([10, 20] as $i => $o->items[$i * 2]) {
    }
    var_dump($o->items);
    foreach ([[1, 2], [3, 4]] as $i => [$arr['first'], $arr[$i]]) {
        echo $i, ": ", json_encode($arr), "\n";
    }
    $grid = [[1, 2], [3, 4]];
    $i = 0;
    foreach ($grid as $i => [$grid[$i][1], $grid[$i][0]]) {
    }
    var_dump($grid);
    // the right side is read before the targets write it
    $pair = [1, 2];
    [$pair[1], $pair[0]] = $pair;
    var_dump($pair);
}
drive(loopOrder());

function generator() {
    [$a, $b] = yield 'pair';
    echo "got $a $b\n";
    [$c, [$d, $e]] = [yield 'c', [yield 'd', 'e']];
    echo "got $c $d $e\n";
    foreach ([[1, 2], [3, 4]] as [$x, $y]) {
        $sum = yield "$x+$y";
        echo "sum $sum\n";
    }
    $key = 'before';
    foreach (['k' => 'v'] as $key => $values[$key]) {
        yield $values;
    }
}
$g = generator();
echo $g->current(), "\n";
echo $g->send([1, 2]), "\n";
echo $g->send('C'), "\n";
echo $g->send('D'), "\n";
echo $g->send(3), "\n";
var_dump($g->send(7));
var_dump($g->send(null));
