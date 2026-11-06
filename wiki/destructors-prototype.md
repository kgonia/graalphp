# Destructors: the counting-only prototype (roadmap step 5.1, 2026-11-06)

## Question
What does counting objects' references cost, before any destructor runs? This is item 1 and 2 of
[destructors.md](destructors.md), "Prototype and thresholds": `rc` on `PhpObject`, increments and decrements on the
bytecode store paths, the zero and possible-root branches to empty `@TruffleBoundary` stubs, owning temporaries of
call arguments in hidden locals, the F4 fix with `holdsCounted`. Thresholds: ≤3% on the Gate 1 median, ≤5% warm CPU
on Gate 2, ≤8% IR.

## Short answer
- **Gate 1 (8 rounds, all kernels): within 3% except kses, +6.0% (IQR 1.008-1.080).** The geometric mean of the
  seven kernels' ratios is 0.985. The host was loaded (13-60% CPU during the holds, another session's A/B
  interleaved), so the IQRs are wide (up to ±17% on hooks). Peak memory +2.4% (IQR 0.959-1.067). Every checksum
  equals PHP 8.5's.
- **IR after PE on Gate 1's kernels: mixed against the 8%.** Tier 1 (89 functions compiled by both): median +7%,
  total +12%. Tier 2 (64 functions; inlining differs): median +10%, total +4%. Compile time is not up (tier 1 total
  1.01, tier 2 total 0.89). The largest growths: `wptexturize` 15077 → 18107 (+20%), `apply_filters` 3727 → 4475
  (+20%), `wp_kses_hair` 2362 → 3083 (+31%), the hooks kernel's closure 2029 → 2329 (+15%).
- **CompilationTest's IR budgets fail twice:** `large-function.php` Query::sql 56980 against the budget 52000 (main
  51806, +10%), `query-builder.php` Query::sql 25372 against 24000 (main 23574, +7.6%).
- **Not measured: Gate 2 (cold, warm, CPU per thread class, compile times, IR) and WooCommerce.** This worktree has
  no Gate 2 or WooCommerce stack (seed SQL, docker), and the session's time went to Gate 1 and the IR first, as the
  brief asked. So the ≤5% warm CPU threshold is open.
- **Decision input:** kses and the IR are over their thresholds; destructors.md says "over them, measure P2 before
  deciding". Where the IR goes is not attributed yet (see "What's left").

## What the prototype counts (model)
`RefCounts` (runtime) holds the operations; `PhpObject` gains `int rc` and `int gcInfo`.
- **rc = the slots holding the object:** variables, parameters, properties, array elements, references (`PhpRef`),
  closure captures (`use`, `MakeClosure`). Temporaries are not counted: a value on the operand stack holds nothing.
  `php.rc.stats=true` prints the zeros, the possible roots and any count below zero at exit; `truffle_refcount()`
  (php.Debug) reads a count. `RefCountTest` checks 18 counts against php-src's (outside temporaries).
- **Stores:** `Locals.Writes` (new and old object bits in its `seen` profile), `releaseVariables` (a released-object
  bit, per-variable deopt profiles on the zero and root branches), `PropNode` (the old value read on writes, unset,
  make-ref, bind-ref), `RefWrites`, `Variables`, `PhpRef` (set, release, take back), `PhpArray.countIn/countOut`,
  `copyElement`, `pop`, `prependAll`, `refAtPosition`; `clone`, `unserialize`, promoted constructor parameters.
- **Return:** the returned value holds a count while the epilogue releases the variables, then moves out silently
  (`ReturnValue`, one specialization per object, array, other, so the release compiles once per kind).
- **A dropped temporary:** expression statements are wrapped in `DiscardValue`: an object at rc 0 goes to the zero
  stub, an array frees its elements if it holds counted ones.
- **Call arguments' owning temporaries** (a call's result, `new`, `clone`, a non-constant array literal) are stored in
  a reused pool of hidden locals and freed after the call (`FreeTemporary`); `Unwind` and
  `interceptInternalException` free them on an exception.
