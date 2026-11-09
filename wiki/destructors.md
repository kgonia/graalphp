# Destructors exactly as php-src (D17)

Issue [005](../issues/005-destructors.md) holds php-src's rules and the first options (A–E). This page records the
2026-11-03 analysis that followed the owner's decision against any deviation. Three independent analyses, given the same
brief and no hypothesis, reached the same conclusion: Codex CLI (gpt-6-astra, xhigh), Fable (xhigh) and Opus
(xhigh). Opus's corrections and additions are marked *(Opus)*.

## What has to be reproduced
Beyond issue 005's three moments (refcount zero, cycle collector, shutdown), these were probed on PHP 8.5
(`bench/gate1/php.sh`):

| Probe | php-src | What it binds |
|---|---|---|
| `$x = new A; f($x, $x = null);` | `[in f] ~x [after f]` | SEND_VAR's copy holds the object for the whole call |
| `function f($o){ $l = new A('l'); } f(new A('arg'));` | `~arg ~l` | the callee frees its CVs in CV order (parameters first) before returning; "the caller frees the temporary after the call" would give `~l ~arg` |
| `(new A('obj'))->m()` with a local in `m` | `~l-in-m ~obj` | `$this` is released after the CVs |
| `function r(){ $k = new A; $ret = new A; return $ret; }` | `~k [after r] ~ret [unset]` | the return value holds its own count before the epilogue frees the CVs |
| a local in a function that throws, caught outside | `~local [catch]` | destructors run during unwinding, before the `catch` (`cleanup_live_vars`: CVs and live temporaries) |
| an object passed to a thrower, the exception kept in `$e` | `~traced` only at `unset($e)` | the trace holds arguments (`zend.exception_ignore_args=0` in CLI, 1 in production ini) |
| `global $g` in a function | `$g` skipped in shutdown step 3a | a global that ever became a reference is skipped even with refcount 1; every global is a `PhpRef` here, so a sticky "is a PHP reference" bit is needed |
| CVs declared in dead code, `$GLOBALS['dyn']` from a function, `unset` then reassign | `~dyn ~third ~first2 ~second` | the symbol table's order is the file's first lexical mention of each CV (dead code too), attached at the file's start; a dynamic global appends; unset and reassign keeps the position. `PhpContext.globals` (a `HashMap`) must become an ordered table with attach semantics |
| cycles a↔b, c↔c; `gc_collect_cycles()` | `~a ~b ~c`, returns 3, `runs=2` | destructors in root-buffer order; GC reruns after destructors (`should_rerun_gc`) |
| `unset($tmp); $late = new A` | `spl_object_id($late) == 3` | handles are reused (LIFO); shutdown's step 3b walks handles, not creation order. `nextObjectId()` is not enough |

php-src also does these:
- **Resurrection:** the refcount is set to 1 around the destructor; an object alive after its destructor stays alive and its destructor doesn't run again.
- **Exceptions:** an exception from a destructor is chained as `previous`.
- **Constructors:** an object whose constructor throws is marked destructed without running `__destruct`.
- **Private destructors:** a private `__destruct` called from outside its scope gives a warning.
- **Weak references:** WeakMap and WeakReference are notified after the destructor, unless the object was resurrected.

*(Opus)* Four more facts from probes on PHP 8.5.12 in the reference image:

