# 063: `include_once` of a missing file throws an Error instead of warning and returning false

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** `@include_once '/nonexistent/x.php'`, twice (`include-once-missing`):

| | Output |
|---|---|
| PHP 8.5 | `bool(false)`, twice |
| Truffle PHP | `Fatal error: Uncaught Error: Failed opening ... for inclusion` |

**Cause:** `IncludeNode` (`IncludeNode.java:37-44`) has two problems:
- It marks the path as included before loading it, so a second `include_once` of a file that failed would be
  skipped.
- `loadFile` reports a missing file as an `Error`.

php-src's `include`/`include_once` warns "Failed to open stream" and "Failed opening ... for inclusion" and returns
false. Only `require` is fatal. The file counts as included only once it has opened.

**Fix:** to do. Warn and return false for `include` and `include_once`. Mark the path once it has opened.

**Test:** `tests/smoke/include-once-missing.php`.
