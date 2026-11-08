# 055: Output buffering doesn't know a handler is running, or that one failed

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:**

| Case | PHP 8.5 | Truffle PHP |
|---|---|---|
| A handler calls `ob_end_clean()` on its own buffer (`ob-handler-nested-end`) | Fatal error "ob_end_clean(): Cannot use output buffering in output buffering display handlers" | the handler runs again, the outer buffer is lost: `x level=0` |
| The same without a guard in the handler | the same fatal error | Java `StackOverflowError` |
| A handler throws (`ob-handler-throws`) | `y[caught h1] level=0 n=1` | prints nothing at all |

**Cause:** `PhpContext.process` (`PhpContext.java:981`) calls the handler with nothing marking it as running. The
output stack (`PhpContext.java:117`, `981-1043`) has one state per buffer, `started`. php-src has two more:
- `OG(running)`, the handler being called. Any `ob_*` call while it is set ends in
  `php_output_handler_reset_and_raise` or `php_output_lock_error`, which is the fatal error above.
- `PHP_OUTPUT_HANDLER_DISABLED`, set on a handler that failed. Its buffer is passed on as it is, and the handler is
  not called again.

**Fix:** to do. Add both states, as in php-src's `main/output.c`:
- a per-context "handler running" mark, checked by every `ob_*` function;
- a per-buffer "disabled" flag, set when the handler fails or throws.

**Test:** `tests/smoke/ob-handler-nested-end.php`, `tests/smoke/ob-handler-throws.php`.