1. **Cycle collection is not one semantics in php-src.** With an explicit `gc_collect_cycles()`, the same script
   prints `~a ~b` under OPcache (the image's default, `opcache.enable_cli=On`) and `~b ~a` without it or with
   `opcache.optimization_level=0`. The optimizer removes a pure call `f($b)` that would have put `$b` in the root
   buffer. The number of roots after the same code also differs: 1/1/1/2/3 with OPcache or JIT, 1/2/3/4/5 without
   OPcache, 1/1/2/3/4 at level 0.
2. **php-src makes an object a possible root often:** after every constructor, after every `$cv->m()`, and at
   `return $cv` (a move plus `gc_possible_root` in `ZEND_RETURN`). A skipped increment/decrement pair must still
   emit that event, or the buffer, and with it the collector's moment and order, drifts.
3. **On Gate 1's kernels php-src never runs the collector:** 0 runs per iteration, at most 59 roots, handles
   staying at 16. Gate 1 measures only the counting.
4. **`WP_HTML_Processor` is a cycle from birth:** its constructor stores a closure over `$this` in a property
   (`class-wp-html-processor.php:439`). A dead processor's tokens are destroyed only by the collector or at
   shutdown's step 3b; tokens popped while it runs die at zero as usual.

## Why there is no shortcut
Every container must be counted from its creation, because any container can later receive a destructible object:
```php
$box = new stdClass; $alias = $box; unset($alias);   // rc($box) == 1, counted by nobody
$box->d = new A();                                    // only now does $box hold a destructible object
unset($box);                                          // php-src: ~A here; without rc($box) nobody knows it's zero
```
Rebuilding the count when `$box` becomes "tainted" means counting every slot that points at it: a heap walk on every
taint (in WordPress, every `WP_HTML_Token` put into a fresh array).

The same argument rules out each cheaper scheme:

| Scheme | Why it isn't exact |
|---|---|
| A Truffle `Assumption` "no class with `__destruct` yet", with counts rebuilt by a census when the first one loads | WordPress declares `WP_HTML_Token` and `WP_Secret` in `wp-settings.php` (lines 287, 325), so the assumption falls in the first millisecond of every boot *(Opus)*. It isn't exact anyway: a garbage cycle that still points at a live object is invisible to a census from the roots, and the JVM may already have collected it. Counterexample: `$u->self = $u; $u->a = $a; unset($u);`, then a destructor class autoloads, `$a->child = new D; unset($a)`. The rebuilt count is too low, so `D` is destroyed early. Closures must be counted from the start anyway (tla-cow F3: a closure's death changes array copy-on-write), and WordPress makes closures at once. Invalidation on a server would mean a second full warm-up mid-traffic. |
| Counting only objects that can reach a destructible one | The taint arrives after the fact (above) |
| Deferred or coalesced RC (Deutsch–Bobrow ZCT, Levanoni–Petrank, biased RC, RC Immix) | They move zero detection to a safepoint or epoch. A ZCT resolved at each release by scanning frames would be exact, but costs O(depth × locals) per candidate, and reading a compiled activation's locals (`FrameInstance.getFrame`) rebuilds the frame from deoptimization metadata. Biased RC solves cross-thread atomicity, which values here don't need. |
| The JVM's GC (Cleaner, PhantomReference, G1/ZGC barriers, JVMTI or native-image heap walks) | `unset($x); echo "after";` already fails: the timing is the GC's, on another thread, without `$this`. Barriers have no API, and heap walks answer reachability, not counts. |
| Escape analysis in the translator | `$this` escapes into every constructor and method; Graal's PEA already covers the virtualized cases |

Prior art agrees:
- **HHVM** kept eager refcounting and added a JIT pass (`refcount-opts`: lower bounds, `DecRefNZ`, no IncRef moved past code that can run a destructor). Hack later removed destructors from the language to relax it.
- **Swift** spent years of ARC work (+0/+1 conventions, OSSA) to cut the overhead.
- **PyPy and GraalPy** document `__del__` as nondeterministic.
- **PeachPie, Quercus and JPHP** don't destroy objects at once, which is the deviation D17 rules out.

