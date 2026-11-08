# 071: Compile-time diagnostics in a function body come at its first call, or never

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** two functions whose bodies hold a backtick (deprecated at compile time), one never called, one called
after `echo "start"` (`compile-diagnostics-in-function`):

| | Output |
|---|---|
| PHP 8.5 | both Deprecated, then `start end` |
| Truffle PHP | `start`, the called function's Deprecated, `end`; nothing for the other |

**Cause:** a function body is translated at its first call. 006 moved the jump checks into a pass over the whole file
when it compiles. Diagnostics raised while translating (deprecations, other compile warnings) did not move with
them. 035 is the parser side of the same split.

**Fix:** to do. Raise every compile-time diagnostic in the whole-file pass of 006, in source order, and only there.

**Test:** `tests/smoke/compile-diagnostics-in-function.php`.
