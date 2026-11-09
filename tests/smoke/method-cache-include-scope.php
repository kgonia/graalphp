<?php
// Issue 058: One included file calls a private method from two class scopes: the second call fails.
class A { private function hidden() { return 'A::hidden'; } function render($obj) { return include __DIR__ . '/method-cache-include-scope.inc'; } }
class B { function render($obj) { return include __DIR__ . '/method-cache-include-scope.inc'; } }
echo (new A)->render(new A), "\n";
try { echo (new B)->render(new A), "\n"; } catch (Error $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
// every scope-dependent lookup in an included file, from a class, another class, a function and the top level
class A2 { private $priv = 'priv'; private const PRIV = 'PRIV'; private static $ps = 'ps'; private static function sh() { return 'sh'; }
  private function hidden() { return 'A::hidden'; } function render($obj) { return include __DIR__ . '/method-cache-include-scope-all.inc'; } }
class B2 { function render($obj) { return include __DIR__ . '/method-cache-include-scope-all.inc'; } }
function plain2($obj) { return include __DIR__ . '/method-cache-include-scope-all.inc'; }
for ($i = 0; $i < 3; $i++) {
  echo (new A2)->render(new A2), "\n";
  echo (new B2)->render(new A2), "\n";
  echo plain2(new A2), "\n";
  $obj = new A2; echo include __DIR__ . '/method-cache-include-scope-all.inc'; echo "\n";
}
