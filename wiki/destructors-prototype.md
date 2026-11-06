# Destructors: the counting-only prototype and its A/B (roadmap step 5.1)

Item 1 of [destructors.md](destructors.md)'s "Prototype and thresholds", measured against main at `851bb6d`
(branch `destructors`, 2026-11-06). Objects get reference counts on the store paths. Nothing runs at zero yet, and
behaviour doesn't change: every check equals main's.

## What is counted
- **`PhpObject`** gets `int rc` and `int gcInfo`. `release()` is `--rc`, then one `@TruffleBoundary` call
  (`Refcount.released`) when the count reaches zero or the object isn't buffered yet. Both events go to empty
  stubs: `zero` and `possibleRoot`, which marks the object buffered as `gc_possible_root` does. `disown()` is
  HHVM's DecRefNZ, for a count that moves to a store. `-Dphp.rc.stats=true` prints the number of events at exit, and
  `-Dphp.rc.trace=true` prints every zero.
- **Store paths:**
  - `Locals.Writes` gains NEW_OBJECT and OLD_OBJECT bits. The old object is released even when it is the new value,
    which the store counted again.
  - `releaseVariables` gains a released-object bit. The returned array keeps its elements' counts
    (`removeReturnedHolder`), because php-src's return keeps a count.
  - `PropNode` (writes with the old value read, `initialize`, make/bind ref, unset) and `RefWrites` get object bits.
  - `PhpRef` (construction, `set`, release/take back), and `PhpArray.countIn/countOut`, copy, pop, shift, prepend
    and ref-at-position are counted too.
- **Calls:**
  - `BindParameter` (SEND_VAR) and by-value captures and `MakeClosure` add a reference.
  - A call's result owns its count: a user function's return, or `BuiltinRootNode`'s result.
  - A method call's receiver is counted per `MethodSite` kind. `$this` isn't counted. `$cv->m()` is a skipped pair
    that still raises the possible-root event. Any other receiver gets a counted pair around the call.
- **Owning temporaries:**
  - The translator marks the result of `new` and of a call as owning (`ownedNode`, decided after the child is
    emitted through `@ConstantOperand(specifyAtEnd = true)`).
  - An owning temporary used as an operand, an argument or a receiver goes into a pooled hidden local (`StoreTemp`).
    Function-wide locals, `enableBlockScoping = false`.
  - Each such local is released at the end of its statement (`ReleaseTemp`) or by the epilogue on a return or an
    exception.
  - A store moves the count (`Disown`) and an expression statement drops it (`ReleaseObject`). A returned variable
    gains one (`Own`), as php-src's RETURN copies a CV.
  - Each hidden local has a held bit, so one that never held an object costs nothing in compiled code.
- **F4:** `PhpArray.holdsCounted`. At the last holder an array of arrays or objects releases its elements' counts
  and becomes `RELEASED` (-1, shared as an unsigned number). A new holder counts them again (`regain`).

**Known approximations** (the census oracle, item 3, is what would measure them):
- Arrays keep "temporaries own nothing": only objects follow the owning-temporary discipline. Exact array
  ownership needs moves into arguments, or every owned array passed on is copied once more on write (step 6, P2).
