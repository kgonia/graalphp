# 067: A finally body is translated once per exit path, so its compile-time effects repeat

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

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

**Fix:** to do. Either translate the body once and dispatch on the way out, or key the one-shot effects by AST node
and run them for the first copy only. The second keeps the copies' speed.

**Test:** `tests/smoke/finally-emitted-once.php`.
