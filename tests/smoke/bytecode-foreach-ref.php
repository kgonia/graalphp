<?php
// foreach by reference in functions on bytecode: over variables, elements,
// properties, calls returning by reference and temporaries; the value bound to each element's reference, the
// array the variable holds followed as it changes (appends, copies, reassignment), targets that are elements and
// properties, the loop's end however it ends; and by-reference generators, which the tree doesn't iterate.

set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (line $line)\n";
    return true;
});

class Holder {
    public $list = [1, 2, 3];
    public $target;
    private $data = ['a' => 1];

    function &data() {
        return $this->data;
    }

    function dump() {
        var_dump($this->data);
    }
}

function &numbers() {
    static $numbers = [1, 2, 3];
    return $numbers;
}

function variables() {
    $a = [1, 2, 3];
    foreach ($a as &$v) {
        $v *= 2;
    }
    unset($v);
    var_dump($a);
    $b = ['x' => 1, 'y' => 2];
    foreach ($b as $k => &$v) {
        $v = "$k=$v";
    }
    var_dump($b, $v);
    // the classic: the reference left in $v
    foreach ($b as $v) {
    }
    var_dump($b);
    unset($v);
    $nested = [[1, 2], [3]];
    foreach ($nested as &$row) {
        foreach ($row as &$cell) {
            $cell += 10;
        }
        unset($cell);
        $row[] = 'end';
    }
    unset($row);
    var_dump($nested);
}
variables();

function changes() {
    $a = [1, 2];
    foreach ($a as $i => &$v) {
        if ($i < 3) {
            $a[] = $v + 10;
        }
    }
    unset($v);
    var_dump($a);
    $b = [1, 2, 3];
    foreach ($b as $i => &$v) {
        if ($i === 0) {
            $copy = $b;
            $v = 'first';
        }
    }
    unset($v);
    var_dump($b, $copy);
    $c = [1, 2, 3];
    $replaced = false;
    foreach ($c as $i => &$v) {
        if (!$replaced) {
            $replaced = true;
            $c = ['x', 'y', 'z'];
        }
        echo "$i: $v\n";
    }
    unset($v);
    $d = [1, 2, 3, 4];
    foreach ($d as $i => &$v) {
        if ($i === 1) {
            unset($d[2]);
        }
        echo "$i ";
    }
    echo "\n";
    unset($v);
    $e = [1, 2, 3];
    foreach ($e as &$v) {
        if ($v === 2) {
            break;
        }
    }
    $v = 'broke at two';
    var_dump($e);
    unset($v);
    $f = [1, 2];
    try {
        foreach ($f as &$v) {
            throw new Exception("out at $v");
        }
    } catch (Exception $ex) {
        echo $ex->getMessage(), "\n";
    }
    $v = 'thrown';
    var_dump($f);
}
changes();

function subjects() {
    $arr = ['list' => [1, 2]];
    foreach ($arr['list'] as &$v) {
        $v *= 3;
    }
    unset($v);
    foreach ($arr['new'] as &$v) {
    }
    var_dump($arr);
    $o = new Holder;
    foreach ($o->list as &$v) {
        $v = -$v;
    }
    unset($v);
    var_dump($o->list);
    foreach (numbers() as &$n) {
        $n *= 100;
    }
    unset($n);
    var_dump(numbers());
    foreach ($o->data() as $k => &$d) {
        $d = "changed $k";
    }
    unset($d);
    $o->dump();
    $temporary = [1, 2];
    foreach ([1, 2, 3] as &$t) {
        $t = 0;
    }
    unset($t);
    foreach ($temporary + [5 => 5] as $k => &$t) {
        $t = 9;
    }
    unset($t);
    var_dump($temporary);
    $null = null;
    foreach ($null as &$x) {
    }
    var_dump($null);
    $scalar = 5;
    foreach ($scalar as &$x) {
    }
}
subjects();

function targets() {
    $a = [1, 2];
    $o = new Holder;
    foreach ($a as &$o->target) {
    }
    $o->target = 'via property';
    var_dump($a);
    $b = ['p' => 1, 'q' => 2];
    $refs = [];
    $k = 'start';
    foreach ($b as $k => &$refs[]) {
    }
    $refs[0] = 'first';
    $refs[1] = 'second';
    var_dump($b, $k);
    $c = [1, 2];
    $keys = [];
    foreach ($c as $keys[] => &$v) {
        $v = 'v';
    }
    unset($v);
    var_dump($c, $keys);
}
targets();

// a function yielding by reference gives its references to foreach by reference; others can't
function drive($generator) {
    foreach ($generator as $ignored) {
    }
}

function &yieldRefs(array &$items) {
    foreach ($items as $k => &$item) {
        yield $k => $item;
    }
}

function gen() {
    yield 1;
}

class Items implements Iterator {
    private $i = 0;

    function current(): mixed { return $this->i; }
    function key(): mixed { return $this->i; }
    function next(): void { $this->i++; }
    function rewind(): void { $this->i = 0; }
    function valid(): bool { return $this->i < 2; }
}

class Aggregate implements IteratorAggregate {
    public $items = ['a' => 1, 'b' => 2];

    function getIterator(): Iterator {
        return yieldRefs($this->items);
    }
}

function generators() {
    yield;
    $data = [1, 2, 3];
    foreach (yieldRefs($data) as $k => &$value) {
        $value = "$k: $value";
    }
    unset($value);
    var_dump($data);
    $g = yieldRefs($data);
    foreach ($g as &$value) {
        $value = 'via variable';
        break;
    }
    unset($value);
    var_dump($data[0]);
    $aggregate = new Aggregate;
    foreach ($aggregate as $k => &$value) {
        $value *= 10;
    }
    unset($value);
    var_dump($aggregate->items);
    try {
        foreach (gen() as &$value) {
        }
    } catch (Exception $e) {
        echo get_class($e), ": ", $e->getMessage(), "\n";
    }
    try {
        foreach (new Items as &$value) {
        }
    } catch (Error $e) {
        echo get_class($e), ": ", $e->getMessage(), "\n";
    }
}
drive(generators());
