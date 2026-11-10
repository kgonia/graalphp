<?php
// A callable naming a class resolves against the code that passes it to a built-in (zend_is_callable_check_class,
// zend_is_callable_check_func): the class called is that code's $this's when it is an instance of the class
// through the code's scope, and a private or protected method is callable from its scope only. The same callable
// value from different callers, in loops (call sites and built-ins keep what they resolved).
// "self" and "static" in a callable are deprecated: callable-deprecated.php has what PHP says about them
// (issues/096); here their values.
error_reporting(E_ALL & ~E_DEPRECATED);

class Base {
    public static function who() { return static::class; }
    protected static function prot() { return static::class . '/prot'; }
    private static function priv() { return static::class . '/priv'; }
    public function inst() { return static::class . '/inst'; }
    public static function cmp($a, $b) { return $a <=> $b; }
    private static function privCmp($a, $b) { return $b <=> $a; }

    public function calls() {
        $r = [];
        for ($i = 0; $i < 3; $i++) {
            $r[] = call_user_func(['Base', 'who']);
            $r[] = call_user_func('Base::who');
            $r[] = call_user_func([self::class, 'prot']);
            $r[] = call_user_func(['Base', 'priv']);
            $r[] = call_user_func_array(['Base', 'who'], []);
            $r[] = call_user_func(['self', 'who']);
            $r[] = call_user_func(['static', 'who']);
            $r[] = call_user_func(['Base', 'inst']);
            $r[] = call_user_func('Base::inst');
        }
        return implode(' ', $r);
    }

    public function builtins() {
        $r = [];
        for ($i = 0; $i < 3; $i++) {
            $r[] = implode(',', array_map(['Base', 'who'], [1]));
            $r[] = implode(',', array_map(['Base', 'priv'], [1]));
            $r[] = implode(',', array_map('Base::prot', [1]));
            $r[] = implode(',', array_map(['Base', 'inst'], [1]));
            $r[] = var_export(is_callable(['Base', 'priv']), true);
            $r[] = var_export(is_callable('Base::prot'), true);
            $r[] = var_export(is_callable(['Base', 'inst']), true);
            $list = [3, 1, 2];
            usort($list, ['Base', 'privCmp']);
            $r[] = implode('', $list);
            usort($list, [self::class, 'cmp']);
            $r[] = implode('', $list);
            $r[] = preg_replace_callback('/x/', ['Base', 'privRepl'], 'axb');
            $r[] = implode(',', array_keys(array_filter([1, 0, 2], ['Base', 'privTrue'])));
            $r[] = array_reduce([1, 2], ['Base', 'privSum'], 0);
            $f = 'call_user_func';
            $r[] = $f(['Base', 'priv']);
            $g = 'is_callable';
            $r[] = var_export($g(['Base', 'priv']), true);
        }
        return implode(' ', $r);
    }

    private static function privRepl($m) { return '[' . static::class . ']'; }
    private static function privTrue($v) { return (bool) $v; }
    private static function privSum($carry, $v) { return $carry + $v; }

    public static function fromStatic() {
        return call_user_func(['Base', 'who']) . ' ' . call_user_func([self::class, 'prot']) . ' '
            . var_export(is_callable(['Base', 'priv']), true) . ' ' . implode(',', array_map(['Base', 'priv'], [1]));
    }

    public function fromClosure() {
        $f = function () {
            return call_user_func(['Base', 'who']) . ' ' . var_export(is_callable(['Base', 'priv']), true)
                . ' ' . implode(',', array_map(['Base', 'prot'], [1]));
        };
        $g = static fn() => call_user_func(['Base', 'who']) . ' ' . var_export(is_callable(['Base', 'priv']), true);
        return $f() . ' | ' . $g() . ' | ' . implode(',', array_map($f, [1]));
    }

    public function fromGenerator() {
        yield call_user_func(['Base', 'who']);
        yield var_export(is_callable(['Base', 'priv']), true);
        yield implode(',', array_map(['Base', 'priv'], [1]));
        yield call_user_func([self::class, 'prot']);
    }
}

