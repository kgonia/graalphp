# 071: Compile-time diagnostics in a function body come at its first call, or never

**State:** Fixed. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** two functions whose bodies hold a backtick (deprecated at compile time), one never called, one called
after `echo "start"` (`compile-diagnostics-in-function`):

| | Output |
|---|---|
| PHP 8.5 | both Deprecated, then `start end` |
| Truffle PHP | `start`, the called function's Deprecated, `end`; nothing for the other |

**Cause:** a function body is translated at its first call. 006 moved the jump checks into a pass over the whole file
when it compiles. Diagnostics raised while translating (deprecations, other compile warnings) did not move with
them. 035 is the parser side of the same split.

**Fix:** compile-time diagnostics are part of a file's compile, as in php-src:
- The whole-file pass (`JumpChecks.file`) collects them in compile order, functions in place. A body the Java parser
  leaves unread keeps the ones found when the parse checked it (`AstBody.compileDiagnostics`).
- The file's parse diagnostics come first.
- They are stored with the translation (`CompileDiagnostics`). The file's code raises them as it starts
  (`RaiseCompileDiagnostics`), the first time in each context: the reference's CLI has OPcache, which compiles a
  file once and doesn't replay them (`opcache.record_warnings=0`). eval'd code is compiled at each eval.
- A compile error comes after the diagnostics before it.

The translator no longer raises them as it translates a body.

**Test:** `tests/smoke/compile-diagnostics-in-function.php` (with `.inc`).
