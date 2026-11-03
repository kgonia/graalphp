<?php
// func_get_arg() in functions on bytecode (FrameArgNode): a declared parameter's variable as it holds it now (null
// once unset), an extra argument as passed, the variadic parameter's arguments as extra ones; php-src's ValueError
// outside the arguments passed; func_get_arg(), func_get_args() and func_num_args() called with the wrong number of
// arguments are calls of the built-ins, whose ArgumentCountError they raise.

function show($label, $value) {
    echo $label, ': ', json_encode($value), "\n";
}

function report($label, $e) {
    echo $label, ': ', get_class($e), ': ', $e->getMessage(), "\n";
}

function declared($a, $b = 'default') {
    show('first', func_get_arg(0));
    $a = 'changed';
    show('first changed', func_get_arg(0));
    unset($a);
    show('first unset', func_get_arg(0));
    show('count', func_num_args());
    for ($i = 1; $i < func_num_args(); $i++) {
        show("arg $i", func_get_arg($i));
    }
    if (func_num_args() > 1) {
        show('by a string', func_get_arg('1'));
    }
    try {
        func_get_arg(func_num_args());
    } catch (ValueError $e) {
        report('past the end', $e);
    }
    try {
        func_get_arg(-1);
    } catch (ValueError $e) {
        report('negative', $e);
    }
}

function variadic($a, ...$rest) {
    $rest[0] = 'changed';
    show('variadic 1', func_get_arg(1));
    show('variadic 2', func_get_arg(2));
}

function byRef(&$x) {
    $x = 'through the reference';
    show('by reference', func_get_arg(0));
}

function counts() {
    try {
        func_get_args(1);
    } catch (ArgumentCountError $e) {
        report('func_get_args(1)', $e);
    }
    try {
        func_num_args(1, 2);
    } catch (ArgumentCountError $e) {
        report('func_num_args(1, 2)', $e);
    }
    try {
        func_get_arg();
    } catch (ArgumentCountError $e) {
        report('func_get_arg()', $e);
    }
    try {
        func_get_arg(0, 1);
    } catch (ArgumentCountError $e) {
        report('func_get_arg(0, 1)', $e);
    }
}

class Holder {
    function method($x) {
        return func_get_arg(0) . ' ' . func_get_arg(1);
    }

    static function make() {
        return func_get_arg(0);
    }
}

declared('one');
declared('one', 'two', 'three');
variadic('a', 'b', 'c');
$v = 'original';
byRef($v);
counts();
show('method', (new Holder)->method('x', 'extra'));
show('static', Holder::make('made'));
$closure = function ($p) {
    return [func_get_arg(0), func_num_args() > 1 ? func_get_arg(1) : null];
};
show('closure', $closure('p', 'q'));
show('arrow', (fn() => func_get_arg(0))('arrow arg'));
