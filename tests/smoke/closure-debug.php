<?php
// What var_dump() and print_r() show of a Closure (zend_closure_get_debug_info), its object id, and static:: in a
// closure (its called scope)

function user(int $a, &$b, $c = 1, ...$rest) {}

class A {
    public static function name() { return 'A'; }
    public function method($x) {}
    public function make() {
        $local = 1;
        return [
            function ($p) use ($local) { return static::name(); },
            static function () { return static::name(); },
        ];
    }
}
class B extends A {
    public static function name() { return 'B'; }
}

$y = 2;
$plain = function ($x, $z = 3) use (&$y) {
    static $count = 0, $list = [1];
    $count++;
    return $count;
};
var_dump($plain);
$plain(1);
var_dump($plain);
print_r($plain);
echo "\n";
var_dump(user(...));
var_dump((new B)->method(...));
[$bound, $static] = (new B)->make();
var_dump($bound, $bound(1), $static());
$rebound = Closure::bind(fn() => 1, new B);
var_dump($rebound);
$unbound = function () { return isset($this) ? get_class($this) : 'none'; };
var_dump($unbound->bindTo(new A)());
var_dump(spl_object_id($plain) === spl_object_id($plain), new stdClass);
$copy = clone $plain;
var_dump($copy(1));
try {
    echo $plain;
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
