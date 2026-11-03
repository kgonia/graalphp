# 013: Methods' visibility isn't checked, and a private method isn't resolved in the calling scope

**State:** Fixed (branch `fix-objects-calls`, commit "Methods' visibility from the calling scope...")

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

**Fix:** php-src's rules are in `PhpClass`: `methodFrom` (zend_std_get_method: the scope's own private method for a
name a subclass declares again, `__call` for a missing or inaccessible one, else zend_undefined_method's or
zend_bad_method_call's Error), `staticMethodFrom` (zend_std_get_static_method), `canCall` (zend_check_method_accessible,
protected against the method's root class). Every lookup takes the calling scope (php-src's executed scope): on
bytecode `PhpBytecodeRootNode.callingScope` (the declaring class; a closure's bound class), on the tree
`MethodCallNode.scope`.
- `ResolveMethod` and `ResolveStaticMethod` stay per-class-key caches: an entry is kept while its answer follows from the
  key (`isMethodAccessKeyed`, else the site looks the method up per call, as properties do) and, in a closure, while the
  bound scope is the entry's or the method doesn't depend on it (`methodDependsOnScope`: public, no private method of an
  ancestor). In a method both are constants, so the compiled dispatch is what it was.
- `Cls::m()` of a non-static method without a `$this` of the class is zend_non_static_method_call's Error (it passed a
  null `$this`); `Cls::__construct()` is the constructor without a lookup, "Cannot call constructor" without one, and
  "Cannot call private X::__construct()" on an object of another class (INIT_STATIC_METHOD_CALL).
- `new` checks a constructor that isn't public (zend_std_get_constructor), `clone` a `__clone` (ZEND_CLONE).
- Callables: a direct call (`$f()`, `[$o, 'm']()`, `'A::m'()`, `$o->$m()`) resolves from the site's scope; a built-in's
  callable (call_user_func, is_callable...) from its caller's, with zend_is_callable's "cannot access private method
  A::f()"; a callable whose resolution depends on the scope isn't kept in `LastCall` or a `CallableDispatchNode` entry.
- First-class callables (`$o->m(...)`, `A::m(...)`, `$f(...)`) resolve as the call would.

Test: `tests/smoke/method-visibility.php`. Simplified (`ponytail:`): on the tree, a method run through a closure made
of it (`$o->m(...)`) takes that closure's class as its scope; trait aliases' visibility (`m as protected`) isn't applied.
