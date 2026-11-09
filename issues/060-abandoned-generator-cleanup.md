# 060: A generator destroyed while suspended doesn't undo its frame's state (`@`, foreach)

**State:** Fixed. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

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

**Fix:** the frame's live ranges, as php-src's: `PhpBytecodeRootNode.liveRanges`, the hidden locals an unfinished
statement holds state in. A foreach loop's (what it iterates) was one already; `@`'s error_reporting before it is
now another. `SilenceEnd` clears it when the `@` ends. `releaseFrame` undoes what is left (`cleanup_live_vars`), for
a generator destroyed while suspended and for an internal exception. Undoing restores error_reporting as
END_SILENCE does.

The smoke test passes with `-Dphp.rc.destructors=true` (checked; it failed before the fix). With destructors off,
the default, a generator is destroyed only at request end (005, 036), which the test can't show.

Found on the way: `exit()` runs the shutdown functions inside the call, before the frames unwind. PHP 8 unwinds
first (`unwind_exit`), so `@exit()` leaves the shutdown functions silenced here. That is 073.

**Test:** `tests/smoke/generator-abandoned-silence.php`.
