# 098: Three lookups did a name's work on every call: a megamorphic method call, `global`, a function-name callable

**State:** Fixed 2026-11-10 on `fastcgi`. Found 2026-11-10 by the in-situ profile and the allocation profile of the
K8 loop ([wiki/beat-php.md](../wiki/beat-php.md)): none of them is a hot spot by time alone, the first one is by
allocation.

**What happened:**

1. **A method call at a site that has seen more than four classes** (`ResolveMethod`'s `object` specialization:
   `$this->$name()`, `$block->render()`, a hook's `[$object, 'method']`) found the method with
   `PhpClass.methodFrom`, which asked `declaringClass(method)` for the visibility check, and that lowercased
   the declaring class's name on each call (`Str.lower` → a new `byte[]` and `TruffleString`): ≈ 9.6% of the bytes
   a hot render allocates (≈ 1.1 MB of ≈ 12 MB), 2.5% of the main thread's samples.
2. **`global $name;` in one context**: a `GlobalRefNode` site that had seen more than three versions of the
   context's globals kept the last reference it found with the version it found it in. A render rebinds and unsets
   globals thousands of times, each a new version, so the site looked the name up in the globals table by hash
   after each: 2.1–3.2% of the samples (`global $wp_filter, $wp_current_filter;` runs 5,864 times in a render of
   the front page).
3. **A callable that is a function's name** (`call_user_func('strtolower', …)`, `array_map('trim', …)`,
   `apply_filters`' plain-function callbacks): `Callables.target`'s one-entry cache did not keep it (the test "is
   the resolution the same from every caller" made two Java `String` copies of the name to look for `::` and
   `self`/`parent`/`static`, and then the answer was computed again), so each resolution was two string copies and
   a function-table lookup.

**Fix:**

1. `PhpFunction.scopeName` (the lowercased declaring class's name, set once where the class name is set) and
   `PhpClass.methodFrom(name, lower, scope)`, which the megamorphic `ResolveMethod.find` calls: no allocation.
2. `GlobalRefNode`: the checked state (`checked`, `LastRef`) is gone. After `constant` (a single context's
   reference under the version guard, three versions at most, as before) a site is `bySlot`: it reads the
   context's array of references by the name's id (`PhpContext.globalSlots`, which a shared engine's contexts
   used already): one array load, whatever the version.
3. `Callables.target`: a `TruffleString` callable whose resolved target has no static class is a function: the
   same from every caller, kept by the one-entry cache without the string copies. `"Cls::m"` still takes the
   caller-dependent path.

Host JVM (P-cores), `tests/gate2/kernels/hot.php`, final round, ns per op (one run each; on this box a single kernel
moves by up to 1.8× between runs, see [101](101-machine-two-speeds.md)):

| kernel | before | after |
|---|---|---|
| `args` (`func_get_args` + `call_user_func_array`, what `do_action` does) | 42,049 | 31,618 |
| `esc` (`esc_attr` + `esc_html` + `sanitize_title`): share of samples under `Callables.target` | 18.6% | 7.2% |

In the K8 loop: see the table of builds in [wiki/beat-php.md](../wiki/beat-php.md).

**Memory:** a `String` reference per `PhpFunction`; nothing else is new (the table of references by id was kept
per context before).

**Open beside it:** which `ResolveMethod` sites are megamorphic (more than four receiver classes) and why was not
looked at; the megamorphic call is still a map read by name and a visibility check behind a boundary.

**Test:** no behaviour changes; the smoke tests that cover the three paths (each also run in three contexts of a
shared engine): `tests/smoke/method-visibility.php`, `callable-caller.php` (methods by scope),
`globals.php`, `global-slots.php`, `globals-writes.php`, `this-globals.php` (a `global` after an unset, a rebind,
a `$GLOBALS` write), `callables.php`, `callable-names.php`, `callable-static-cache.php`.
