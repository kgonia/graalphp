# 067: A finally body is translated once per exit path, so its compile-time effects repeat

**State:** Fixed. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** (`finally-emitted-once`)

| Case | PHP 8.5 | Truffle PHP |
|---|---|---|
| `new class {}` in a finally reached by `return` and by falling through | one class: `bool(true)` | two classes, with different names: `bool(false)` |
| a backtick in a finally reached by `continue` and by falling through | one Deprecated | three |

**Cause:** `BytecodeTranslator` (`BytecodeTranslator.java:1738-1769`) translates a finally body again for each way
out of its try: normal, `return`, `break`/`continue`, exception. The translator's one-shot effects then run once per
copy:
- `made()` (line 6406) declares an anonymous class per copy;
- compile-time warnings (line 2152) are raised per copy.

php-src compiles the body once and reaches it through `ZEND_FAST_CALL`.

**Fix:** the copies stay, for their speed: the DSL emits a finally body once per way out of its try. The one-shot
effects are keyed by AST node within a translation, so the copies share them:
- `madeOnce` gives every copy the anonymous class the first one declared;
- `firstMeeting` raises a compile-time diagnostic (the backtick's Deprecated) once.

With one context the test passes. With three contexts on one engine the Deprecated is missing in the later
contexts, which reuse the translation; main does the same. That is 071's: compile-time diagnostics belong to every
compile.

**Test:** `tests/smoke/finally-emitted-once.php`.
