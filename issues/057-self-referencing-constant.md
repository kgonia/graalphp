# 057: Class constants that refer to each other overflow the Java stack

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** `class SR { const X = self::Y; const Y = self::X; }`, then `SR::X` (`class-constant-self-reference`):

| | Output |
|---|---|
| PHP 8.5 | `Error: Cannot declare self-referencing constant self::Y` (catchable) |
| Truffle PHP | JVM crash, `NoClassDefFoundError: Could not initialize class OptimizedCallTarget$ReturnProfile` after a stack overflow |

**Cause:** `PhpClass.getConstant` (`PhpClass.java:643-650`) runs a constant's `Thunk` and stores the result. While
the thunk runs, the constant has no "being evaluated" state, so a cycle recurses until the stack overflows. php-src
marks the constant with `IS_CONSTANT_VISITED` while `zval_update_constant_ex` runs and throws the Error when it finds
the mark.

**Fix:** to do. Mark the constant while its thunk runs, and throw the Error when the mark is found. Do the same for
static property and default property initializers if they share the path.

**Test:** `tests/smoke/class-constant-self-reference.php`.
