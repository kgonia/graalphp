# 060: A generator destroyed while suspended doesn't undo its frame's state (`@`, foreach)

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** a generator suspended inside `@(yield 1)` and then unset (`generator-abandoned-silence`):

| | `error_reporting()` afterwards |
|---|---|
| PHP 8.5 | 30719, restored |
| Truffle PHP | 4437, so warnings stay silenced for the rest of the request |

**Cause:** a suspended frame holds state that an unfinished statement must undo:
- the `error_reporting` that `@` saved;
- the foreach iterations registered by reference (`RefIteration`);
- the scope.

A generator that is closed without resuming never undoes them. php-src does this in
`zend_generator_cleanup_unfinished_execution`, which calls `zend_cleanup_unfinished_execution`. It walks the live
ranges at the suspended opline. A `ZEND_LIVE_SILENCE` range restores `EG(error_reporting)`, and loop ranges free
their iterators.

When the generator is destroyed depends on reference counts (005, 036). Before those, request end is the latest
point. The state is wrong from there on in every case.

**Fix:** to do. Record, per yield point, the state to undo (an `@` level, open iterations), as php-src's live ranges
do. Undo it when a suspended generator is destroyed.

**Test:** `tests/smoke/generator-abandoned-silence.php`.
