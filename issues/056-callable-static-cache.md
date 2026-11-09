# 056: `'static::m'` as a callable is resolved once, not per called class

**State:** Fixed. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** `array_map('static::m', ...)` called from an `A2` method and then from a `B2` method (both extend `P`):
PHP 8.5 prints `A2 B2`, Truffle PHP prints `A2 A2` (`callable-static-cache`).

**Cause:** `Callables.LastCall` (`Callables.java:455-536`) caches the target a callable string resolved to. A
`static::`, `self::` or `parent::` string resolves against the calling scope and the called class. Both can change
between two calls with the same string, and the cache doesn't key on them.

**Fix:** two parts.
- `LastCall` keeps no callable that names its class relative to the caller (`self::`, `parent::`, `static::`, as a
  string or in an array).
- The class part of a callable follows `zend_is_callable_check_class` (`Callables.classPart`), probed form by form
  (`callable-static-cache`):
  - self and parent are the caller's scope and its parent, static the caller's called class;
  - each gets the caller's called class when that is a subclass, and the caller's $this;
  - a named class gets the caller's $this, and its class as the called class, when $this is an instance of the
    caller's scope and the scope one of the class: `array_map('P::m', ...)` from an `A2` method calls `m` with
    `static` `A2`;
  - the errors are php-src's: "cannot access "self" when no class scope is active", "cannot access "parent" when
    current class scope has no parent".

**Test:** `tests/smoke/callable-static-cache.php`.
