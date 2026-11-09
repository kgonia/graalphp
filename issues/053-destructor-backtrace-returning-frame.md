# 053: A destructor that a function's return runs sees the returning function in its backtrace

**State:** Open. Found 2026-11-09 while checking the line a destructor sees during a function's epilogue.

**Symptom:**
```php
class E { function __destruct() {
  echo implode(' ', array_map(fn($f) => ($f['function'] ?? '') . '@' . ($f['line'] ?? '-'), debug_backtrace())), "\n";
} }
function f() { $e = new E; return 1; }
f();   // line 22
// PHP 8.5: __destruct@22
// Truffle PHP: __destruct@- f@22
```
An exception thrown from such a destructor has the same extra frame in `getTrace()`.

**Cause:** php-src's `zend_leave_helper` frees the compiled variables after `EG(current_execute_data)` points at the
caller again. The destructor's call is the caller's (its line is the caller's call), and the returning function is no
longer on the stack. Our epilogue (`PhpBytecodeRootNode.releaseVariables` from `ReturnValue`) runs in the
function's own frame, which `Backtrace` and `SourceLocation.current` then report.

**Fix:** to do. While a frame runs its return epilogue, the backtrace and the current line skip it. A destructor run
at the return then sees the caller's call line, as in PHP. The exceptional epilogue (`releaseFrame`) needs the same
check: php-src's `cleanup_live_vars` and `i_free_compiled_variables` also run with the frame gone.
