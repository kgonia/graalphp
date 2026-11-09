# 064: `var_dump` from inside `__debugInfo` crashes the JVM instead of printing `*RECURSION*`

**State:** Fixed. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** `class A { function __debugInfo() { var_dump($this); return []; } }`, `var_dump(new A)`
(`var-dump-debuginfo-recursion`):

| | Output |
|---|---|
| PHP 8.5 | `*RECURSION*`, then `object(A)#1 (0) {}` |
| Truffle PHP | JVM crash, `NoClassDefFoundError: Could not initialize class OptimizedCallTarget$ReturnProfile` after a stack overflow |

**Cause:** the recursion guard is `Dump.inProgress` (`Dump.java:29`, used at `74-80`), a set that each `var_dump`
call starts empty. A `var_dump` inside `__debugInfo` starts a new one, so it never sees the outer object. php-src
keeps the mark on the object itself (`Z_PROTECT_RECURSION`, `GC_IS_RECURSIVE`), so it holds across nested calls.
The same is likely for `print_r` and `var_export` (042) if they use a per-call set.

**Fix:** the recursion marks are the context's `RecursionGuard`, kept on the values as php-src keeps them, so a
walk that user code starts inside another sees the outer one's. Probed on PHP 8.5 (`var-dump-debuginfo-recursion`):
- An array has one mark, `GC_PROTECT_RECURSION`, which var_dump, print_r, var_export and json_encode all share. A
  frozen (literal) array, immutable in php-src, has none.
- An object has one mark per kind of walk (`ZEND_GUARD_RECURSION_DEBUG`, `_EXPORT`, `_JSON`): var_dump and print_r
  share DEBUG, var_export and json_encode have their own. print_r tests the mark before `__debugInfo` and sets it
  only after, so a print_r inside its own `__debugInfo` recurses until the stack runs out, on PHP too.

Three more differences found on the way, fixed with it:
- var_dump writes as it goes (`php_printf`): what it printed before calling `__debugInfo` comes before what
  `__debugInfo` prints.
- `__debugInfo` returning null is deprecated in 8.5 ("Returning null from A::__debugInfo() is deprecated").
  Anything else that isn't an array is a fatal error, not an Error. An exception out of it is the same fatal error,
  with the exception reported first as a warning and no backtrace (`ErrorFunctions.fatalAfterException`, php-src's
  `zend_error_zstr_at`; `debuginfo-fatal`).

**Test:** `tests/smoke/var-dump-debuginfo-recursion.php`, `tests/smoke/debuginfo-fatal.php`.
