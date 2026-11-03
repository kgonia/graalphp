# 012: Class constants' visibility isn't checked

**State:** Fixed (branch `fix-objects-calls`, commit "Class constants' visibility...")

**Symptom:** a private or protected class constant can be read from anywhere, and `defined()` says it is defined
from a scope that can't access it:
```php
class A { private const P = 1; }
echo A::P;               // PHP: Error: Cannot access private constant A::P; Truffle PHP: 1
var_dump(defined('A::P')); // PHP: bool(false) (zend_get_class_constant_ex checks zend_verify_const_access); Truffle PHP: bool(true)
```
Found while pinning `defined()` for partial evaluation round 4 (wiki/pe-round4.md); its smoke test leaves the
non-public constants out.

**Cause:** `PhpClass` keeps a class constant's value only (`addConstant(name, value)`), not its modifiers, and
`ClassConstLookup` and `CoreFunctions.isDefined` look the value up without a scope. php-src keeps the flags on each
`zend_class_constant`, doesn't inherit private ones, and checks the access from the executing scope.

**Fix:** `ClassTemplate.Constant` keeps the visibility; `PhpClass` keeps the non-public constants with their declaring
class (`restrictedConstants`), doesn't inherit private ones (do_inherit_constant_check: `B::P` of a private `A::P` is
"Undefined constant B::P"), and `canAccessConstant` is zend_verify_const_access (private: the declaring class;
protected: zend_check_protected against it). `ConstantNodes.classConstant` raises "Cannot access private constant A::P"
from the scope: on bytecode the root's calling scope (a closure's bound class), on the tree `MethodCallNode.scope`, in a
constant expression its class (zend_update_class_constant). The bytecode site keeps its value as before (single context),
except a non-public constant read in a closure, whose scope may change. `constant()` raises the Error and `defined()`
says false from the caller's scope.

Found with it: a constant expression (`ThunkRootNode`) ran with its class as `$this` and no called class:
`call(null, cls, null)` bound to `CallTarget.call(Node, Object...)`.

Test: `tests/smoke/class-constant-visibility.php`.