- Temporaries are released at the end of their statement, not right after their consumer as php-src does.
- Closures and generators aren't `PhpObject`, so they aren't counted (F3 stays open).
- Not counted:
  - `PhpObject.setProperty` (built-ins' own properties, defaults);
  - receivers of `CallMethodNamed` and spread calls;
  - the shared enum objects (they should be uncounted, D18 answer 6, but aren't marked).
- Calls compiled in place (intrinsics, frame functions) are borrowed. When the real call runs instead, its +1 is
  never given back. That overcounts, which is safe for the decision but would delay a destructor.
- An exception thrown after a `Disown` and before the store can undercount.

**Events on Gate 1** (one iteration each, `-Dphp.rc.stats=true`):

| kernel | zeros | possible roots |
|---|---|---|
| hooks | 0 | 4 |
| escape, slugs, wpautop, wptexturize | 0 | 1 |
| blocks | 5,517 | 5,209 |
| kses | 3,538 | 6,591 |

A small script with ten objects freed at known points (temporaries, arguments, returned arrays, overwritten
variables, properties) gives php-src's ten zeros in php-src's order.

## Checks (against main `851bb6d`)
| check | result |
|---|---|
| `mvn test` | 57 tests, 2 failures: `CompilationTest`'s IR budgets for `Query::sql`. large-function.php is at 53,750 against a budget of 52,000 (main: 51,806), and query-builder.php at 24,286 against 24,000 (main: 23,574). Everything else passes. The budgets are left as they are: raising them is the owner's call. |
| smoke | 199 of 205. The same 6 fail on main with identical diffs (closure-debug, closure-scope, lazy-objects, named-args-assert, named-args-fiber, property-hooks). |
| php-src `.phpt` | 5,559 strict passes against main's 5,561. The difference is only `random_int`/`str_shuffle` tests: 2 `#[\Deprecated]` constant tests whose `SUFFIX` comes from `random_int`, and `str_shuffle.php`. `generators/bug71441.php` flips the other way. |
| Gate 1 checksums | all 7 kernels equal PHP 8.5's and main's |
| Gate 2 pages | 19 of 21 equal PHP 8.5's (the two others: the oEmbed link, as on main) |
| compilation diagnosis | not run |

The first version of the prototype compiled `Query::sql` to 24,996 nodes (+6.0%). Most of that came from two places.
`PhpRef.set` inlined two decrements with their event branches. `PhpArray.removeHolder` had a second boundary call.
Putting `PhpRef.set`'s object counts out of line, giving each decrement one boundary call, and merging
`removeHolder`'s two last-holder walks brought it to 24,286 (+3.0%). The translator's StoreTemp/Disown/Own wrappers
cost nothing measurable there: turning them off left the IR unchanged.

## A/B against main
Builds: `target/classes-base` (`851bb6d`) and `target/classes-rc` (`697e6bc`). Every timed run held the CPU lock and
a memory-lock reservation (`bench/destructors/gate1.sh`, `gate2.sh`). Another session's Gate 1 A/B
(`wordpress-destructors-b`) took turns with these runs on the lock.

### Gate 1: within the threshold (≤3%)
`bench/destructors/gate1.sh 4 400 base rc`: 4 holds of 2 rounds (8 rounds), 400 iterations, interleaved, the order
reversed every round. Each value is the median per-round ratio rc/base of the steady state (last quarter), with the
IQR over rounds:

| hooks | escape | slugs | wpautop | wptexturize | blocks | kses | peak memory |
|---|---|---|---|---|---|---|---|
| 0.96 (0.92–1.11) | 1.01 (0.87–1.05) | 0.98 (0.92–1.04) | 1.01 (0.98–1.07) | 1.01 (0.97–1.05) | 1.03 (0.99–1.17) | 1.00 (0.97–1.07) | 0.98 (0.97–1.03) |

Medians of the time per iteration (ms):

| build | hooks | escape | slugs | wpautop | wptexturize | blocks | kses | peak memory MB |
|---|---|---|---|---|---|---|---|---|
| base | 16.07 | 2.24 | 1.61 | 4.94 | 4.06 | 6.25 | 29.48 | 1,509 |
| rc | 16.89 | 2.27 | 1.64 | 5.06 | 3.92 | 6.46 | 30.86 | 1,538 |

- The per-round ratios are at or under 1.03 for every kernel; blocks, the kernel with 5,517 zeros, is the highest.
- The holds differ a lot: hold 1 had blocks at 1.23 and slugs at 1.15, hold 3 had every kernel at 0.92–1.01. The
  IQRs (±5–15%) are wider than the threshold, so this says "no cost Gate 1 can resolve", not "≤3% proven". The
  medians of the raw times differ by up to +5% (hooks, kses) because the holds' host load differed, and the time
  medians mix holds.

### IR after partial evaluation: over the threshold (≤8%)
The Gate 2 servers' compilation logs, compared with `bench/bc-compile-cost/compare.py` per round (first compilation
of each PHP function both builds compiled). The four rounds give the same ratios to within 0.01:

| tier | functions | IR after PE: median / total | IR after compilation: median / total | code size: median / total |
|---|---|---|---|---|
| 1 | ~2,400 | **1.06 / 1.10** | 1.04 / 1.08 | 1.03 / 1.08 |
| 2 | ~625 | **1.12 / 1.06** | 1.08 / 1.03 | 1.08 / 1.03 |

- The largest functions grow the most at tier 1. `WP_Theme_JSON::sanitize` goes from 39,224 to 45,416 (+16%),
  `get_block_nodes` +12%, and `WP_Term_Query::get_terms` +12%.
- The compile time ratios follow the host's load (tier 1 total 1.00–1.29 over the rounds), so they aren't usable.
- The same 2 compilations fail as on main (code too large: `WP_Query::get_posts` and wp-admin's `menu.php`).
- Invalidations and deoptimizations: 18–21 per server for each build.

### Gate 2 warm CPU: over the threshold in the quiet rounds (≤5%)
`bench/destructors/gate2.sh g2`: paced visits from a cold server, 200 visits, warm = visits 151–200, the server on
CPUs 14–21, the database on 22–23. The site is a fresh `wp core install` (Hello world and the sample page) in the
stack's own database. gate2-mariadb's `wordpress` database, which native.sh seeds from, is empty now, and it
belongs to another session.

Six rounds, interleaved and the order reversed every round. Round 1's files were overwritten by round 2 (a naming
bug in the script, fixed since); its compile logs survive. That leaves 5 paired rounds:

| round | host % base/rc | warm CPU-s per visit, base | rc | rc/base | warm wait s, base | rc | rc/base | cold CPU-s, base | rc |
|---|---|---|---|---|---|---|---|---|---|
| 2 | 54/68 | 1.037 | 1.419 | 1.370 | 0.528 | 0.689 | 1.305 | 71.8 | 77.7 |
| 3 | 59/44 | 1.055 | 0.906 | 0.859 | 0.523 | 0.439 | 0.840 | 75.8 | 64.5 |
| 4 | 34/36 | 0.892 | 0.904 | 1.014 | 0.453 | 0.440 | 0.971 | 42.4 | 46.0 |
| 5 | 37/31 | 0.829 | 0.948 | 1.144 | 0.420 | 0.453 | 1.078 | 48.8 | 41.6 |
| 6 | 29/43 | 0.850 | 0.961 | 1.131 | 0.423 | 0.459 | 1.086 | 49.1 | 43.8 |

- **Warm CPU per visit, median over the 5 rounds: 1.13 (IQR 0.94–1.26). Warm wait: 1.08 (IQR 0.91–1.20).**
- Rounds 2 and 3 ran with the host at 44–68% (this session's phpt runs on CPUs 0–11, and other sessions) and
  point both ways.
- The three quiet rounds (host 29–43%) give +1.4%, +14.4% and +13.1% warm CPU. That is over the 5% threshold. Five
  rounds are too few to pin the size down.
- Cold visit: 46.0 CPU-s against 49.1 (median), no cost visible.
- memory.peak warm: 1,702 MB against 1,684 (+1%).

### Not measured
- WooCommerce (orders/s, memory peak, whether the compile queue settles).
- Gate 2's CPU per thread class (no `threadcpu.py` sampler beside these runs).
- The compilation diagnosis (`tests/gate2/compile-diag.sh`).
- Item 3: the census oracle and the TLA+ extension.

## What the numbers say
Two thresholds are exceeded:
- IR after PE: tier 1 total +10%, tier 2 median +12%.
- Gate 2's warm CPU: median +13% over 5 rounds, +13–14% in two of the three quiet rounds.

Gate 1 shows no cost beyond its noise. By destructors.md, P2 should be measured before deciding.

Where the IR goes, and the next things to try:
- **Per-site bits are missing where calls end.** Each call's result and each decrement still compile an `instanceof
  PhpObject` and a field update. `callResult`, `BindParameter` and the epilogue could get seen bits like `Writes`.
- **Deopt slow paths.** destructors.md's "transferToInterpreterAndInvalidate until a site first takes them" for the
  zero and possible-root branches wasn't tried. Item 1 says to keep them as boundary calls for this measurement.
- **P2's borrowed parameters and moves** remove whole increment/decrement pairs.
