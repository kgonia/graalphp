<?php
// References in functions on bytecode: `=&` between variables, elements
// (nested, appended, of properties) and properties, `=&` of a call's result, functions returning by reference,
// global and static variables, by-reference arguments that are nested elements or elements of properties,
// array literals with references and spreads, and generators yielding elements, properties and calls by
// reference; with the copy-on-write rules (tla-cow.md) and php-src's notices.

set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (line $line)\n";
    return true;
});

class Box {
    public $p = 1;
    public $list = [];
    public $self;

    function &item($key) {
        return $this->list[$key];
    }

    function &listRef() {
        return $this->list;
    }

    function add(&$target, $value) {
        $target[] = $value;
    }
}

function inc(&$value) {
    $value++;
}

function variables() {
    $a = 1;
    $b = &$a;
    $b++;
    var_dump($a, $b);
    $c = &$b;
    $c = 'c';
    var_dump($a, $b, $c);
    unset($b);
    $a = 'after unset';
    var_dump($c);
    $x = [1, 2];
    $y = &$x;
    $y[] = 3;
    var_dump($x);
    $z = $x;
    $z[] = 4;
    var_dump(count($x), count($z));
    // the value of `=&` is the reference's
    var_dump($w = &$a);
    $self = 'self';
    $self = &$self;
    var_dump($self);
}
variables();

function elements() {
    $arr = ['k' => 1];
    $r = &$arr['k'];
    $r = 10;
    $missing = &$arr['missing'];
    var_dump($arr);
    $deep = &$arr['x']['y'];
    $deep = 'deep';
    $appended = &$arr[];
    $appended = 'appended';
    var_dump($arr);
    // a reference in an array stays one in its copies
    $copy = $arr;
    $r = 'through the reference';
    var_dump($copy['k']);
    $copy['k'] = 'copy';
    var_dump($arr['k']);
    $value = 'v';
    $target = [];
    $target['a'] = &$value;
    $target[] = &$value;
    $target['n']['m'] = &$value;
    $target['n'][] = &$value;
    $value = 'changed';
    var_dump($target);
}
elements();

function properties() {
    $o = new Box;
    $p = &$o->p;
    $p = 'via ref';
    var_dump($o->p);
    $e = &$o->list['e'];
    $e = 'element';
    $n = &$o->list['a']['b'];
    $n = 'nested';
    var_dump($o->list);
    $v = 'bound';
    $o->self = &$v;
    $v = 'rebound';
    var_dump($o->self);
    $o->list['ref'] = &$v;
    $o->list[] = &$v;
    $v = 'again';
    var_dump($o->list);
}
properties();

$counter = 0;
$names = ['first'];

function &counterRef() {
    global $counter;
    return $counter;
}

function &valueReturned() {
    return 5;
}

function &nothing() {
}

function &chained() {
    return counterRef();
}

function &element(array &$arr, $key) {
    return $arr[$key];
}

function plain() {
    return 'plain';
}

function returns() {
    $c = &counterRef();
    $c += 5;
    global $counter;
    var_dump($counter);
    $d = &chained();
    $d *= 2;
    var_dump($counter);
    $v = &valueReturned();
    var_dump($v);
    $nothing = &nothing();
    var_dump($nothing);
    $p = &plain();
    var_dump($p);
    $arr = ['a' => 1];
    $e = &element($arr, 'a');
    $e = 'set through';
    $f = &element($arr, 'new');
    $f = 'created';
    var_dump($arr);
    $box = new Box;
    $item = &$box->item('k');
    $item = 'item';
    $list = &$box->listRef();
    $list[] = 'pushed';
    var_dump($box->list);
    echo counterRef(), "\n";
}
returns();

function globals() {
    global $names, $created;
    $names[] = 'second';
    $created = 'made here';
    var_dump($created);
}
globals();
var_dump($names, $created);

function counter() {
    static $calls = 0, $log = [];
    static $start = 10 * 2;
    $calls++;
    $log[] = $calls;
    return [$calls, $start++, $log];
}

class Counts {
    function next() {
        static $n = 0;
        return ++$n;
    }

    static function shared() {
        static $all = [];
        $all[] = count($all);
        return $all;
    }
}

function statics() {
    counter();
    counter();
    var_dump(counter());
    $c = new Counts;
    $c->next();
    var_dump($c->next(), (new Counts)->next());
    Counts::shared();
    var_dump(Counts::shared());
}
statics();

function arguments() {
    $arr = ['x' => ['y' => 1]];
    inc($arr['x']['y']);
    inc($arr['new']['deep']);
    var_dump($arr);
    $o = new Box;
    $o->list = ['k' => 5];
    inc($o->list['k']);
    inc($o->list['a']['b']);
    var_dump($o->list);
    $o->add($o->list['added'], 'one');
    $o->add($arr['x']['list'], 'two');
    var_dump($o->list['added'], $arr['x']['list']);
    $i = 0;
    $grid = [[0, 0], [0, 0]];
    inc($grid[$i++][$i++]);
    var_dump($grid, $i);
    // by value, the element is read
    var_dump(strlen($arr['x']['list'][0]), count($o->list['a']));
    $shared = ['n' => ['m' => 1]];
    $copy = $shared;
    inc($copy['n']['m']);
    var_dump($shared['n']['m'], $copy['n']['m']);
}
arguments();

function literals() {
    $x = 1;
    $y = 2;
    $arr = [&$x, 'k' => &$y, 'v' => $x];
    $x = 'x changed';
    $y = 'y changed';
    var_dump($arr);
    $nested = ['inner' => [1]];
    $refs = [&$nested['inner'][0], &$nested['created']];
    $nested['inner'][0] = 'through';
    $nested['created'] = 'made';
    var_dump($refs);
    $a = [1, 2];
    $b = ['s' => 'S', 3];
    var_dump([0, ...$a, ...$b, ...[], 'last']);
    var_dump(['a' => 1, 2, 'b' => 3, 4, 10 => 5, 6]);
    $o = new Box;
    $props = [&$o->p];
    $o->p = 'prop through';
    var_dump($props);
}
literals();

function &references(array &$data, Box $box) {
    foreach (['a', 'b'] as $key) {
        yield $key => $data[$key];
    }
    yield 'p' => $box->p;
    yield 'item' => $box->item('i');
    yield 'value' => plain();
}

function generators() {
    $data = ['a' => 1, 'b' => 2];
    $box = new Box;
    $g = references($data, $box);
    foreach ($g as $key => $value) {
        if (is_int($value)) {
            $value *= 100;
        }
    }
    $r = references($data, $box);
    $r->current();
    $r->next();
    $r->next();
    $r->next();
    $item = $r->current();
    unset($g, $r);
    var_dump($data, $box->p, $box->list, $item);
}
generators();

// Generators run on bytecode in either interpreter: what the tree does differently from php-src is tested in them
// (the tree takes the reference before it evaluates the target's keys).
function drive($generator) {
    foreach ($generator as $ignored) {
    }
}

function order() {
    yield;
    $i = 0;
    $keys = [];
    $keys[$i++] = &$keys[$i++];
    var_dump($keys, $i);
}
drive(order());