## Design (recommended: P1, then P2 if needed)
**P1: exact reference counts with php-src's ownership discipline, specialized per site.**
- **Fields:** `int rc` and `int gcInfo` (root-buffer index and flags such as `DESTRUCTOR_CALLED`) on `PhpObject`.
- **Exact counts:** `PhpArray.holders` and `PhpRef.bindings` become exact, no longer "may overestimate". A cycle collector over overestimated counts finds no garbage. Closures (`captured`, `boundThis`) and generator frames are containers that cascade.
- **Ownership in `BytecodeTranslator`, as php-src's TMP/CV:**
  - the result of `new`, a call, `clone` or an array literal is an owning temporary (+1);
  - reading a CV, a property or an element borrows (0);
  - a copy into an owning position increments (ASSIGN from a CV, SEND_VAR, RETURN of a CV, a literal's element, `use`); from a temporary it moves;
  - a discarded temporary is freed.
- **Owning temporaries live in hidden locals**, so the exceptional epilogue (`Unwind`) frees them as `cleanup_live_vars` does. They are freed already on entry to a `catch` or `finally` of their `try`, in php-src's order: first the arguments of unfinished calls, then live ranges (`~tmp-arg caught`) *(Opus)*.
- **Epilogue order:** CVs in php-src's order, then `$this`, then the return conversion. Slots are numbered like php-src's CVs.
- **Per-site specialization, as today:**
  - `Locals.Writes.seen` gains object bits, and `releasedVariables` a released-object bit. A site that only saw strings compiles no object path; one deoptimization at the first object.
  - The decrement fast path is `if (--o.rc == 0) destroy(o); else if (o.gcInfo == 0) possibleRoot(o);`. Both branches are `transferToInterpreterAndInvalidate` until a site first takes them, then `@TruffleBoundary` *(Opus)*: a branch ending in a deopt doesn't kill the memory state at the merge, a branch with a call does.
  - Where the count is known to be >= 2, the decrement is HHVM's `DecRefNZ`: no zero test, no slow path.
  - `$this->m()` isn't counted, as in php-src (op1 UNUSED; 7,965 sites in WordPress). `$cv->m()` is skipped when the CV isn't a `PhpRef` at run time and the function has no dynamic scope. Each skipped pair still emits the possible-root event.
  - Arrays gain a `holdsCounted` bit, so an array of scalars isn't walked at death. This also fixes F4: nested arrays are released at their last holder.
- **Object store:** a free list of handles, LIFO like `EG(objects_store)`. Only objects of classes with `__destruct` and generators go into the handle table that step 3b walks. Every other object gets just a handle number, so `new` doesn't make it escape and PEA can still virtualize it *(Opus)*. A table entry is removed at zero and the JVM collects.
- **Shutdown:**
  - ordered globals with attach semantics and the sticky reference bit;
  - step 3a as `do { reverse walk } while (the table changed)`;
  - step 3b over handles;
  - after a fatal error or `exit` in a destructor, the rest are only marked;
  - `PhpContext.destroyGenerators` becomes a case of 3b.
- **Cycle collector:** a port of `zend_gc.c`.
  - The root buffer and its threshold: 10,001, adapted by 10,000 when fewer than 100 are collected.
  - `gc_protected` and the coloring.
  - Destructors in buffer order, then the rerun.
  - `gc_status`.
  - A buffer entry is removed when its object is freed.
  - Children are visited in `get_gc` order: properties in shape order, elements in array order, closures as `zend_closure_get_gc`.
  - Candidates without destructors can't be skipped: they fill the buffer and so trigger earlier cycles' destructors.
- **Built-ins that hold values must count them.** ArrayObject and SPL, WeakMap, `Closure::bind`, `func_get_args`, `debug_backtrace`, exception traces, error, output and autoload handlers, `register_shutdown_function`, class constants, enums, stream contexts, curl handles.

**Why skipping a pair is exact** *(Opus)*: `rc_ours(v) = rc_php(v) - e(v)`, with e(v) the number of active
skipped pairs. Each skipped pair is supported by a counted slot that nothing can change during its interval: the
suspended caller's non-`PhpRef` CV, or `$this`. During the interval `rc_ours >= 1` and `rc_php >= 2`; outside it the
counts are equal, so zeros fall in the same operations. Copy-on-write could differ only at rc_php=2, rc_ours=1,
where the other holder is the unchangeable support. A `PhpRef` is never borrowed. Trial deletion sees the support
as an external reference.

**P2: ownership optimizations in the translator**, after P1 is measured:
- **`return $local` moves** when the local is dead and the function has no dynamic scope (`compact`, `extract`, `$$`, `get_defined_vars`, `include`, `eval`).
- **Borrowed read-only parameters:** a known callee that never writes, unsets or references a parameter, called with a local held directly (not a `PhpRef`, checked at run time), skips the increment and the epilogue's decrement. The same applies to `$this` from a direct local.
- **Why it is exact:** the caller's frame is suspended for the whole call, so nobody can release its slot, and the count stays ≥ 1.
- **Arrays benefit too:** argument pairs are a large part of today's 2–5% holder cost.
- **Model first:** `CowRefs.tla` must be extended before this lands.

## Expected cost
| Source | Steady state | IR growth |
|---|---|---|
| Fable (P1) | +2–4% over today: objects counted at the same sites as arrays, F4 +0.5–1%, the possible-root branch +~0.5%, the old value read on property stores +~0.5% | +3–8% (5–8 nodes per counted store, ~300–400 nodes on a 2–10k-node WordPress function) |
| Codex (ownership + moves) | "a few percent", no basis to promise below the naive 3–10% | the main risk; keep slow paths out of line |
| Opus (P1 with skipped pairs and deopt slow paths) | Gate 1 +0.5–2% (hooks +1–2%), Gate 2 warm +2–4%, WooCommerce +3–5%; interpreter and cold code +1–3%; +8 B per object, +4 B per array (about +1–2% live heap). HHVM's PLDI'18 paper: turning off its refcount elimination costs 3.4% at Facebook, a lower bound of RC's cost there, with strings counted too | +2–5%; the risk is an inlining cliff (20% on hooks in `gate-1.md`) |
| Naive estimate (issue 005, option A) | 3–10% | not estimated |

Graal won't pair the increments and decrements by itself: after a branch that calls `destroy`, the memory state is
killed. PEA removes counts only on virtualized values: array literals in inlined calls, rarely objects, because
`$this` escapes into every constructor.

## Prototype and thresholds
1. **Counting only, no action at zero.**
   - `rc` on `PhpObject`, with increments and decrements on the bytecode store paths (`Locals.Writes`, `releaseVariables`, `PropSite` with the old value read, `PhpArray.countIn/countOut`, SEND_VAR on object arguments, `$this` in the epilogue, a full `ReleaseValue` after expression statements, return by increment or move).
   - The decrement keeps its zero and possible-root branches, to empty stubs behind `@TruffleBoundary`, or the measurement understates.
   - Owning temporaries go in hidden locals, at least for call arguments.
   - The F4 fix with `holdsCounted`.
2. **A/B on CPUs 14–21**, interleaved, with memory.peak:
   - Gate 1: all kernels, 8 rounds.
   - Gate 2: cold and warm (visits 151–200), CPU per thread class, tier 1 and tier 2 compile times, IR after PE on the same functions (`bench/bc-compile-cost`).
   - WooCommerce: orders/s, memory peak, whether the compile queue settles.

   Thresholds: ≤3% on the Gate 1 median, ≤5% warm CPU on Gate 2, ≤8% IR. Over them, measure P2 before deciding.

   **Measured (2026-11-06, [destructors-prototype.md](destructors-prototype.md)):**
   - Gate 1 is within its threshold: per-round ratios 0.96–1.03, IQRs ±5–15%.
   - IR after PE is over its threshold: tier 1 median 1.06 and total 1.10, tier 2 median 1.12 and total 1.06.
   - Gate 2's warm CPU is over its threshold: median 1.13 over 5 rounds, 1.01–1.14 in the 3 quiet rounds.
   - WooCommerce wasn't measured.
3. **In parallel:**
   - a census oracle for test mode: at `gc_collect_cycles()` and before shutdown step 3a, count each value's in-degree from the roots (frames via `iterateFrames`, generators, globals, statics, constants, built-ins' state) and compare it with `rc`;
   - `CowRefs.tla` extended with objects and destruction events.

