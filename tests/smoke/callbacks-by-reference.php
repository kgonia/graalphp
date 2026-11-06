<?php
// zend_call_function's sends from call_user_func(), call_user_func_array() and built-ins calling back: a value for a
// by-reference parameter is the warning "f(): Argument #1 ($x) must be passed by reference, value given" and the call
// goes on with a temporary reference; call_user_func_array() into __call keeps the array's references in $args (no
// separation for a trampoline); Closure::fromCallable() of a method __call stands in for takes no named arguments,
// `$obj->missing(...)` takes them into __call's array.

function rel($text) {
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
}

function t($label, $call) {
    try {
        $result = $call();
        echo $label, ': ', json_encode($result), "\n";
    } catch (Throwable $e) {
        echo $label, ': ', get_class($e), ': ', rel($e->getMessage()), ' (line ', $e->getLine(), ")\n";
    }
}

function bump(&$x) {
    $x++;
    return $x;
}

class Magic {
    public function __call($name, $args) {
        $args[0]++;
        return [$name, $args];
    }
}

function main() {
    t('call_user_func by-reference value', fn() => call_user_func('bump', 1));
    $v = 1;
    t('call_user_func by-reference variable', fn() => [call_user_func('bump', $v), $v]);
    t('call_user_func_array value', fn() => call_user_func_array('bump', [5]));
    t('call_user_func_array reference', function () {
        $w = 1;
        $r = call_user_func_array('bump', [&$w]);
        return [$r, $w];
    });
    t('array_map by-reference callback', fn() => array_map(function (&$x) {
        return $x * 2;
    }, [1, 2]));
    t('usort by-reference callback', function () {
        $a = [3, 1];
        usort($a, function (&$x, &$y) {
            return $x <=> $y;
        });
        return $a;
    });
    t('cufa into __call keeps references', function () {
        $m = new Magic();
        $z = 10;
        $r = call_user_func_array([$m, 'anything'], [&$z]);
        return [$r, $z];
    });
    $m = new Magic();
    $fixed = Closure::fromCallable([$m, 'missing']);
    t('fromCallable of __call, positional', fn() => $fixed(1, 2));
    t('fromCallable of __call, named', fn() => $fixed(1, x: 2));
    $variadic = $m->missing(...);
    t('first-class callable of __call, named', fn() => $variadic(1, x: 2));
    t('fromCallable of __call, cufa named', fn() => call_user_func_array($fixed, ['x' => 2]));
}

main();
