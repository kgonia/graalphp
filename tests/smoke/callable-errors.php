<?php
// Callables that resolve against the calling code (a class callable takes the caller's $this; "parent::m" its
// scope), and php-src's errors for values that aren't callable: a built-in checks its callback parameter on entry,
// even when it would never call it, with a TypeError naming the parameter; a direct call `$f()` raises an Error.
class A
{
    function m($x = null)
    {
        return (isset($this) ? get_class($this) : 'none') . " $x";
    }

    function __call($n, $a)
    {
        return "__call $n " . (isset($this) ? get_class($this) : 'none');
    }

    static function __callStatic($n, $a)
    {
        return "__callStatic $n";
    }

    // a direct call `$f()` takes neither the caller's $this nor its scope: a non-static method is an error, and so is
    // a missing one (php-src picks __call, non-static); "self" and 'parent::m' are literal names there
    function t()
    {
        $result = [call_user_func(['A', 'm'], 1), call_user_func('A::m', 2), call_user_func(['A', 'missing'])];
        foreach ([['A', 'm'], 'A::missing', 'self::m', ['A', 'parent::x'], [$this, 'parent::x']] as $f) {
            try {
                $result[] = $f(3);
            } catch (Error $e) {
                $result[] = get_class($e) . ': ' . $e->getMessage();
            }
        }
        return $result;
    }
}

class B extends A
{
    function u()
    {
        return [call_user_func(['A', 'm'], 5), call_user_func('A::m', 7)];
    }
}

class C
{
    function v()
    {
        try {
            return call_user_func(['A', 'm'], 8);
        } catch (Error $e) {
            return get_class($e) . ': ' . $e->getMessage();
        }
    }
}

class Plain
{
    function m()
    {
    }
}

class NoMagic
{
}

var_dump((new A)->t(), (new B)->u(), (new C)->v(), call_user_func(['A', 'missing']));

$values = ['nofunc', 'Nope::m', 'Plain::nomethod', 'Plain::m', ['Plain'], ['Plain', 'm', 'x'], [1, 'm'],
    ['Plain', 5], ['Nope', 'm'], [new Plain, 'nomethod'], ['Plain', 'm'], 42, null, new NoMagic];
foreach ($values as $c) {
    try { call_user_func($c); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
    try { array_map($c, [1]); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
    try { $a = [2, 1]; usort($a, $c); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
    try { $c(); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
}

$empty = [];
$nothing = 5;
$cases = [
    fn() => set_error_handler('nofunc'),
    fn() => spl_autoload_register('nofunc'),
    fn() => register_shutdown_function('nofunc'),
    fn() => array_walk($GLOBALS['empty'], 'nofunc'),
    fn() => array_walk_recursive($GLOBALS['empty'], 'nofunc'),
    fn() => array_reduce([], 'nofunc'),
    fn() => array_filter([], 'nofunc'),
    fn() => array_map('nofunc', []),
    fn() => array_map('nofunc', [1], [2]),
    fn() => array_any([], 'nofunc'),
    fn() => array_all([], 'nofunc'),
    fn() => array_find([], 'nofunc'),
    fn() => array_find_key([], 'nofunc'),
    fn() => preg_replace_callback('/x/', 'nofunc', 'y'),
    fn() => usort($GLOBALS['empty'], 'nofunc'),
    fn() => uasort($GLOBALS['empty'], 'nofunc'),
    fn() => uksort($GLOBALS['empty'], 'nofunc'),
    fn() => usort($GLOBALS['nothing'], 'nofunc'),
    fn() => ksort($GLOBALS['nothing']),
    fn() => call_user_func_array('nofunc', []),
];
foreach ($cases as $i => $case) {
    try {
        var_dump($case());
    } catch (Throwable $e) {
        echo $i, ' ', get_class($e), ': ', $e->getMessage(), "\n";
    }
}
// through a dynamic name: the built-in itself rather than the translator's inline call
$f = 'call_user_func';
try { $f('nofunc'); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
$g = 'array_map';
try { $g('nofunc', []); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
