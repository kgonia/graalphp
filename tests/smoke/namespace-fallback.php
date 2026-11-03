<?php
// Issue 014: an unqualified call in a namespace finds the namespace's function, else the global one, at run time
// (INIT_NS_FCALL_BY_NAME), also for the built-ins the runtime compiles in place; the site keeps the function it
// found first for the request, though the namespace declares one later.
namespace X {
    function strlen($s) { return 'mine'; }
    function compact(...$names) { return 'my compact'; }
    function func_get_args() { return 'my func_get_args'; }
    var_dump(strlen('a'), \strlen('a'));
    $a = 1;
    var_dump(compact('a'), \compact('a'));
    function f() { return [func_get_args(), \func_get_args(), compact('x')]; }
    var_dump(f(1, 2));
    $f = strlen(...);
    var_dump($f('abc'));
}

namespace L {
    function probe() { return str_repeat('ab', 2); }
    function counted() { return count([1, 2]); }
    function args() { return func_num_args(); }
    function converted() { return trim(...); }
    var_dump(probe(), counted(), args(1, 2, 3), converted()(' t '));
    if (true) {
        function str_repeat($s, $n) { return 'declared late'; }
        function count($a) { return 'declared late'; }
        function func_num_args() { return 'declared late'; }
        function trim($s) { return 'declared late'; }
    }
    var_dump(probe(), counted(), args(1, 2, 3), converted()(' t '));
    // a site that runs first now finds the namespace's
    var_dump(str_repeat('x', 3), count([]), func_num_args(), trim(' t '));
}

namespace T {
    // top-level code (the tree's): the in-place built-ins behind the same test. The arguments aren't constants:
    // opcache's optimizer folds a call of a pure built-in with constant arguments when this file declares no such
    // function unconditionally (SCCP), whatever the namespace declares later.
    $s = str_repeat('a', rand(3, 3));
    var_dump(strlen($s), is_int($s), intval($s . '7'));
    if (true) {
        function strlen($s) { return 'T strlen'; }
        function is_int($v) { return 'T is_int'; }
    }
    var_dump(strlen($s), is_int($s));
    for ($i = 0; $i < 2; $i++) {
        var_dump(strlen($s), is_int($i));
    }
}
