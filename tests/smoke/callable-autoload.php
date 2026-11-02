<?php
// A class named by a callable is autoloaded: by call_user_func(), is_callable(), array_map() and a direct call of
// an array or "Class::method" string (Jetpack's Script_Data::... hooked on plugins_loaded in WooCommerce)
namespace Ns;

spl_autoload_register(function ($class) {
    echo "autoload $class\n";
    if ($class === 'Ns\Loaded') {
        class Loaded { static function hi($x = 1) { return "hi $x"; } }
    } elseif ($class === 'Ns\Later') {
        class Later { static function hi() { return 'later'; } }
    }
});
var_dump(is_callable(['Ns\Missing', 'hi']));
var_dump(is_callable(['\Ns\Loaded', 'hi']));
var_dump(call_user_func(['Ns\Loaded', 'hi'], 2));
var_dump(array_map('Ns\Loaded::hi', [3]));
$direct = ['Ns\Later', 'hi'];
var_dump($direct());
try {
    $missing = 'Ns\Gone::hi';
    $missing();
} catch (\Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    call_user_func('Other\Klass::hi');
} catch (\TypeError $e) {
    echo $e->getMessage(), "\n";
}