class Child extends Base {
    public function outer() {
        return call_user_func(['Base', 'who']) . ' ' . var_export(is_callable(['Base', 'priv']), true) . ' '
            . var_export(is_callable(['Base', 'prot']), true) . ' ' . implode(',', array_map(['Base', 'prot'], [1]));
    }
}

class Other {
    public function calls() {
        return call_user_func(['Base', 'who']) . ' ' . var_export(is_callable(['Base', 'priv']), true) . ' '
            . var_export(is_callable(['Base', 'prot']), true) . ' ' . var_export(is_callable(['Base', 'inst']), true);
    }
}

function plain() {
    return call_user_func(['Base', 'who']) . ' ' . var_export(is_callable(['Base', 'prot']), true) . ' '
        . implode(',', array_map(['Base', 'who'], [1]));
}

class Via extends Base {
    // a callback's own callables resolve against the callback, not against the code that called the built-in
    public function run() {
        return implode(',', array_map('plain', [1])) . ' | ' . call_user_func('plain');
    }
}

function attempt(callable $f) {
    try {
        return $f();
    } catch (Throwable $e) {
        return get_class($e) . ': ' . $e->getMessage();
    }
}

// the same callable values from callers that differ: instance of the class, of a subclass, static, none
$callers = [new Child, new Base, new Child, new Base];
foreach ($callers as $caller) {
    echo get_class($caller), ' calls: ', $caller->calls(), "\n";
    echo get_class($caller), ' builtins: ', $caller->builtins(), "\n";
    echo get_class($caller), ' closure: ', $caller->fromClosure(), "\n";
    echo get_class($caller), ' generator: ', implode(' ', iterator_to_array($caller->fromGenerator(), false)), "\n";
}
for ($i = 0; $i < 3; $i++) {
    echo 'static: ', Child::fromStatic(), "\n";
    echo 'outer: ', (new Child)->outer(), "\n";
    echo 'other: ', (new Other)->calls(), "\n";
    echo 'plain: ', plain(), "\n";
    echo 'via: ', (new Via)->run(), "\n";
    echo 'top: ', call_user_func(['Base', 'who']), ' ', var_export(is_callable(['Base', 'prot']), true), ' ',
        var_export(is_callable(['Base', 'priv']), true), "\n";
}

// what is not callable from here is an error of the built-in asked
echo attempt(fn() => call_user_func(['Base', 'priv'])), "\n";
echo attempt(fn() => array_map(['Base', 'prot'], [1])), "\n";
echo attempt(function () { $list = [2, 1]; return usort($list, ['Base', 'privCmp']); }), "\n";
echo attempt(fn() => call_user_func(['Base', 'inst'])), "\n";
echo attempt(fn() => (new Other)->calls()), "\n";

// a closure bound to a class's scope, with and without an object
$scoped = Closure::bind(fn() => call_user_func(['Base', 'priv']) . ' ' . var_export(is_callable(['Base', 'priv']), true)
    . ' ' . implode(',', array_map(['Base', 'priv'], [1])), null, Base::class);
$bound = Closure::bind(function () {
    return call_user_func(['Base', 'who']) . ' ' . implode(',', array_map(['Base', 'priv'], [1]));
}, new Child, Base::class);
for ($i = 0; $i < 3; $i++) {
    echo 'scoped: ', $scoped(), "\n";
    echo 'bound: ', $bound(), "\n";
}

// one array value, callers in turn: a built-in keeps the last callable it resolved
$callable = ['Base', 'who'];
class Turn extends Base {
    public function map($callable) { return implode(',', array_map($callable, [1])); }
    public static function smap($callable) { return implode(',', array_map($callable, [1])); }
}
function fmap($callable) { return implode(',', array_map($callable, [1])); }
for ($i = 0; $i < 3; $i++) {
    echo fmap($callable), ' ', (new Turn)->map($callable), ' ', Turn::smap($callable), ' ', fmap($callable), ' ',
        (new Base)->calls() === (new Base)->calls() ? 'same' : 'differs', "\n";
}
