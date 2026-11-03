<?php
// Issue 002: a write through a property that doesn't exist or the scope may not access, on a class with __get,
// goes through what __get returns (zend_std_get_property_ptr_ptr gives NULL, the write fetch calls read_property):
// written through a reference `&__get` returns, else to a temporary with php-src's notice (an object is used as it
// is). An ArrayAccess element written in place goes through offsetGet the same way. At file level (the tree) and
// in a function (bytecode).

set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (line $line)\n";
    return true;
});

class D {
    private $hidden = ['h' => 1];
    public $real = [];
    function &__get($n) {
        echo "__get($n)\n";
        return $this->hidden;
    }
    function hidden() { return $this->hidden; }
}

class V {
    private $hidden = ['h' => 1];
    public $obj;
    function __construct() { $this->obj = new stdClass; }
    function __get($n) {
        echo "__get($n)\n";
        return $n === 'o' ? $this->obj : $this->hidden;
    }
    function hidden() { return $this->hidden; }
}

class Store implements ArrayAccess {
    private $data = ['list' => []];
    function offsetExists($k): bool { return isset($this->data[$k]); }
    function &offsetGet($k): mixed { echo "offsetGet($k)\n"; return $this->data[$k]; }
    function offsetSet($k, $v): void { echo "offsetSet($k)\n"; $this->data[$k] = $v; }
    function offsetUnset($k): void { unset($this->data[$k]); }
    function data() { return $this->data; }
}

class ValueStore implements ArrayAccess {
    private $data = ['list' => []];
    function offsetExists($k): bool { return isset($this->data[$k]); }
    function offsetGet($k): mixed { echo "offsetGet($k)\n"; return $this->data[$k]; }
    function offsetSet($k, $v): void { echo "offsetSet($k)\n"; $this->data[$k] = $v; }
    function offsetUnset($k): void { unset($this->data[$k]); }
    function data() { return $this->data; }
}

function run() {
    $d = new D;
    $d->magic['new'] = 2;
    $d->magic[] = 3;
    $r = &$d->other;
    $r['ref'] = 4;
    var_dump($d->hidden(), property_exists($d, 'magic'));
    $d->real['x'] = 1;
    var_dump($d->real);

    $v = new V;
    $v->magic['new'] = 2;
    $w = &$v->other;
    $w['ref'] = 4;
    $v->o->prop = 'set';
    var_dump($v->hidden(), $v->obj->prop, property_exists($v, 'magic'));

    $s = new Store;
    $s['list'][] = 'a';
    $s['list']['k'] = 'b';
    $e = &$s['list'];
    $e[] = 'c';
    $s['new'] = 1;
    var_dump($s->data());

    $t = new ValueStore;
    $t['list'][] = 'a';
    $f = &$t['list'];
    $f[] = 'b';
    var_dump($t->data());
}
run();

echo "-- file level\n";
$d = new D;
$d->magic['new'] = 2;
$r = &$d->other;
$r['ref'] = 4;
var_dump($d->hidden());
$v = new V;
$v->magic['new'] = 2;
var_dump($v->hidden());
$s = new Store;
$s['list'][] = 'a';
var_dump($s->data());
$t = new ValueStore;
$t['list'][] = 'a';
var_dump($t->data());
