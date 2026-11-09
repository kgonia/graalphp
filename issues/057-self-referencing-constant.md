# 057: Class constants that refer to each other overflow the Java stack

**State:** Fixed. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** `class SR { const X = self::Y; const Y = self::X; }`, then `SR::X` (`class-constant-self-reference`):

| | Output |
|---|---|
| PHP 8.5 | `Error: Cannot declare self-referencing constant self::Y` (catchable) |
| Truffle PHP | JVM crash, `NoClassDefFoundError: Could not initialize class OptimizedCallTarget$ReturnProfile` after a stack overflow |

**Cause:** `PhpClass.getConstant` (`PhpClass.java:643-650`) runs a constant's `Thunk` and stores the result. While
the thunk runs, the constant has no "being evaluated" state, so a cycle recurses until the stack overflows. php-src
marks the constant with `IS_CONSTANT_VISITED` while `zval_update_constant_ex` runs and throws the Error when it finds
the mark.

**Fix:** a constant's slot in `PhpClass.constants` has three states: a `Thunk` (not evaluated), `Evaluating` (its
initializer running for a constant expression's lookup) and its value. A constant expression's lookup
(`ClassConstantInExpression`, php-src's `zend_get_class_constant_ex`) marks the slot while the initializer runs and
throws the Error when it finds the mark, naming the class as written (`self::Y`). A plain `Cls::X` doesn't mark
(php-src's `FETCH_CLASS_CONSTANT` doesn't), so the Error names the constant the cycle came back to, as PHP's does.
An initializer that throws leaves the slot unevaluated.

Two more differences found on the way, fixed with it:
- php-src evaluates all of a class's constants at its first object or static property access
  (`zend_update_class_constants`, `ZEND_ACC_CONSTANTS_UPDATED`): `new C` of a class with a bad constant throws.
  `PhpClass.updateConstants` does it, once all succeed; `property_exists` doesn't trigger it.
- The access check of a class constant comes before its initializer runs (`private const P = UNDEF` read from
  outside: "Cannot access private constant").

**Test:** `tests/smoke/class-constant-self-reference.php`.
