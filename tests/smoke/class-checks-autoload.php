<?php
// is_subclass_of(), is_a() with allow_string, method_exists(), property_exists() and get_parent_class() load a
// class given by name (WooCommerce's PaymentsProviders checks its providers' classes with is_subclass_of before
// anything loaded them), and the private members a parent declares belong to the parent only
spl_autoload_register(function ($class) {
    echo "autoload $class\n";
    if ($class === 'Lazy') {
        class Lazy extends LazyBase { private function own() {} }
    } elseif ($class === 'LazyBase') {
        class LazyBase { private $hidden; protected $shown; private function secret() {} public function open() {} }
    }
});
var_dump(is_subclass_of('Lazy', 'LazyBase'));
var_dump(is_a('Other1', 'LazyBase'), is_a('Other2', 'LazyBase', true));
var_dump(method_exists('Other3', 'x'), property_exists('Other4', 'x'));
try {
    get_parent_class('Other5');
} catch (TypeError $e) {
    echo $e->getMessage(), "
";
}
var_dump(is_subclass_of('Lazy', 'Lazy'), is_a('Lazy', 'Lazy', true), is_a(new Lazy, 'lazybase'), is_a('Lazy', 'Unknown', true));
var_dump(method_exists('Lazy', 'secret'), method_exists(new Lazy, 'secret'), method_exists('Lazy', 'own'),
    method_exists('LazyBase', 'secret'), method_exists('Lazy', 'OPEN'));
var_dump(property_exists('Lazy', 'hidden'), property_exists('LazyBase', 'hidden'), property_exists('Lazy', 'shown'));
var_dump(get_parent_class('Lazy'), get_parent_class(new Lazy), get_parent_class('LazyBase'));
$f = function () {};
var_dump(method_exists($f, '__invoke'), method_exists('Closure', '__invoke'), is_a($f, 'Closure'));
var_dump(is_subclass_of(new ArrayIterator([]), 'Traversable'), is_subclass_of('ArrayIterator', 'Countable'));
try {
    method_exists(1, 'x');
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
