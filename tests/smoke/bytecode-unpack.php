<?php
// Argument unpacking `f(...$args)` in functions on bytecode (ArgumentsNode's spread): in calls by name, of methods,
// of static methods, of constructors, of callable values and of `$obj->$name()`; after positional arguments and
// more than once; elements landing on by-reference parameters become references in a variable's array (separated
// first), a temporary array's are passed as they are; variadic parameters, __call and __callStatic, built-ins
// (array_push's first argument by reference), call_user_func(), and php-src's Error for a value that isn't an array.

function show($label, $value) {
    echo $label, ': ', json_encode($value), "\n";
}

function report($label, $e) {
    echo $label, ': ', get_class($e), ': ', preg_replace('/ in .* on line/', ' in FILE on line', $e->getMessage()),
        "\n";
}

function three($a, $b = 'b default', $c = 'c default') {
    return "$a|$b|$c";
}

function rest($first, ...$rest) {
    return [$first, $rest];
}

function bump(&$x, &$y) {
    $x++;
    $y .= '!';
}

function &keep(array &$list, ...$more) {
    foreach ($more as $item) {
        $list[] = $item;
    }
    return $list;
}

class Target {
    public $log = [];
    public $args = ['p', 'q'];

    function __construct(...$given) {
        $this->log[] = 'constructed with ' . implode(',', $given);
    }

    function method($a, $b = 'none') {
        return "method $a $b";
    }

    static function stat($a, ...$rest) {
        return 'static ' . $a . ' ' . count($rest);
    }

    function own() {
        return $this->method(...$this->args);
    }

    function __call($name, $arguments) {
        return "__call $name " . json_encode($arguments);
    }

    static function __callStatic($name, $arguments) {
        return "__callStatic $name " . json_encode($arguments);
    }
}

function values() {
    return ['from', 'a call'];
}

function byName() {
    $args = ['x', 'y'];
    show('all', three(...$args));
    show('after one', three('first', ...$args));
    show('twice', three(...['one'], ...['two', 'three']));
    show('empty', three('only', ...[]));
    show('of a call', three(...values()));
    show('literal', three(...['l1', 'l2', 'l3']));
    show('variadic', rest(...$args));
    show('variadic after', rest('first', ...$args, ...[1, 2]));
    show('count', count([...$args]));
    show('max', max(...[3, 9, 4]));
    show('sprintf', sprintf('%s-%s', ...$args));
    show('nested', three(...array_map('strtoupper', $args)));
}

function references() {
    $pair = [1, 'text'];
    $copy = $pair;
    bump(...$pair);
    show('bumped', $pair);
    show('copy untouched', $copy);
    var_dump($pair);
    bump(...[10, 'temporary']);
    $list = [1];
    keep($list, ...[2, 3]);
    show('kept', $list);
    $stack = ['a'];
    array_push($stack, ...['b', 'c']);
    show('array_push', $stack);
    $more = ['d', 'e'];
    array_push($stack, ...$more);
    show('array_push var', $stack);
    $values = [5];
    $refArgs = [&$values, 6];
    keep(...$refArgs);
    show('reference element', $values);
}

function objects() {
    $args = ['one', 'two'];
    $t = new Target(...$args);
    show('constructed', $t->log);
    show('method', $t->method(...$args));
    show('method after', $t->method('first', ...['second']));
    show('own property', $t->own());
    show('static', Target::stat(...$args));
    show('static after', Target::stat('a', ...$args));
    show('__call', $t->missing(...$args));
    show('__callStatic', Target::missing(...$args));
    $name = 'method';
    show('dynamic method', $t->$name(...$args));
    $f = function (...$all) {
        return 'closure ' . implode('+', $all);
    };
    show('closure', $f(...$args));
    show('string callable', 'three'(...$args));
    show('method string', 'Target::stat'(...$args));
    show('array callable', [$t, 'method'](...$args));
    show('call_user_func', call_user_func('three', ...$args));
    show('call_user_func after', call_user_func('three', 'a', ...$args));
    show('new in a list', get_class(new Target(...[])));
}

function errors() {
    try {
        three(...null);
    } catch (Error $e) {
        report('null', $e);
    }
    try {
        three(...'string');
    } catch (Error $e) {
        report('string', $e);
    }
    try {
        rest(...[]);
    } catch (ArgumentCountError $e) {
        report('too few', $e);
    }
}

function generator(...$args) {
    yield three(...$args);
    yield rest(...$args);
}

byName();
references();
objects();
errors();
foreach (generator('g1', 'g2') as $value) {
    show('generator', $value);
}
