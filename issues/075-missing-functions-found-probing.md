# 075: Built-ins missing that the state-machine probes needed

**State:** Open. Found 2026-11-09 while fixing the implicit state machines (055-071); confirmed on PHP 8.5 and on issues-state-machines 5fa1ee2.

**Symptom:** each is "Call to undefined function" (or method) here, defined in PHP 8.5:

| Function | Where it came up |
|---|---|
| `get_included_files()` (`get_required_files()`) | 063's probe: what counts as included |
| `debug_zval_dump()` | 064's probe: it shares var_dump's DEBUG recursion mark |
| `ReflectionFunction::isGenerator()` | 069's probe |

**Fix:** to do. `get_included_files` reads `PhpContext.included`, in inclusion order. `debug_zval_dump` is
`php_debug_zval_dump`: var_dump's output with refcounts, under the DEBUG mark (`RecursionGuard`). `isGenerator` is the
function's generator flag.

**Test:** none yet.
