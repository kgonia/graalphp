# 006: Compile-time errors for jumps and try aren't raised

**State:** Open

**Symptom:** code php-src refuses to compile runs, or hangs:

| php-src's fatal error | Tests |
|---|---|
| `jump out of a finally block is disallowed` (goto, break, continue) | `Zend/tests/try/try_finally_005`, `007` (hangs: a goto back into the try from its finally), `008`, `009` |
| `jump into a finally block is disallowed` | `Zend/tests/try/finally_goto_001`, `002`, `004` |
| `Cannot use try without catch or finally` | `Zend/tests/try/try_finally_004` |
| `'break' not in the 'loop' or 'switch' context` | `Zend/tests/try/try_finally_011` (a Java `ControlFlow$Break` escapes instead) |

**Cause:** php-src raises these while compiling the file (`zend_compile_try`, `zend_compile_break_continue`, `zend_check_finally_breakout`), so they hit functions that are never called too. Truffle PHP translates a function on its first call and has no check pass over the whole file.

**Fix:** to do: a pass over a file's php-ast when it is compiled (include or the main script), raising these as E_COMPILE_ERROR before any of the file runs.
