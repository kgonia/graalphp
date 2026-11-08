<?php
// Issue 058: One included file calls a private method from two class scopes: the second call fails.
class A { private function hidden() { return 'A::hidden'; } function render($obj) { return include __DIR__ . '/method-cache-include-scope.inc'; } }
class B { function render($obj) { return include __DIR__ . '/method-cache-include-scope.inc'; } }
echo (new A)->render(new A), "\n";
try { echo (new B)->render(new A), "\n"; } catch (Error $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
