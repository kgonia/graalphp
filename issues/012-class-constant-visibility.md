# 012: Class constants' visibility isn't checked

**State:** Open

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

**Fix:** to do: the modifiers with each constant (private ones not inherited), and the check at the translated
site (its class scope is known there) and in `defined()` and `constant()` (the caller's scope).
