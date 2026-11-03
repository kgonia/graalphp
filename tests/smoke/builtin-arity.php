<?php
// Built-ins check their argument count against php-src's arginfo (zend_wrong_parameters_count_error): too few or too
// many arguments are an ArgumentCountError thrown in the built-in's frame ("exactly", "at least", "at most"), after
// the arguments were evaluated, also when called through call_user_func, array_map or a name in a variable, and
// counted after unpacking. Methods of built-in classes are named after the class declaring them, those written in
// PHP (the prelude) too.

function rel($text) {
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
}

function t($label, $call, $trace = false) {
    try {
        $result = $call();
        echo $label, ': ', json_encode($result), "\n";
    } catch (Throwable $e) {
        echo $label, ': ', get_class($e), ': ', $e->getMessage(), ' (line ', $e->getLine(), ")\n";
        if ($trace) {
            echo rel($e->getTraceAsString()), "\n";
        }
    }
}

// the wording and plurals
t('too few, exactly 1', fn() => strlen(), true);
t('too many, exactly 1', fn() => strlen('a', 'b'));
t('exactly 0', fn() => func_get_args(1));
t('at least 3', fn() => str_replace(1));
t('at least 1', fn() => max());
t('at most 2', fn() => count([], 1, 2));
t('at most 3', fn() => array_keys([], 1, true, 4));
t('in range', fn() => [strlen('abc'), str_pad('a', 3, '-'), count([1], COUNT_RECURSIVE)]);
t('variadic', fn() => max(1, 2, 3, 4, 5, 6));
t('optional missing', fn() => [substr('abc', 1), implode([1, 2]), trim(' a ')]);
t('mt_rand with one bound', fn() => mt_rand(1));
t('rand with three', fn() => rand(1, 2, 3));
t('mt_rand without bounds', fn() => is_int(mt_rand()));

// the arguments are evaluated first, in order
t('side effects', fn() => strlen(print("first\n"), print("second\n")));

// the line of the call
t('line', function () {
    return str_repeat(
        'x'
    );
});

// methods of built-in classes
t('Exception', fn() => new Exception('m', 1, null, 4), true);
t('subclass', fn() => new RuntimeException('m', 1, null, 4));
t('Error', fn() => new Error('m', 1, null, 4));
t('getMessage', fn() => (new Exception('m'))->getMessage(1));
t('Generator::send', function () {
    $g = (function () {
        yield 1;
    })();
    return $g->send();
});
t('Closure::fromCallable', fn() => Closure::fromCallable());
t('Closure::bind', fn() => Closure::bind(fn() => 1));
t('prelude too many', fn() => (new ArrayIterator([]))->count(1), true);
t('prelude at most', fn() => new ArrayObject([], 0, 'ArrayIterator', 5));
t('prelude too few', fn() => new ReflectionClass());
t('prelude in range', fn() => (new ArrayObject([1, 2]))->count());
t('prelude Reflection', fn() => (new ReflectionClass('stdClass'))->getName(1));
t('prelude DateTime', fn() => new DateTime('now', null, 1));

// calls through callables
t('call_user_func', fn() => call_user_func('strlen'), true);
t('call_user_func_array', fn() => call_user_func_array('strlen', ['a', 'b']));
t('array_map', fn() => array_map('strlen', ['a'], ['b']), true);
t('array_map in range', fn() => array_map('strlen', ['a', 'bc']));
t('array_walk', function () {
    $a = ['A'];
    array_walk($a, 'strtolower');
    return $a;
});
t('usort', function () {
    $a = ['b', 'a'];
    usort($a, 'strcmp');
    return $a;
});
t('name in a variable', function () {
    $f = 'strlen';
    return $f();
}, true);
t('first-class callable', function () {
    $f = strlen(...);
    echo "made\n";
    return $f('a', 'b');
}, true);
t('closure of a built-in', fn() => Closure::fromCallable('str_repeat')('x'));

// unpacking counts the unpacked arguments
t('unpacked too many', fn() => strlen(...['a', 'b']));
t('unpacked none', fn() => strlen(...[]));
t('unpacked in range', fn() => str_repeat(...['x', 3]));
t('unpacked after one', fn() => str_repeat('x', ...[2, 3]));

// the built-ins compiled in place, called with other counts
t('count none', fn() => count());
t('is_int two', fn() => is_int(1, 2));
t('array_key_exists one', fn() => array_key_exists(1));
t('compact none', fn() => compact());
t('extract none', fn() => extract());
t('extract four', function () {
    $a = [];
    return extract($a, EXTR_SKIP, 'p', 4);
});
t('func_get_arg none', fn() => func_get_arg());

// at the file's top level
try {
    strlen();
} catch (ArgumentCountError $e) {
    echo 'top level: ', $e->getMessage(), ' (line ', $e->getLine(), ")\n", rel($e->getTraceAsString()), "\n";
}
try {
    in_array(1);
} catch (ArgumentCountError $e) {
    echo 'top level: ', $e->getMessage(), "\n";
}
try {
    compact();
} catch (ArgumentCountError $e) {
    echo 'top level: ', $e->getMessage(), "\n";
}
