<?php
// Named arguments a variadic parameter collects (php-src's extra_named_params, ZEND_RECV_VARIADIC): with their names
// as string keys after the positional extras; not counted by func_num_args() or func_get_args(); a typed variadic
// checks them; a by-reference variadic takes references; backtraces list them as `name: value`
// (debug_backtrace()'s args with string keys); __call and __callStatic get them in their arguments array; a
// parameter skipped before the variadic takes its default.

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

function collect($a, ...$extra) {
    return [$a, $extra, func_num_args(), func_get_args()];
}

function collectTyped($a, string ...$extra) {
    return [$a, $extra];
}

function collectInt(int ...$numbers) {
    return $numbers;
}

function withDefault($a, $b = 'b', ...$rest) {
    return [$a, $b, $rest, func_num_args()];
}

function refs(&...$refs) {
    foreach ($refs as &$ref) {
        $ref++;
    }
}

function traced($a, ...$rest) {
    $frames = debug_backtrace();
    show('debug_backtrace args', $frames[0]['args']);
    ob_start();
    debug_print_backtrace();
    echo rel(ob_get_clean());
    throw new Exception('in traced');
}

class Magic {
    public function __call($name, $args) {
        return [$name, $args];
    }

    public static function __callStatic($name, $args) {
        return ['static', $name, $args];
    }

    private function hidden($a = 'a', $b = 'b') {
        return "hidden $a $b";
    }

    public function forward($method, ...$args) {
        return $this->{'_' . $method}(...$args);
    }

    public function _target($a = 'a', $b = 'b') {
        return "$a,$b";
    }
}

class Forwarder {
    public function __call($method, $args) {
        return $this->{'_' . $method}(...$args);
    }

    public static function __callStatic($method, $args) {
        return (new static())->{'_' . $method}(...$args);
    }

    private function _method($a = 'a', $b = 'b') {
        return "a: $a, b: $b";
    }
}

function main() {
    show('extras by name', collect(b: 'b', a: 'a', c: 'c', extra: 'extra'));
    show('positional then named extras', collect('a', 'b', 'c', d: 'd'));
    show('typed extras', collectTyped('a', 'x', y: 'y'));
    t('typed extra rejected', fn() => collectTyped('a', y: 5.5 > 1 ? [] : ''));
    t('int extra coerced', fn() => collectInt(1, x: '2'));
    t('int extra rejected', fn() => collectInt(1, x: 'two'));
    show('default before the variadic skipped', withDefault(a: 1, z: 26));
    show('default before the variadic, positional extra', withDefault(1, 2, 3, z: 26));
    $x = 0;
    $y = 10;
    refs(x: $x, y: $y);
    show('by-reference variadic by name', [$x, $y]);
    try {
        traced(1, 2, x: 3, y: 4);
    } catch (Exception $e) {
        show('getTrace args', $e->getTrace()[0]['args']);
        echo rel($e->getTraceAsString()), "\n";
        echo rel((string) $e), "\n";
    }

    $m = new Magic();
    show('__call named', $m->anything(a: 'A', b: 'B'));
    show('__call mixed', $m->anything(1, two: 2));
    show('__callStatic named', Magic::other(z: 'Z'));
    show('__call for a private method', $m->hidden(b: 'B'));
    show('forward through a variadic', $m->forward('target', b: 'B'));
    $f = new Forwarder();
    $obj = new class {
        public function __toString() {
            return 'STR';
        }
    };
    show('__call unpacking named', $f->method(a: 'A', b: 'B'));
    show('__call unpacking named, skip', $f->method(b: 'B'));
    show('__call unpacking named, object', $f->method(b: $obj));
    show('__callStatic unpacking named', Forwarder::method(b: 'B'));
    t('__call unpacking unknown', fn() => $f->method(c: 'C'));
}

main();
