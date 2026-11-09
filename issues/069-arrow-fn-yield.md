# 069: An arrow function with `yield` isn't a generator

**State:** Fixed. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** `$f = fn() => yield 1; foreach ($f() as $v) echo $v;` (`arrow-fn-yield`):

| | Output |
|---|---|
| PHP 8.5 | `1` |
| Truffle PHP | `Fatal error: The "yield" expression can only be used inside a function` |

**Cause:** `Translator.java:898` decides whether a function is a generator from the node kind, and excludes
`AST_ARROW_FUNC`. php-src marks any function, arrow functions included, whose body contains `yield` as a generator
(`ZEND_ACC_GENERATOR`).

**Fix:** `Translator.translateBody` looks for `yield` in an arrow function too. Its body is already a return of
its expression, which in a generator is the generator's return value (`getReturn()`).

**Test:** `tests/smoke/arrow-fn-yield.php`.
