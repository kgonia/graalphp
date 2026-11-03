# 006: Compile-time errors for jumps and try aren't raised

**State:** Fixed; php-src's compile-time `continue` warnings open

**Symptom:** code php-src refuses to compile runs, or hangs:

| php-src's fatal error | Tests |
|---|---|
| `jump out of a finally block is disallowed` (goto, break, continue) | `Zend/tests/try/try_finally_005`, `007` (hangs: a goto back into the try from its finally), `008`, `009` |
| `jump into a finally block is disallowed` | `Zend/tests/try/finally_goto_001`, `002`, `004` |
| `Cannot use try without catch or finally` | `Zend/tests/try/try_finally_004` |
| `'break' not in the 'loop' or 'switch' context` | `Zend/tests/try/try_finally_011` (a Java `ControlFlow$Break` escapes instead) |

**Cause:** php-src raises these while compiling the file (`zend_compile_try`, `zend_compile_break_continue`, `zend_check_finally_breakout`), so they hit functions that are never called too. Truffle PHP translates a function on its first call and has no check pass over the whole file.

**Fix:** `parser/JumpChecks.java`, a pass over the file's tree when it is compiled, raising php-src's
`E_COMPILE_ERROR` before any of the file runs, at php-src's line. Each function, method and closure is its own
op_array, checked where php-src compiles it: its compile-time errors as the walk meets them (`'break' operator with
non-integer operand is no longer supported`, `... accepts only positive integers`, `'break' not in the 'loop' or
'switch' context`, `Cannot 'break' 2 levels`, `Label 'x' already defined`, `Cannot use try without catch or
finally`), then pass_two's for its jumps in order (`'goto' to undefined label 'x'`, `'goto' into loop or switch
statement is disallowed`, `jump into a finally block is disallowed`, `jump out of a finally block is disallowed`: the
first finally block in try order that one end is in and the other isn't, as `zend_check_finally_breakout`).
- With the php-ast export, `Translator.translateFile` runs it, reading the bodies left unread.
- With `php.Parser=java`, the parse runs it on each function body while php-src's tree of it is at hand
  (`ZendParser.Bodies.leave`: the body converted once more, the cost of an eager parse's conversion, not of a parse).
  A body that fails is kept converted, so that the file's pass reports it where php-src's compile reaches it; a body
  that passes isn't read again.

Tests: `tests/smoke/compile-jump-finally.php` (an included file: the include fails, the file doesn't run),
`compile-jump-into-finally`, `compile-try-alone`, `compile-break-context`, `compile-goto-errors` (a function's own
errors come first), `compile-goto-loop`; each passes with `php.Parser=java` too.

**Still open:** php-src's compile-time warnings `"continue" targeting switch is equivalent to "break"` (and the
`continue N` forms). A compile warning is raised in the context that compiles the file (opcache keeps no warnings);
the parse's diagnostics have a way to do that with `php.Parser=java` only (`SourceFiles`), and the export has none.
