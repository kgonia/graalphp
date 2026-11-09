# 055: Output buffering doesn't know a handler is running, or that one failed

**State:** Fixed. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

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

**Fix:** the output stack in `PhpContext` has php-src's states (`main/output.c`):
- `running` (`OG(running)`): the buffer whose handler runs. An output-buffer operation meanwhile (`ob_start`, or
  one that runs a handler: `ob_end_*`, `ob_get_*`, `ob_flush`, `ob_clean`) is the fatal error. It discards every
  buffer unsent (`php_output_deactivate`).
- A handler's `HandlerState`: `NEW`, `STARTED` or `DISABLED`. A handler that returns false or throws is disabled.
  Its buffer is passed on as it is and the handler isn't called again; what is written then goes on to the level
  below. The exception surfaces once the operation has finished.
- Output from inside a handler is lost with its buffer, and Deprecated ("Producing output from user output handler
  NAME is deprecated").

Also from `main/output.c`:
- each `ob_*` function's notices ("Failed to delete buffer. No buffer to delete", "Failed to discard buffer of NAME
  (LEVEL)");
- `ob_get_clean` and `ob_get_flush` return the contents even when the buffer isn't removable;
- handlers are named as `zend_fcall_info_init` names them (`{closure:FILE:LINE}`, `K::m`);
- true, null or "" from a handler pass nothing.

**Test:** `tests/smoke/ob-handler-nested-end.php`, `tests/smoke/ob-handler-throws.php`, `tests/smoke/ob-handler-states.php`, `tests/smoke/ob-start-in-handler.php`.
