<?php
// Named arguments' run-time errors and when they are thrown (php-src's zend_handle_named_arg at each SEND,
// zend_handle_undef_args at ZEND_CHECK_UNDEF_ARGS before the call): "Unknown named parameter $x" and "Named
// parameter $x overwrites previous argument" once that argument's value is evaluated, before the next one; a skipped
// required parameter's ArgumentCountError "Argument #n ($x) not passed", and a skipped default that throws, both in
// the callee's frame before any parameter is checked (so before a TypeError of an earlier argument); a value by name
// for a by-reference parameter; a constructor-less class; a variable read by name that the call never takes.

function rel($text) {
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
}

function t($label, $call, $trace = false) {
    try {
        $result = $call();
        echo $label, ': ', json_encode($result), "\n";
    } catch (Throwable $e) {
        echo $label, ': ', get_class($e), ': ', rel($e->getMessage()), ' (line ', $e->getLine(), ")\n";
        if ($trace) {
            echo rel($e->getTraceAsString()), "\n";
        }
    }
}

function trace($label) {
    echo "  eval $label\n";
    return $label;
}

function one($a) {
    return $a;
}

function four($a, $b, $c, $d) {
    return "$a$b$c$d";
}

function typed(int $a, $b, $c = 0) {
    return [$a, $b, $c];
}

function typedDefault(int $a, $b = MISSING_CONST, $c = 0) {
    return [$a, $b, $c];
}

function undefinedDefault($a = 'a', $b = UNDEFINED_ONE, $c = 'c') {
    return [$a, $b, $c];
}

class Noisy {
    public function __construct() {
        echo "  Noisy made\n";
    }
}

function noisyDefault(int $a = 0, $b = new Noisy(), $c = 0) {
    return [$a, $c];
}

function byRef($a, &$e) {
    $e = 'set';
}

class NoCtor {
}

class Ctor {
    public function __construct($x) {
        echo "  Ctor($x)\n";
    }
}

class M {
    public function m($a, $b = 'b') {
        return "$a$b";
    }

    public static function s($a, $b = 'b') {
        return "$a$b";
    }
}

function main() {
    t('unknown', fn() => one(b: 42));
    t('unknown object', fn() => one(b: new stdClass()));
    t('unknown before known', fn() => one(b: 2, a: 1));
    t('unknown, case', fn() => one(A: 1));
    t('unknown after side effects', fn() => four(trace('a'), x: trace('x'), b: trace('b')));
    t('duplicate', fn() => one(a: 1, a: 2));
    t('overwrites positional', fn() => one(1, a: 2));
    t('overwrites, later args not evaluated', fn() => four(trace(1), trace(2), b: trace('b'), c: trace('c')));
    t('unknown with an undefined variable', fn() => one(zz: $undefined));
    t('known with an undefined variable', fn() => one(a: $undefined));
    t('unknown with an undefined element', function () {
        $arr = [];
        return one(zz: $arr['nope']);
    });

    t('not passed', fn() => four(a: 'a', d: 'd'), true);
    t('not passed, second', fn() => four('a', 'b', d: 'd'), true);
    t('not passed before TypeError', fn() => typed(a: 'x', c: 1), true);
    t('TypeError when nothing skipped', fn() => typed(a: 'x', b: 1));
    t('skipped default throws before TypeError', fn() => typedDefault(a: 'x', c: 1), true);
    t('skipped default throws', fn() => undefinedDefault(c: 'C'), true);
    t('trailing default throws', fn() => undefinedDefault('A'), true);
    t('skipped new before TypeError', fn() => noisyDefault(a: 'x', c: 1));

    t('value for a by-reference parameter', fn() => byRef(e: 42));
    t('variable for a by-reference parameter', function () {
        byRef(e: $v, a: 1);
        return $v;
    });

    t('no constructor, unknown', fn() => new NoCtor(x: 'nope'));
    t('stdClass, unknown', fn() => new stdClass(x: 'nope'));
    t('constructor, unknown', fn() => new Ctor(y: trace('y')));
    t('constructor, overwrite', fn() => new Ctor(1, x: 2));

    $m = new M();
    t('method unknown', fn() => $m->m(c: 1));
    t('method not passed', fn() => $m->m(b: 1), true);
    t('static not passed', fn() => M::s(b: 1), true);
    t('closure not passed', fn() => (function ($p, $q) {})(q: 1), true);
}

main();
