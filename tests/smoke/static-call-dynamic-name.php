<?php
// `A::$m()`, `self::$m()`, `static::$m()`, `parent::$m()`, `$cls::$m()` and their first-class callables: the
// method found by name as `A::m()` finds it (zend_std_get_static_method from the calling scope), by-reference
// parameters, __callStatic and __call standing in, php-src's Errors. In functions (bytecode) and at file level.

function t($what, $f) {
    try {
        echo "$what: ", var_export($f(), true), "\n";
    } catch (Error $e) {
        echo "$what: ", get_class($e), ': ', $e->getMessage(), "\n";
    }
}

class A {
    public $v = 'v';
    static function s($x) { return "A::s($x) " . static::class; }
    function i() { return 'A::i ' . $this->v; }
    static function inc(&$n) { $n++; }
    private static function hidden() { return 'A::hidden'; }
    static function viaSelf($m) { return self::$m('self'); }
    static function viaStatic($m) { return static::$m('static'); }
    function viaThis($m) { return self::$m(); }
    static function callHidden() { $m = 'hidden'; return static::$m(); }
    static function convert($m) { return (self::$m(...))('conv'); }
}
class B extends A {
    static function s($x) { return "B::s($x) " . static::class; }
    static function viaParent($m) { return parent::$m('parent'); }
}
class M {
    static function __callStatic($n, $a) { return "__callStatic $n " . implode(',', $a); }
    function __call($n, $a) { return "__call $n"; }
    function inside() { $m = 'missing'; return self::$m(1); }
}

t('name', function () { $m = 's'; return A::$m(1); });
t('case', function () { $m = 'S'; return A::$m(2); });
t('self', fn() => B::viaSelf('s'));
t('static', fn() => B::viaStatic('s'));
t('parent', fn() => B::viaParent('s'));
t('class in a variable', function () { $c = 'B'; $m = 's'; return $c::$m(3); });
t('object as class', function () { $o = new B; $m = 's'; return $o::$m(4); });
t('non-static with $this', fn() => (new A)->viaThis('i'));
t('non-static without $this', function () { $m = 'i'; return A::$m(); });
t('by reference', function () { $n = 1; $m = 'inc'; A::$m($n); return $n; });
t('private outside', function () { $m = 'hidden'; return A::$m(); });
t('private inside', fn() => A::callHidden());
t('undefined', function () { $m = 'nope'; return A::$m(); });
t('__callStatic', function () { $m = 'missing'; return M::$m(1, 2); });
t('__call with $this', fn() => (new M)->inside());
t('name not a string', function () { $m = 5; return A::$m(); });
t('name an array', function () { $m = ['A', 's']; return A::$m(); });
t('first-class', fn() => A::convert('s'));
t('first-class outside', function () { $m = 's'; return (B::$m(...))('fc'); });
t('array element name', function () { $names = ['s']; return A::{$names[0]}(5); });

// at file level
$m = 's';
echo A::$m('file'), "\n";
echo B::$m('file'), "\n";
$n = 1;
$inc = 'inc';
A::$inc($n);
echo $n, "\n";
$missing = 'missing';
echo M::$missing('x'), "\n";
echo (A::$m(...))('file conv'), "\n";
try {
    $hidden = 'hidden';
    A::$hidden();
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
