<?php
// Argument unpacking with string keys (php-src's ZEND_SEND_UNPACK, issue 017): a string key is a named argument; a
// positional (int) key after one is an Error "during unpacking"; named arguments after the unpacking; an array's
// elements on by-reference parameters become references (the variable's array separated), a Traversable's go by
// value with php-src's warning; a Traversable's keys: numeric strings are positions, a key neither int nor string is
// an Error; extras with string keys into a variadic; unpacking into methods, constructors, closures and callable
// values.

function rel($text) {
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
}

function show($label, $value) {
    echo $label, ': ', json_encode($value), "\n";
}

function t($label, $call) {
    try {
        show($label, $call());
    } catch (Throwable $e) {
        echo $label, ': ', get_class($e), ': ', rel($e->getMessage()), ' (line ', $e->getLine(), ")\n";
    }
}

function k($a, $b) {
    return "$a $b";
}

function abc($a, $b, $c) {
    return "a=$a b=$b c=$c";
}

function opt($a, $b, $c = 3, $d = 4) {
    return [$a, $b, $c, $d];
}

function h(...$args) {
    return $args;
}

function bump($a = null, &$b = null) {
    $b++;
}

function keyed() {
    yield 'a' => 'A';
    yield 'b' => 'B';
    yield 'c' => 'C';
}

function oddKeys() {
    yield 1.5 => 'x';
}

function numericKeys() {
    yield '0' => 'zero';
    yield '1' => 'one';
    yield 'c' => 'C';
}

class C {
    public function __construct(public $x = 'x', public $y = 'y') {
    }

    public function m($p = 'p', $q = 'q') {
        return "$p$q";
    }
}

function main() {
    show('issue 017, keys reordered', k(...['b' => 2, 'a' => 1]));
    show('issue 017, variadic keeps keys', h(...['a' => 1, 'b' => 2]));
    show('all named', abc(...['a' => 'a', 'b' => 'b', 'c' => 'c']));
    show('named reversed', abc(...['c' => 'c', 'b' => 'b', 'a' => 'a']));
    show('positional then named', abc(...['a', 'b' => 'b', 'c' => 'c']));
    t('positional after named', fn() => abc(...['a', 'b' => 'b', 'c']));
    t('overwrites', fn() => abc(...['a', 'a' => 'a']));
    t('unknown', fn() => abc(...['a', 'b', 'zz' => 1]));
    t('not passed', fn() => abc(...['a' => 1, 'c' => 3]));
    show('two unpacks', abc(...['a'], ...['b', 'c' => 'C']));
    t('positional unpack after a named one', fn() => abc(...['a', 'b' => 'b'], ...['c']));
    show('named after unpacking', h(...[1, 2], a: 3));
    show('named after unpacking with keys', h(...[1, 'a' => 2], b: 3));
    show('skipped after unpacking', opt(...[1, 2], d: 40));
    show('keys then named', opt(...['b' => 2, 'a' => 1], d: 40));
    t('named overwrites unpacked', fn() => opt(...[1, 2], b: 20));
    t('named overwrites unpacked key', fn() => opt(...[1, 'b' => 2], b: 20));
    t('named after unpacking, duplicate extra', fn() => h(...['a' => 1], a: 2));
    show('int keys in any order are positions', k(...[1 => 'one', 0 => 'zero']));

    $ary = ['b' => 0];
    $ary2 = $ary;
    bump(...$ary);
    show('by reference from a variable', [$ary, $ary2]);
    bump(...['b' => 5]);
    show('by reference from a temporary', 'ok');

    show('Traversable named', abc(...new ArrayIterator(['a' => 'a', 'b' => 'b', 'c' => 'c'])));
    show('Traversable reversed', abc(...new ArrayIterator(['c' => 'c', 'b' => 'b', 'a' => 'a'])));
    show('Traversable mixed', abc(...new ArrayIterator(['a', 'b' => 'b', 'c' => 'c'])));
    t('Traversable positional after named', fn() => abc(...new ArrayIterator(['a', 'b' => 'b', 'c'])));
    t('Traversable overwrites', fn() => abc(...new ArrayIterator(['a', 'a' => 'a'])));
    t('Traversable unknown', fn() => abc(...new ArrayIterator(['unknown' => 42])));
    show('generator keys', abc(...keyed()));
    t('generator float key', fn() => abc(...oddKeys()));
    show('generator numeric-string keys', h(...numericKeys()));
    $ary = ['b' => 0];
    bump(...new ArrayIterator($ary));
    show('Traversable by reference, by value', $ary);

    $c = new C(...['y' => 'Y']);
    show('constructor', [$c->x, $c->y]);
    show('method', $c->m(...['q' => 'Q']));
    $closure = fn($u, $v = 'v') => "$u$v";
    show('closure', $closure(...['v' => 'V', 'u' => 'U']));
    $callable = [$c, 'm'];
    show('callable value', $callable(...['q' => 'Q2']));
    $static = 'k';
    show('function by name in a variable', $static(...['b' => 'B', 'a' => 'A']));
}

main();
