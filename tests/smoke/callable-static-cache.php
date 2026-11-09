<?php
// Issue 056: 'static::m' as a callable string resolves against the calling class on each call.
class P { static function m() { return static::class; } function run() { return array_map('static::m', [1])[0]; } }
class A2 extends P {}
class B2 extends P {}
echo @(new A2)->run(), ' ', @(new B2)->run(), "\n";
// every way a callable names its class, from an instance, a static method, another class and the top level
class CP { static function m() { return static::class; } function i() { return static::class . '/' . get_class($this); }
  function run($c) { return call_user_func($c); } static function srun($c) { return call_user_func($c); } }
class CA2 extends CP { function prun($c) { return call_user_func($c); } }
class CQ { static function m() { return static::class; } function run($c) { return call_user_func($c); } }
$cs = ['CP::m', ['P', 'm'], 'self::m', ['self', 'm'], 'static::m', 'CP::i', 'self::i', 'static::i', 'CA2::m'];
foreach ($cs as $c) {
  $r = [];
  foreach ([fn() => (new CA2)->run($c), fn() => CA2::srun($c), fn() => (new CQ)->run($c), fn() => call_user_func($c)] as $k => $f) {
    try { $r[] = @$f(); } catch (Throwable $e) { $r[] = 'E:' . preg_replace('/^.*must be a valid callback, /', '', $e->getMessage()); }
  }
  echo json_encode($c), ' => ', implode(' | ', $r), "\n";
}
foreach (['parent::m', ['parent', 'm'], 'parent::i'] as $c) {
  $r = [];
  foreach ([fn() => (new CA2)->prun($c), fn() => (new CA2)->run($c)] as $f) {
    try { $r[] = @$f(); } catch (Throwable $e) { $r[] = 'E:' . preg_replace('/^.*must be a valid callback, /', '', $e->getMessage()); }
  }
  echo json_encode($c), ' => ', implode(' | ', $r), "\n";
}
