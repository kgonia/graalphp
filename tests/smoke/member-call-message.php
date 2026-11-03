<?php
// Issue 009: "Call to a member function M() on T" names the value as zend_zval_value_name does (null, int, float,
// string, bool's true and false, array) and the method as written; "Call to undefined method" too. At file level
// (the tree) and in a function (bytecode).

class A {
    static function make() { return new A; }
}

function t($what, $f) {
    try {
        $f();
        echo "$what: ok\n";
    } catch (Error $e) {
        echo "$what: ", get_class($e), ': ', $e->getMessage(), "\n";
    }
}

$values = [null, 1, 1.5, 'str', true, false, [1]];
foreach ($values as $v) {
    t('method', function () use ($v) { $v->Method(); });
    t('dynamic', function () use ($v) { $n = 'DynMethod'; $v->$n(); });
    t('convert', function () use ($v) { $v->ConvertMe(...); });
    t('nullsafe', function () use ($v) { $v?->NullSafe(); });
}
t('undefined', function () { (new A)->FooBar(); });
t('undefined dynamic', function () { $n = 'FooBar'; (new A)->$n(); });
t('undefined static', function () { A::FooBar(); });
t('undefined callable', function () { [new A, 'FooBar'](); });
t('undefined string', function () { 'A::FooBar'(); });
t('undefined convert', function () { (new A)->FooBar(...); });
t('undefined static convert', function () { A::FooBar(...); });
t('name not a string', function () { $n = 5; (new A)->$n(); });
t('name not a string convert', function () { $n = [1]; (new A)->$n(...); });
t('call_user_func', function () { call_user_func([new A, 'FooBar']); });

// at file level
foreach ($values as $v) {
    try {
        $v->Method();
    } catch (Error $e) {
        echo $e->getMessage(), "\n";
    }
    try {
        $n = 'DynMethod';
        $v->$n();
    } catch (Error $e) {
        echo $e->getMessage(), "\n";
    }
}
try {
    (new A)->FooBar();
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    A::FooBar();
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    $n = 'FooBar';
    (new A)->$n();
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
