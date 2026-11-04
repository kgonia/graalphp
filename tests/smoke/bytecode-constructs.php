<?php
// Constructs the bytecode translator used to leave to the tree, which didn't cover them either: each as php-src
// runs it, in a function and at the top level

set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (line $line)\n";
    return true;
});

function gotoInto($n) {
    if ($n > 1) {
        goto inTry;
    }
    if ($n > 0) {
        goto inCatch;
    }
    echo "start\n";
    try {
        inTry:
        echo "in try\n";
        if ($n === 0) {
            throw new Exception('e');
        }
    } catch (Exception $e) {
        inCatch:
        echo "in catch\n";
    } finally {
        echo "finally\n";
    }
}
gotoInto(0);
gotoInto(1);
gotoInto(2);

function lists() {
    $a = [1, [2, 3]];
    [&$x, [, &$y]] = $a;
    $x = 10;
    $y = 30;
    var_dump($a);
    $rows = [[1, 2], [3, 4]];
    foreach ($rows as [&$first, $second]) {
        $first *= $second;
    }
    unset($first);
    var_dump($rows);
}
lists();

function appends() {
    $a = [];
    $a[] .= 'x';
    $a[] += 5;
    $a[]++;
    $b = [];
    $b[][0] = 1;
    $b[]['k'] .= 'y';
    var_dump($a, $b);
}
appends();

class Base {
}
class Derived extends Base {
    function test($x) {
        $closure = function ($y) {
            return [$y instanceof self, $y instanceof static, $y instanceof parent];
        };
        return [$x instanceof static, $x instanceof parent, $closure($x)];
    }
}
var_dump((new Derived)->test(new Derived), (new Derived)->test(new Base));

function outside() {
    try {
        get_class();
    } catch (Error $e) {
        echo $e->getMessage(), "\n";
    }
    try {
        get_called_class();
    } catch (Error $e) {
        echo $e->getMessage(), "\n";
    }
}
outside();
$bound = Closure::bind(function () { return [get_class(), get_called_class()]; }, null, Derived::class);
var_dump($bound());

function defined_vars($p) {
    $a = 1;
    $b = &$a;
    $c = [2];
    unset($p);
    return get_defined_vars();
}
$vars = defined_vars(0);
var_dump($vars);
$topLevel = 'here';
var_dump(array_key_exists('topLevel', get_defined_vars()));

class Props {
    public $count = 1;
    public $text = 'a';
    public $list = [1, 2];
}
function computed() {
    $o = new Props;
    $n = 'count';
    $t = 'te' . 'xt';
    $o->$n++;
    ++$o->$n;
    $o->$t .= 'b';
    $o->{'co' . 'unt'} *= 10;
    $ref = &$o->$t;
    $ref = 'via reference';
    $value = 'bound';
    $o->$n = &$value;
    $value = 'rebound';
    $l = 'list';
    foreach ($o->$l as &$item) {
        $item++;
    }
    unset($item);
    var_dump(get_object_vars($o));
}
computed();

class Store implements ArrayAccess {
    private $data = [];
    function offsetExists($o): bool { echo "exists $o\n"; return isset($this->data[$o]); }
    function offsetGet($o): mixed { echo "get $o\n"; return $this->data[$o] ?? null; }
    function offsetSet($o, $v): void { echo "set $o\n"; $this->data[$o] = $v; }
    function offsetUnset($o): void { echo "unset $o\n"; unset($this->data[$o]); }
    function fill() {
        $this['a'] = 1;
        unset($this['a']);
        return $this;
    }
}
function store() {
    static $s;
    return $s ??= new Store;
}
function &reference() {
    static $array = [];
    return $array;
}
function temporaries() {
    (new Store)->fill();
    store()['k'] = 'v';
    reference()['x'] = 1;
    var_dump(reference());
    function_returning_array()[0] = 5;
}
function function_returning_array() {
    return [1];
}
temporaries();

function voidCast() {
    (void) print("printed\n");
    $x = 1;
    (void) $x;
}
voidCast();

class Self_ {
    function run() {
        try {
            $this++;
        } catch (Error $e) {
            echo $e->getMessage(), "\n";
        }
        $copy = &$this;
        var_dump($copy === $this);
    }
}
(new Self_)->run();

function &byRef() {
    static $v = 'static';
    return $v;
}
function byValue() {
    return 'value';
}
function refFromCalls() {
    $a = [];
    $a['r'] = &byRef();
    $a['r'] .= '!';
    $a['v'] = &byValue();
    var_dump($a, byRef());
}
refFromCalls();

static $topStatic = 1;
$topStatic++;
var_dump($topStatic);
$fromCall = &byRef();
var_dump($fromCall);
