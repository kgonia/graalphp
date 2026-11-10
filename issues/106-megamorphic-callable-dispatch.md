# 106: a call site that sees more than six callables resolves each one from its name at every call

**State:** Open. Found 2026-11-10 in [wiki/ceiling.md](../wiki/ceiling.md).

**What happens:** `CallableDispatchNode` (calls through a callable: `call_user_func`, `call_user_func_array`,
`$f(...)` of a string or an array, what `WP_Hook::apply_filters` does for every callback of every hook) caches up
to `LIMIT = 6` callables a site, by the closure's function, the function's name, the object's class and method
name, or the class and method names. The seventh sends the site to `generic` for good:
`Callables.resolveOrThrow` behind a `@TruffleBoundary` for every call, which parses the callable again
(a name: lower-case it and look it up; an array: its two members, the class by name or the object's class, the
method by name with its visibility from the caller's scope), then an indirect call. `WP_Hook::apply_filters` has
one such site for all of a site's hooks: hundreds of callables.

**How much:**

| where | measure |
|---|---|
| `hot.php` `mega`: `apply_filters` on 40 hooks of 5 callbacks each | 41.5 µs against PHP's 22.8: 1.82× (`one`, a single callback: 1.39×) |
| the front page (JFR, `tests/gate2/JfrSplit.java`) | `Callables.resolveOrThrow` called by compiled code: 1.6% of the render's samples, inclusive; `OptimizedCallTarget.profileIndirectArguments` 0.5% more |

php-src resolves a callable once per `call_user_func_array` too (`zend_is_callable_ex`), but for a name it is one
hash lookup of an interned string with its hash kept, and for `[$object, 'method']` one lookup in the class's
function table; `WP_Hook` keeps the callables as it got them, so both engines resolve at every call. The difference
is what a resolution costs.

**Fix (a sketch):** between the six cached entries and `resolveOrThrow`, a table per site, direct-mapped, of
immutable entries `(key parts, Target)`:

- a string callable: the entry's key is the string instance (identity), then equality; the target is valid while
  the context's function table has the same function under that id (the check `named` already makes);
- `[$object, 'method']`: the object's class key and the method's string instance; valid as `objectMethod`'s entry
  is (not when the result depends on the caller's scope: those stay with `resolveOrThrow`);
- `['Class', 'method']`: the two string instances and the class id's key, as `classMethod`.

The index from the identity hash of the first part (or the string's own hash, which a `TruffleString` keeps); a
miss replaces the entry. Entries are immutable objects published by one array store, so a context on another
thread reads either the old entry or the new one. The table is reached without a boundary; the call stays
indirect. 64 entries a site would cover a hook-heavy page; the table is allocated when the site first goes
generic, so sites below seven callables pay nothing.

**Check:** `mega` in `tests/gate2/kernels/hot.php` (a kernel alone, then the front page by `tests/gate2/k8-cli.sh`
with a compile trace: the page, not the kernel, decides, see the wiki page). The callable tests of
`tests/smoke` and the PHPT callable set for the scope-dependent cases.
