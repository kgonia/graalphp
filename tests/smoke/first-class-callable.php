<?php
// The first-class callable syntax makes a Closure of what the call would call: functions (namespaced first),
// methods bound to their object, self/parent/static with the caller's $this, __call and __callStatic for missing
// methods, any callable value, and the errors the call itself would raise.
namespace NS { function local($x) { return "local $x"; } function run() { $a = local(...); $b = strlen(...); return [$a(1), $b('abcd')]; } }
namespace {
class P { public function pm($x) { return 'pm ' . get_class($this) . " $x"; } public static function ps() { return 'ps ' . static::class; } }
class A extends P {
    private $v = 'private';
    private function priv() { return $this->v; }
    public static function make() { return [self::ps(...), static::ps(...), parent::ps(...)]; }
    public function bound() { return [$this->priv(...), parent::pm(...), self::pm(...), $this->missing(...), A::other(...)]; }
    public function __call($n, $a) { return "__call $n(" . implode(',', $a) . ')'; }
    public static function __callStatic($n, $a) { return "__callStatic $n(" . implode(',', $a) . ')'; }
}
class B extends A {}
var_dump(NS\run());
$s = strlen(...);
var_dump($s('hello'), $s instanceof Closure);
foreach (B::make() as $f) var_dump($f());
foreach ((new B)->bound() as $f) var_dump($f(7));
var_dump(A::stat(...)(1, 2));
$m = 'pm';
$o = new B;
var_dump($o->$m(...)(3), [$o, 'pm'](...)(4), 'A::ps'(...)(), $s(...)('xy'));
$c = function ($x) { return $x * 2; };
var_dump($c(...)(21), $c(...) === $c);
foreach ([fn() => nope(...), fn() => null->m(...), fn() => P::pm(...), fn() => P::nothing(...), fn() => (new P)->nothing(...)] as $t) {
    try { $t(); } catch (Error $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
}
var_dump(array_map(intval(...), ["1", "2x"]), "A::ps"(), $c->__invoke(...)(5));
$arr = ['b', 'a']; usort($arr, strcmp(...)); var_dump($arr);
}
