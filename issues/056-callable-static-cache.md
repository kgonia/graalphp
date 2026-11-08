# 056: `'static::m'` as a callable is resolved once, not per called class

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** `array_map('static::m', ...)` called from an `A2` method and then from a `B2` method (both extend `P`):
PHP 8.5 prints `A2 B2`, Truffle PHP prints `A2 A2` (`callable-static-cache`).

**Cause:** `Callables.LastCall` (`Callables.java:455-536`) caches the target a callable string resolved to. A
`static::`, `self::` or `parent::` string resolves against the calling scope and the called class. Both can change
between two calls with the same string, and the cache doesn't key on them.

**Fix:** to do. Either don't cache scope-relative strings, or add the scope and the called class to the cache key.

**Test:** `tests/smoke/callable-static-cache.php`.