- **The receiver:** `$this->m()` is not counted (php-src's op1 UNUSED). Another receiver is released after the call
  at the call site (`MethodSite.countsReceiver`), since the callee's frame doesn't hold it.
- **Stubs:** `zero` marks the object destroyed (`gcInfo = DESTROYED`); `possibleRoot` marks it rooted, so later
  releases of a rooted object skip the stub, as php-src's buffered flag does. Both are `@TruffleBoundary`; each site
  enters them behind a profile (a deopt until the site first takes the branch).
- **F4 (arrays):** `PhpArray` gains `holdsCounted` (it holds arrays or objects whose counts it took) and
  `elementsReleased`. At its last holder an array releases its nested arrays and objects, at its next holder it takes
  them back; `countIn/countOut` are gated while released. `dropHolder` (a return, a pop) gives up a holder without the
  release; `freeTemporary` releases a dropped temporary's elements. `tests/smoke/nested-release.php` checks F4's
  trace and the other paths against PHP 8.5.

### Deviations from destructors.md's P1
- Temporaries are uncounted (moves are implicit); php-src counts them. Allowed by D18 answer 2 and 5.
- Closures (`PhpClosure`, not a `PhpObject`) and enum cases are not counted; generators' frames are not containers.
- Temporaries are not freed on entry to a `catch`/`finally` (only by `Unwind` and after the call); spread and named
  calls keep no temporaries.
- No possible roots for arrays; no root buffer (the stub only sets a flag).
- No built-in holds counts (ArrayObject, SPL, WeakMap, `func_get_args`, traces, handlers...). Objects passed to
  built-ins are not counted.
- No `DecRefNZ` and no skipped `$cv->m()` pairs (only `$this->m()`).

## Measurements
### Setup
- A = main `851bb6d` (target/classes-base), B = the prototype `0356dcb` (target/classes-proto). Class directories,
  run by `bin-php.sh` (`PHP_CLASSES`).
- Gate 1: `bench/destructors/runs.sh gate1 4 400 base proto`, 4 holds × 2 rounds (forward, then reversed), 400
  iterations, CPUs 14-21 under the CPU lock and a 5 GB memory reservation per hold. The median of the last iterations
  per round; ratios are medians of per-round ratios, with their IQR.
- IR: `runs.sh ir 400 base proto`, one run each with `TraceCompilationDetails` on CPUs 6-13 (not timed),
  `bench/bc-compile-cost/compare.py`.

### Gate 1 (8 rounds per variant)
| kernel | base ms | proto ms | proto / base | checksums = PHP |
|---|---|---|---|---|
| hooks | 15.47 (12.48-19.58) | 15.04 (14.25-17.03) | 0.986 (IQR 0.914-1.166) | yes |
| escape | 2.06 (1.57-2.28) | 1.90 (1.75-2.28) | 0.965 (IQR 0.846-1.133) | yes |
| slugs | 1.53 (1.12-1.84) | 1.47 (1.21-1.80) | 0.959 (IQR 0.816-1.053) | yes |
| wpautop | 4.74 (3.66-6.22) | 4.63 (3.92-6.10) | 0.946 (IQR 0.841-1.007) | yes |
| wptexturize | 3.67 (2.95-4.62) | 3.63 (3.20-3.95) | 0.982 (IQR 0.888-1.109) | yes |
| blocks | 6.25 (5.66-6.78) | 6.43 (4.91-6.84) | 1.004 (IQR 0.867-1.067) | yes |
| kses | 29.94 (24.91-37.72) | 30.90 (26.50-38.47) | 1.060 (IQR 1.008-1.080) | yes |
| peak memory MB | 1529 (1435-1723) | 1563 (1455-1647) | 1.024 (IQR 0.959-1.067) | |

Per hold (2 rounds each), proto / base: kses 1.05, 1.06, 1.15, 1.04; hooks 0.83, 0.99, 1.04, 1.11. Host CPU at the
holds' start: 25%, 60%, 13%, 54%. Warm-up: blocks iterations 2-3 and slugs 2-3 are slower on the prototype
(141 against 74 ms, 31 against 10 ms), kses iterations 3-5 faster; from iteration 50 the curves match.

### IR after partial evaluation (Gate 1's kernels, first compilation of each function)
| tier | functions | IR after PE, median | total | IR after, median / total | code total | compile ms total |
|---|---|---|---|---|---|---|
| 1 | 89 | 1.07 | 1.12 | 1.07 / 1.08 | 1.08 | 1.01 |
| 2 | 64 | 1.10 | 1.04 | 1.06 / 0.98 | 0.97 | 0.89 |

An earlier build (`91fd446`, before `ReturnValue` was split per kind) measured tier 1 median 1.09, total 1.15; the
split took most of the return's growth off. Kses's checksum is the same in both runs (451326:1285e0d1).

## Checks
- `mvn test`: 58 tests, 2 failures, both CompilationTest's IR budgets (above). RefCountTest passes.
- Smoke (`tests/smoke/run.sh`, 206 tests incl. nested-release): 200 pass. The 6 failures — closure-debug,
  closure-scope (Windows paths), lazy-objects (object handles), named-args-assert, named-args-fiber, property-hooks —
  fail on main too (PHP_CLASSES=target/classes-base).
- phpt (php-src's tests, 7341 cases, the same oracle): 5561 strict passes on both. Differences: on the prototype
  `Zend/tests/generators/bug71441.php` overflowed the stack in the full run (a 2000-term `eval`'d expression); run
  alone in the same batch launcher it passes on both builds, so it sits at the stack's edge and the prototype's
  frames moved it over in the full run. `ext/standard/tests/strings/str_shuffle.php` fails on main and passes on
  the prototype (random).

## What's left
- Gate 2 and WooCommerce A/B (the ≤5% warm CPU threshold).
- Attributing the IR: which of the store paths, DiscardValue, FreeTemporary, the receiver release or the array
  holder changes grows `wptexturize` and `apply_filters`. Knobs per part (system properties read once) gave the same
  numbers in a first try, so they did not switch what they should; not resolved.
- A quiet-host rerun of Gate 1 for kses (+6%, IQR 1.008-1.080).
- Item 3 (the census oracle, `CowRefs.tla` with objects) and P2.
