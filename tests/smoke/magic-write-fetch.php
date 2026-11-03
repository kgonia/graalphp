<?php
// A write through a property's object (FETCH_OBJ_W) reads the property with __get and no __isset, at a file's top
// level (the tree) and in a function (bytecode); a string offset at the int range's ends warns (php-src's
// bug71359, ns_064, string_offset_int_min_max).
ob_start(function ($text) {
    // "/" after the directory: Truffle PHP runs the smoke tests on the host
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
});
class Magic {
    private $data = [];
    function __isset($n) { echo "__isset($n)\n"; return isset($this->data[$n]); }
    function __get($n) { echo "__get($n)\n"; return $this->data[$n] ??= new stdClass; }
    function __set($n, $v) { echo "__set($n)\n"; $this->data[$n] = $v; }
}
$m = new Magic;
$m->a->b = 1;
$m->c->d['e'] = 2;
var_dump($m->a->b, $m->c->d);
function inFunction() {
    $m = new Magic;
    $m->a->b = 1;
    $m->c->d['e'] = 2;
    var_dump($m->a->b);
}
inFunction();
$s = "";
var_dump($s[PHP_INT_MAX], $s[PHP_INT_MIN]);
