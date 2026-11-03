# 013: Methods' visibility isn't checked, and a private method isn't resolved in the calling scope

**State:** Open

**Symptom:**
```php
class A { private function f() { return 'A::f'; } function g() { return $this->f(); } }
class B extends A { function f() { return 'B::f'; } }
var_dump((new B)->g());   // PHP: "A::f" (the private method of the calling scope); Truffle PHP: "B::f"
class C { private function p() {} public function __call($n, $a) { return "__call $n"; } }
var_dump((new C)->p());   // PHP: "__call p" (an inaccessible method goes to __call); Truffle PHP: NULL from p()
class D { protected function q() {} }
(new D)->q();             // PHP: Error: Call to protected method D::q() from global scope; Truffle PHP: runs
```
Found while changing the method dispatch for partial evaluation round 4 (wiki/pe-round4.md), which keeps what
the dispatch did: it resolves a name in the receiver's class only.

**Cause:** `MethodLookupNode` (before it, `MethodCallNode.Dispatch`) and `StaticCallNode` find the method with
`PhpClass.findMethod(name)`; nothing checks `PhpFunction`'s modifiers against the calling scope.
php-src's `zend_std_get_method` and `zend_std_get_static_method` check the access from the executing scope, take
the calling scope's private method for a `ZEND_ACC_CHANGED` one (`zend_get_parent_private_method`), and fall back
to `__call` / `__callStatic` for an inaccessible one.

**Fix:** to do: a site's calling scope is its lexical class (known at translation), so the lookup stays a
per-class cache at the site, with php-src's rules in the lookup; callables (`Callables.resolve`) need the same.