## The owner's answers (2026-11-03, D18)
1. **Garbage cycles:** destructors of garbage cycles run at an explicit `gc_collect_cycles()` and at shutdown, as on
   php-src. When the automatic collector triggers and the order within one run are internal: php-src varies them
   itself (OPcache), so the cheapest policy that php-src could also produce wins.
2. **`debug_zval_dump` refcounts and `gc_status()` numbers need not match.** Every skip, move or borrow that no
   program can observe is allowed.
3. **`zend.exception_ignore_args`** comes from the ini, as on PHP (production 1, CLI 0); both work.
4. **Budget:** not fixed in advance. The counting-only prototype gives the numbers, and the decision follows them.
   If it is expensive, P2 (borrowed parameters) is measured first.
5. **Moves and borrows** are internal: whatever is fastest and exact by the argument above.
6. **Values shared between contexts** (enums, constants holding objects) are uncounted, like `FROZEN` arrays.
7. **Non-atomic counters:** yes. A context runs on one thread at a time, and shared values are uncounted. Test mode
   asserts that only the context's owning thread changes a count.

**Why only some objects can't be counted:** memory is still the JVM's GC for every object; the count only decides
*when* a destructor runs. But any container can receive a destructible object later (`$box`, above), and PHP's
properties are mostly untyped, so no container can be left uncounted.

