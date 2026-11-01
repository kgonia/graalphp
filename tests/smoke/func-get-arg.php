<?php
// func_get_arg(): a declared parameter's current value, an extra argument as passed (WooCommerce's
// WC_Settings_Page::get_settings and the Action Scheduler's ActionFactory)
function f($a, $b = 'default', ...$rest)
{
    $a = 'changed';
    var_dump(func_num_args(), func_get_arg(0), func_num_args() > 1 ? func_get_arg(1) : 'one');
    if (func_num_args() > 2) {
        var_dump(func_get_arg(2), func_get_arg(func_num_args() - 1));
    }
    try {
        func_get_arg(func_num_args());
    } catch (ValueError $e) {
        echo $e->getMessage(), "\n";
    }
    try {
        func_get_arg(-1);
    } catch (ValueError $e) {
        echo $e->getMessage(), "\n";
    }
}
f(1);
f(1, 2, 3, 4);
function g() { unset($x); return func_get_arg(0); }
var_dump(g('extra'));
class K { function m($x) { return [func_get_arg(0), (fn($y) => func_get_arg(0))('inner')]; } }
var_dump((new K)->m('outer'));
var_dump(function_exists('func_get_arg'), function_exists('func_get_args'));
function dynamic($x)
{
    foreach ([['func_get_arg', [0]], ['func_get_args', []], ['func_num_args', []], ['func_get_args', [1]]] as $call) {
        try {
            call_user_func_array($call[0], $call[1]);
        } catch (Error $e) {
            echo $e->getMessage(), "\n";
        }
    }
}
dynamic(1);
try {
    func_get_arg(0);
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
