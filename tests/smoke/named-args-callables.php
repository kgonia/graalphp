<?php
// Named arguments through the callable paths: call_user_func() forwards its own extra named arguments
// (Z_PARAM_VARIADIC_WITH_NAMED), call_user_func_array() takes an array's string keys as names (ZEND_SEND_ARRAY:
// "Cannot use positional argument after named argument", without "during unpacking"), forward_static_call() refuses
// them, forward_static_call_array() takes them; Closure::__invoke(), Closure::call(), Closure::fromCallable();
// Reflection's invoke(), invokeArgs(), newInstance(), newInstanceArgs() (Fiber::start() is named-args-fiber); a
// value for a by-reference parameter is php-src's warning and the call goes on; a skipped default resolved on the
// call's first use (runtime_cache_init).

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

class Test {
    public function __construct($a = 'a', $b = 'b', $c = 'c') {
        if (func_num_args() != 0) {
            echo "  new Test: a=$a b=$b c=$c\n";
        }
    }

    public function method($a = 'a', $b = 'b', $c = 'c') {
        return "a=$a b=$b c=$c";
    }

    public static function lateDefault($a = LATE_FOO, $b = 1) {
        return "a=$a b=$b";
    }

    public static function forward() {
        return [
            forward_static_call_array([static::class, 'target'], ['B', 'z' => 'Z']),
            forward_static_call([static::class, 'target'], 'A', 'B'),
        ];
    }

    public static function forwardNamed() {
        return forward_static_call([static::class, 'target'], 'A', z: 'Z');
    }

    public static function target($x, $y = 'y', $z = 'z') {
        return static::class . ": $x $y $z";
    }
}

function main() {
    $test = function ($a = 'a', $b = 'b', $c = 'c') {
        return "a=$a b=$b c=$c";
    };
    $variadic = function (...$args) {
        return $args;
    };
    $ref = function (&$ref) {
        $ref++;
        return $ref;
    };
    $required = function ($a, $b) {
        return "a=$a b=$b";
    };

    show('cuf positional + named', call_user_func($test, 'A', c: 'C'));
    show('cuf reordered', call_user_func($test, c: 'C', a: 'A'));
    show('cuf skip', call_user_func($test, c: 'C'));
    show('cuf variadic', call_user_func($variadic, 'A', c: 'C'));
    show('cuf by-reference value', call_user_func($ref, ref: 1));
    show('cuf nested', call_user_func('call_user_func', $test, c: 'D'));
    t('cuf not passed', fn() => call_user_func($required, b: 'B'));
    t('cuf builtin not passed', fn() => call_user_func('array_slice', [1, 2, 3, 4, 5], length: 2));
    show('cuf builtin named', call_user_func('array_slice', [1, 2, 3, 4, 'x' => 5], 3, preserve_keys: true));
    define('LATE_FOO', 42);
    show('cuf default resolved late', call_user_func(['Test', 'lateDefault'], b: 0));
    t('cuf unknown', fn() => call_user_func($test, d: 'D'));

    show('cufa list', call_user_func_array($test, ['A', 'B']));
    show('cufa int keys out of order', call_user_func_array($test, [1 => 'A', 0 => 'B']));
    show('cufa named', call_user_func_array($test, ['A', 'c' => 'C']));
    show('cufa variadic', call_user_func_array($variadic, ['A', 'c' => 'C']));
    t('cufa unknown', fn() => call_user_func_array($test, ['d' => 'D']));
    t('cufa positional after named', fn() => call_user_func_array($test, ['c' => 'C', 'A']));
    $v = 1;
    show('cufa reference element', call_user_func_array($ref, ['ref' => &$v]) . " $v");
    show('cufa value for reference', call_user_func_array($ref, ['ref' => 5]));
    t('cufa variadic builtin, empty name', fn() => call_user_func_array('array_multisort', ['' => 1]));

    show('forward_static_call(_array)', Test::forward());
    t('forward_static_call named', fn() => Test::forwardNamed());

    show('__invoke', $test->__invoke('A', c: 'C'));
    show('__invoke variadic', $variadic->__invoke('A', c: 'C'));
    show('Closure::call', $test->call(new class {}, 'A', c: 'C'));
    show('Closure::call variadic', $variadic->call(new class {}, 'A', c: 'C'));
    $from = Closure::fromCallable([new Test(), 'method']);
    show('fromCallable', $from(b: 'B'));

    $rf = new ReflectionFunction($test);
    show('ReflectionFunction::invoke', $rf->invoke('A', c: 'C'));
    show('ReflectionFunction::invokeArgs', $rf->invokeArgs(['A', 'c' => 'C']));
    $rm = new ReflectionMethod(Test::class, 'method');
    show('ReflectionMethod::invoke', $rm->invoke(new Test(), 'A', c: 'C'));
    show('ReflectionMethod::invokeArgs', $rm->invokeArgs(new Test(), ['A', 'c' => 'C']));
    $rc = new ReflectionClass(Test::class);
    $rc->newInstance('A', c: 'C');
    $rc->newInstanceArgs(['A', 'c' => 'C']);
    t('newInstanceArgs unknown', fn() => $rc->newInstanceArgs(['zz' => 1]));
}

main();