## Status (2026-11-06, updated 2026-11-08)
**Where the code is (2026-11-08):**

| Worktree | Branch | State |
|---|---|---|
| `../wordpress-destructors` | `destructors-merged` | **The current work**, pushed (last commit 1183d27), with its PR. It replaces `destructors` (PR #54) and `destructors-b` (PR #53). |
| `../wordpress-destructors-b` | `destructors-b` | Prototype B (PR #53). An experiment, not to be merged. |

`destructors-merged` is prototype A (counts on the store paths, owning temporaries in hidden locals) plus fixes,
with B's profiled releases (`Refcount.Releases`) and `truffle_refcount` ported, then the audit, the operand
releases and the static release diff below.

The untracked `array_count_file` and `test.php` in that worktree are strays, not part of it.

The differential fuzzer is in `tests/fuzz` (`gen.py`, `run.sh`, `compare.py`, `reduce.py`, `triage.py`). Its runs
go to `target/fuzz/`.

**Built:**
- Counts on every `PhpObject` (P1's store paths), including properties that built-ins set (`setProperty` and
  `initProperty` count since 2026-11-06), an exception in flight (`EG(exception)`'s count), and live temporaries
  released before a `catch` (`cleanup_live_vars`).
- destructors, on by default since 2026-11-09 (`-Dphp.rc.destructors=false` turns them off, only to measure their
  cost): at zero, `__destruct` runs once with the object held (resurrection possible), then
  the properties release what they hold (`zend_objects_store_del`, `zend_object_std_dtor`). `PhpArray` unlinks an
  element before releasing it (`zend_hash_del`).
- `-Dphp.rc.verify=true`: a reachability check at zero. It is **not** the census planned in step 3 above, which
  compares each value's in-degree with its count. It only finds counts that are too low (objects destroyed early)
  and can't see counts that are too high (objects destroyed late or never).
- A differential fuzzer (`tests/fuzz`): generated programs whose destructors print their labels, compared with php-src
  (OPcache off).

**Results:**

| Check | Result |
|---|---|
| Fuzzer, 1000 programs, all features | 915 match php-src |
| Fuzzer, 1000 programs, without closures | 984 match |
| Reachability check on Gate 2's WordPress (10 front-end pages, 16 wp-admin pages, REST writes; 6,356 zeros checked) | No early zero found |
| 2026-11-08, 1183d27: fuzzer seed 1000, 1000 programs, all features | 999 match, no early destructor |
| 2026-11-08, 1183d27: fixtures `tests/rcaudit/*` against PHP 8.5 with and without OPcache | all lines match one of the two (see the reference note below) |
| 2026-11-08, 1183d27: count audit on Gate 2's WordPress | no group left |
| 2026-11-09, c4b04cb (`deep` flag): fuzzer seed 1000, 1000 programs | 999 match; p1741 is issue 052 (not destructors, on main too) |
| 2026-11-09, c4b04cb: count audit and verify (every 20th zero) on Gate 2's WordPress | no group, 0 reports |

Performance against `main` hasn't been measured on this branch. `CompilationTest`'s IR budgets fail, as on
prototype A.

**Checks that don't need PHP (2026-11-08).** Comparing outputs with PHP only finds what a test happens to
print. Three checks don't depend on that:
1. **Verify at zero** (`-Dphp.rc.verify=true`, `php.rc.verify.every=n`). When an object's count reaches zero, the
   census looks for a counted holder that still reaches it (`Census.heldAtZero`). It reports `rc-verify: EARLY
   ... via PATH`. For it to be exact, a slot is cleared before its old value is released, at every
   release-then-clear site, as php-src does (`unset` was the last one). One exception is left: an argument moved
   into a call is still in the caller's hidden temporary, so a zero in the callee reports `via ... local N` with no
   variable name (issue 054). Every other report is a bug. Results: fuzzer seed 1000, every zero checked: 51
   reports on 1183d27 and on c4b04cb, all from moved arguments; Gate 2's WordPress, every 20th zero, 0 reports.
2. **php-src's rule per opcode.** A destructor runs where an opcode frees a TMP or VAR operand (FREE_OP), or
   where a moved one dies. The fixtures follow those rules rather than programs:
   - `tests/rcaudit/opcodes.php`: one case per opcode with a temporary operand, with markers before and after;
   - `sequence.php`: the free against the next thing the same expression does (a call that echoes).
   `bench/destructors/opcode_coverage.sh` lists the opcodes the fixtures run with a temporary operand. With
   `CORPUS=` it also lists the ones WordPress compiles to that no fixture covers. Both come from php-src's own
   opcode dump, checked against the 130 opcodes of `zend_vm_def.h` that take one.
   `bench/destructors/release_diff.py` checks the same rule on a whole corpus without running it. For each
   body it compares, temporary by temporary, the opcode that frees, moves or passes on each TMP/VAR in php-src's
   dump with the operation that releases it in our translator (`-Dphp.rc.releases=FILE` with
   `truffle_translate`). Results on 2026-11-08 (1183d27):

   | Corpus | Bodies | Temporaries aligned | Released as php-src | Elsewhere |
   |---|---:|---:|---:|---:|
   | Gate 2's WordPress | 14,826 | 101,443 | 93,914 | 1,202 |
   | Fixtures | 290 | 591 | 582 | 9 |

   831 of WordPress's 1,202 are a `sprintf` with a constant format, which php-src compiles to a rope. The diff
   checks where values are released, not the counts: `one(...[new D])` released at the right place still
   destroyed D early, because our argument list doesn't count what it unpacks. The fixtures and the verify at
   zero cover the counts.
3. **The count audit** (in-degree against count; item 1 below).

**Reference: PHP with or without OPcache.** The optimizer moves some frees. Of the fixtures' 147 lines, 3 differ
between the two, and `bench/destructors/fixtures.sh` reports each line against both:
- `(string) new D` echoed: `~z sz` without OPcache, `sz~z` with it;
- a match with only a default arm frees its subject first with OPcache;
- `(string) make() . f()`: the cast is folded into the concatenation.
The translator models OPcache's concatenation (the smoke test `concat-order` holds `__toString`'s order), so the
third case follows OPcache and the other two follow PHP without it. The fuzzer runs PHP without OPcache;
production and Gate 2's php-fpm run with it. **Which one is the reference is the owner's decision.**

**Work order.** Every known gap is required work (D17): a late destructor is as wrong as an early one. Details are in
issue [036](../issues/036-refcount-prototype-gaps.md).
1. **Done 2026-11-06 (065bf8f): the count audit** (in-degree against count, at the end and at sampled statement
   ends). On Gate 2's WordPress it finds 61 groups of late destructors and 14 of early ones; the table and causes
   are in issue 036, item 8. The order below now follows its findings: the early ones first (generators' counts
   reaching -1, closures' `$this` with the HTML API's tokens), then the late ones (temporary arrays and built-ins'
   result arrays, a temporary argument moved into the call, closures' captures).
2. **Done 2026-11-07 (70fd18e): temporary arrays, casts, and results of PHP code called from Java** (issue 036,
   items 2-2c). Gate 2's WordPress: no LATE, EARLY or FREED finding left. Left: a fresh object offsetGet or __get
   returns outside a built-in is released at the request's end.
   **Done 2026-11-07 (3bde5ae): arguments move into calls, operands are freed once used** (issue 036, item 2d).
   The audit samples statement ends only, so it missed both: an owning temporary lived to its statement's end
   (an `if`'s condition past the body), where php-src frees a TMP when its opcode is done (FREE_OP) and moves an
   argument into the call (SEND_VAL). Fixtures `tests/rcaudit/moves.php` and `operands.php` hold PHP 8.5's
   output and match it. Left: a TypeError's trace keeps the call's arguments while `zend.exception_ignore_args`
   is 0 (`moves.php` case 7); here they are freed before the catch.
   **Done 2026-11-08 (11fe68c): every operand freed at php-src's opcode** (see "Checks that don't need PHP"
   below). The new fixtures found 12 late releases and 1 early one:
   - late: `??`, a concatenation chain, match's subject, `?->`, `(expr)::class`/`::C`/`::$p`, a TMP array sent to
     a built-in (freed at the statement's end instead of the call's), `xor`, `&&`/`||` (freed after the other
     operand), an in-place intrinsic's argument, and a temporary's property read (`make()->obj->m()`: the
     container lived until the read's consumer, where php-src copies the value and frees the container at once);
   - early: `(array) f()`, where the cast returned the operand's array and freed its elements while `foreach`
     still iterated it. WordPress hit it in `get_post_class` (`foreach ((array) get_the_terms(...) as $term)`):
     the verify found a `WP_Term` freed while `$term` held it.
3. **Done 2026-11-06: closures counted** (issue 036, item 1). Fuzzer seed 1000, all features: 996 of 1000.
4. **Done 2026-11-06 for registered callbacks and generators' frames** (issue 036, item 4); SPL storage is PHP code
   and counts already. Left: curl and mysqli handles' callbacks, lazy objects' initializers.
5. **The fuzzer's remaining groups (2026-11-06, seed 1000, all features):** `$x = &$x` and the `array_shift` of an
   array are fixed (bind before release; a shifted or popped array keeps its elements). Left: p1741,
   `$v2[] = $v0` with both the same reference: php-src fetches the container for write before the value, so the
   array takes itself (a cycle, `*RECURSION*`); here the value is read first and the array separated. That is an
   evaluation-order difference, visible without destructors too.
6. **The cycle collector, the shutdown order (3a/3b), `WeakReference`/`WeakMap` cleared at zero.**
7. **Cost:** an A/B against the thresholds of step 2. The numbers choose P1's tuning or P2; they don't decide
   whether the work is done.
   **Measured 2026-11-07** (`base` = main 851bb6d; `dt` = 70fd18e; `mv` = 3bde5ae; destructors on unless said;
   CPUs 14-21 under the CPU lock, interleaved; the host was 25-60% busy with other sessions):
   - Gate 1 (`bench/destructors/gate1.sh 4 400`, 8 rounds, median ratio to base): counting only (`dt` without
     destructors) hooks 1.09, slugs 1.03, wpautop 1.18, wptexturize 1.34, blocks 1.15, kses 1.11; with
     destructors blocks 1.65, kses 1.23; `mv` hooks 1.15, slugs 1.27, wpautop 1.03, wptexturize 1.51, blocks
     1.65, kses 1.22. Peak memory 0.98-1.03. The prototype (697e6bc) was at most 1.03 everywhere.
   - Gate 2 (`gate2.sh g2 6 200 base mv`): warm CPU per visit 4.7x (IQR 4.6-5.2), warm wait 1.47x (1.37-1.53),
     memory.peak warm ~+10%. The warm CPU is the compiler: in the same time `mv` finishes 4,359 compilations
     against base's 8,750. IR after PE (tier 1, 2,385 functions both compiled): median 1.17, total 1.30;
     compile time 1.59x. `dt`'s first, partial run already had warm CPU 1.1-3.2 CPU-s against base's 0.86.
   - Pages: `te` (7bff781) and base give the same 18 of 21 pages as PHP 8.5 (3 differ for both).
   Every threshold is exceeded; the compiled size of the counting (statement-end and epilogue releases, the
   new argument and operand releases) is the first thing to shrink, then the zero path's work (blocks).
