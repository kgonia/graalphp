<?php
// Nested element writes, appends, compound assignments, increments and unset() in functions on bytecode
// (-Dpolyglot.php.Interpreter=bytecode): the containers made writable down the chain (copied when shared, created
// when unset or null), through references, on properties and ArrayAccess objects, with php-src's order of keys
// and values, and the copy-on-write rules (tla-cow.md).

class Bag implements ArrayAccess {
    public $items = [];

    function offsetExists($offset): bool {
        echo "[exists ", json_encode($offset), "]";
        return isset($this->items[$offset]);
    }

    function offsetGet($offset): mixed {
        echo "[get ", json_encode($offset), "]";
        return $this->items[$offset] ?? null;
    }

    function offsetSet($offset, $value): void {
        echo "[set ", json_encode($offset), "]";
        if ($offset === null) {
            $this->items[] = $value;
        } else {
            $this->items[$offset] = $value;
        }
    }

    function offsetUnset($offset): void {
        echo "[unset ", json_encode($offset), "]";
        unset($this->items[$offset]);
    }
}

class Holder {
    public $data = [];
    public $count = 0;

    function add($group, $item) {
        $this->data[$group][] = $item;
        $this->data[$group]['n'] ??= 0;
        $this->data[$group]['n']++;
        $this->count += 1;
        return $this;
    }

    function rename($group, $suffix) {
        $this->data[$group]['name'] = $group;
        $this->data[$group]['name'] .= $suffix;
        unset($this->data[$group][0]);
    }

    function drop($group) {
        unset($this->data[$group]);
    }
}

function side($label, $value) {
    echo "[$label]";
    return $value;
}

function show($label, $value) {
    echo $label, ': ', json_encode($value), "\n";
}

function writes() {
    $a = [];
    $a['x']['y'] = 1;
    $a['x']['z'][] = 2;
    $a['x']['z'][] = 3;
    $a[][] = 'appended nested';
    $a['list'] = [1, 2];
    $a['list'][5] = 'five';
    $a['list'][] = 'six';
    $i = 'x';
    $j = 'w';
    $a[$i][$j] = 'by variables';
    $a[$i . '2'][$j . '2'] = 'by expressions';
    $n = null;
    $n['created']['deep'] = true;
    $u['undefined']['too'] = 1;
    show('writes', [$a, $n, $u]);
    $r = ($a['x']['y'] = 'value of the assignment');
    show('assignment value', $r);
}

function compounds() {
    $a = ['s' => ['t' => 'a'], 'n' => ['m' => 1], 'f' => [1.5]];
    $a['s']['t'] .= 'b';
    $a['n']['m'] += 10;
    $a['n']['m'] *= 2;
    $a['n']['m'] -= 1;
    $a['n']['m'] %= 7;
    $a['n']['m'] **= 3;
    $a['n']['m'] <<= 2;
    $a['n']['m'] |= 1;
    $a['f'][0] /= 2;
    $a['n']['missing'] ??= 'filled';
    $a['n']['m'] ??= 'kept';
    $k = 'k';
    $a[$k] ??= [];
    $a[$k][$k] ??= 'deep';
    $a['top'] = 'x';
    $a['top'] .= 'y';
    $a['cnt'] = 0;
    $a['cnt'] += 5;
    show('compound', $a);
    $x = ['v' => ['w' => 1]];
    echo 'compound value: ', $x['v']['w'] += 4, "\n";
}

function steps() {
    $a = ['i' => ['j' => 1], 'f' => [1.5], 's' => ['a']];
    $a['i']['j']++;
    ++$a['i']['j'];
    $a['i']['j']--;
    $old = $a['i']['j']++;
    $new = ++$a['i']['j'];
    $a['f'][0]++;
    $b = ['c' => PHP_INT_MAX];
    $b['c']++;
    $c = [5];
    $c[0]++;
    $c[0]--;
    show('steps', [$a, $old, $new, $b, $c]);
}

function unsets() {
    $a = ['x' => ['y' => 1, 'z' => 2], 'list' => [1, 2, 3], 'keep' => 1];
    unset($a['x']['y']);
    unset($a['list'][1]);
    unset($a['missing']['deep']);
    unset($a['x']['missing']);
    $k = 'list';
    unset($a[$k][0]);
    unset($a['keep']);
    show('unset', $a);
    $s = ['str' => 'abc'];
    $n = null;
    unset($n['a']['b']);
    show('unset scalars', [$s, $n]);
    $copy = $a;
    unset($copy['x']['z']);
    show('unset copy', [$a, $copy]);
}

function copies() {
    $a = ['x' => ['y' => 1]];
    $b = $a;
    $b['x']['y'] = 2;
    $c = $a['x'];
    $a['x']['y'] = 3;
    $a['x']['new'][] = 'n';
    $d = $a;
    $d['x']['y']++;
    $d['x']['y'] .= 'd';
    show('copies', [$a, $b, $c, $d]);
    $self = [[1]];
    $self[0][1] = $self;
    show('self', $self);
    $e = [[1, 2], [3]];
    $f = $e;
    unset($f[0][0]);
    $f[1][] = 4;
    show('copy unset', [$e, $f]);
}

