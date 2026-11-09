# 073: `exit()` runs the shutdown functions before the stack unwinds

**State:** Open. Found 2026-11-09 while fixing the implicit state machines (055-071); confirmed on PHP 8.5 and on issues-state-machines 5fa1ee2.

**Symptom:** `register_shutdown_function(fn() => print(error_reporting()))`, then `@exit();`:

| | Shutdown function prints |
|---|---|
| PHP 8.5 | `30719`: the `@` was undone first |
| Truffle PHP | `4437`: still silenced |

**Cause:** `PhpContext.exit` (from the `exit` built-in) runs the shutdown functions, destroys the generators and ends
the output inside the call, while every PHP frame is still on the stack. PHP 8 throws `unwind_exit` instead. It leaves
each frame like an exception, except that no catch or finally block runs: `cleanup_live_vars` undoes the live ranges
(`@`, foreach) and the variables are freed. Only then does `php_request_shutdown` run the shutdown functions.

Whatever the frames still hold when a shutdown function runs differs from PHP:
- the `@` level;
- the arrays and objects in local variables. With destructors on, their destructors run after the shutdown functions,
  not before.

072 lists another `exit()` problem: its unguarded `endRequest`.

**Fix:** to do. Make `exit()` an unwinding exception that no catch or finally takes and that the frames' live ranges
see (`releaseFrame`, 060). The request's shutdown then runs where the script's end runs it.

**Test:** none yet (`@exit()` with a shutdown function printing `error_reporting()`).
