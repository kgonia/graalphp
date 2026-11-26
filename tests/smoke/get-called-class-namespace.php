<?php
// get_called_class() unqualified in a namespace (Elementor's Core\Base\Module::instance()): php-src resolves the
// name at run time, falling back to the global built-in, which must exist as a function. Called dynamically
// (call_user_func, a variable), it answers for the calling code's called class (zend_get_called_scope).
namespace App\Base {
    class Module {
        public static function name() { return get_called_class(); }
        public function instanceName() { return get_called_class(); }
        public static function viaClosure() { return (fn() => get_called_class())(); }
        public static function dynamic() { return call_user_func('get_called_class'); }
        public static function variable() { $f = 'get_called_class'; return $f(); }
    }
    class Child extends Module {}
    function outside() { return get_called_class(); }
}

namespace {
    use App\Base\Child;
    use App\Base\Module;

    var_dump(Module::name(), Child::name(), (new Child)->instanceName(), Child::viaClosure());
    var_dump(Child::dynamic(), Child::variable());
    var_dump(function_exists('get_called_class'));
    try {
        var_dump(App\Base\outside());
    } catch (Error $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
    try {
        var_dump(call_user_func('get_called_class'));
    } catch (Error $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