function references() {
    $a = ['x' => ['y' => 1]];
    $r = &$a['x'];
    $a['x']['y'] = 'through the array';
    show('ref sees', $r);
    $r['z'] = 'through the ref';
    show('array sees', $a);
    $b = $a;
    $b['x']['y'] = 'b writes the shared reference';
    show('ref shared by the copy', [$a['x']['y'], $b['x']['y']]);
    $v = [1, 2];
    $w = &$v;
    $w[5][6] = 'deep';
    show('ref variable', $v);
    unset($r);
    $q = [[0]];
    $ref = &$q[0][0];
    $q[0][0]++;
    $q[0][0] .= 'x';
    show('ref element', [$q, $ref]);
}

function objects() {
    $h = new Holder();
    $h->add('a', 1)->add('a', 2)->add('b', 3);
    $h->rename('a', '!');
    show('holder', [$h->data, $h->count]);
    $h->drop('b');
    show('dropped', $h->data);
    $o = new stdClass();
    $o->list['k'][] = 1;
    $o->list['k'][] = 2;
    $o->list['k'][0] += 10;
    $o->list['k'][1]++;
    unset($o->list['k'][1]);
    $name = 'list';
    show('stdClass', $o);
    $a = ['obj' => new stdClass()];
    try {
        $a['obj']['k'] = 1;
    } catch (Error $e) {
        echo $e::class, ': ', $e->getMessage(), "\n";
    }
}

function access() {
    $bag = new Bag();
    $bag['a'] = 1;
    $bag[] = 'appended';
    $a = ['bag' => $bag];
    $a['bag']['b'] = 2;
    $a['bag'][] = 3;
    $a['bag']['a'] += 5;
    $a['bag']['s'] = 'x';
    $a['bag']['s'] .= 'y';
    echo "\n";
    unset($a['bag']['b']);
    unset($bag['missing']);
    echo "\n";
    show('bag', $bag->items);
}

function errors() {
    $i = 5;
    $t = true;
    try {
        $i[0][1] = 1;
    } catch (Error $e) {
        echo 'int: ', $e::class, ': ', $e->getMessage(), "\n";
    }
    try {
        $t['a']['b'] = 1;
    } catch (Error $e) {
        echo 'true: ', $e::class, ': ', $e->getMessage(), "\n";
    }
    $a = ['int' => 1, 'str' => 'abc'];
    try {
        $a['int']['x'] = 1;
    } catch (Error $e) {
        echo 'element int: ', $e::class, ': ', $e->getMessage(), "\n";
    }
    try {
        $a['int']['x']['y'] = 1;
    } catch (Error $e) {
        echo 'element int deep: ', $e::class, ': ', $e->getMessage(), "\n";
    }
    try {
        $a['int']['x'] .= 's';
    } catch (Error $e) {
        echo 'compound int: ', $e::class, ': ', $e->getMessage(), "\n";
    }
    try {
        $a[[]]['x'] = 1;
    } catch (TypeError $e) {
        echo 'array key: ', $e::class, ': ', $e->getMessage(), "\n";
    }
    try {
        $a['x'][new stdClass()] = 1;
    } catch (TypeError $e) {
        echo 'object key: ', $e::class, ': ', $e->getMessage(), "\n";
    }
    show('after errors', $a);
}

function order() {
    // keys with side effects, the value with side effects: those the tree evaluates in php-src's order
    $a = ['a' => ['b' => 'v']];
    $k = 0;
    $a[side('k1', 'a')][side('k2', 'b')] .= side('value', 'x');
    echo "\n";
    $a[$k][$k] = $k++;
    show('order', [$a, $k]);
}

function counters($words) {
    $count = [];
    $by = [];
    foreach ($words as $w) {
        $first = $w[0];
        $count[$first] ??= 0;
        $count[$first]++;
        $by[$first][] = $w;
        $by[$first]['len'] ??= 0;
        $by[$first]['len'] += strlen($w);
    }
    return [$count, $by];
}

function matrix($n) {
    $m = [];
    for ($i = 0; $i < $n; $i++) {
        for ($j = 0; $j < $n; $j++) {
            $m[$i][$j] = $i * $j;
        }
    }
    for ($i = 0; $i < $n; $i++) {
        $m[$i][$i] += 100;
        $m[$i][$n - 1 - $i] *= -1;
    }
    unset($m[0][0]);
    return $m;
}

function gen() {
    $a = [];
    $a['x'][yield 'key'] = yield 'value';
    $a['x']['k'] .= yield 'append';
    $a['c'][] = yield 'list';
    unset($a['x'][yield 'unset key']);
    return $a;
}

writes();
compounds();
steps();
unsets();
copies();
references();
objects();
access();
errors();
order();
show('counters', counters(['apple', 'avocado', 'banana', 'blueberry', 'cherry']));
show('matrix', matrix(3));
$g = gen();
echo json_encode($g->current()), "\n";
foreach (['k', 'v', '!', 'item', 'k'] as $send) {
    echo json_encode($g->send($send)), "\n";
}
show('gen', $g->getReturn());
