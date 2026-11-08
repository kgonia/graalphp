# 064: `var_dump` from inside `__debugInfo` crashes the JVM instead of printing `*RECURSION*`

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

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

**Fix:** to do. Keep the guard per context (or on the object) for the time the object is being printed.

**Test:** `tests/smoke/var-dump-debuginfo-recursion.php`.
