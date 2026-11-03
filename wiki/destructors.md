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
3. **In parallel:**
   - a census oracle for test mode: at `gc_collect_cycles()` and before shutdown step 3a, count each value's in-degree from the roots (frames via `iterateFrames`, generators, globals, statics, constants, built-ins' state) and compare it with `rc`;
   - `CowRefs.tla` extended with objects and destruction events.

## Open questions for the owner
1. **Which php-src configuration is the reference for garbage cycles**, both the collector's moment and the order
   of destructors within a run, explicit `gc_collect_cycles()` included? OPcache's optimizer changes both
   *(Opus)*. Proposed: opcode semantics without the optimizer (`opcache.optimization_level=0`), with the
   optimizer's variation recorded as a property of php-src itself; reference outputs are generated at that
   setting (the reference image runs with `opcache.enable_cli=On`). Destruction at refcount zero isn't affected.
2. Must `debug_zval_dump`'s refcounts and `gc_status()`'s numbers match too? If yes, every skipped pair becomes
   visible and only the naive scheme (option A) remains.
3. Which `zend.exception_ignore_args` is the default: CLI's 0 or production's 1? It decides how long objects in
   exception traces live.
4. **The budget:** about 2–4% warm on Gate 2 and up to 5–8% IR for exactness (F2/F5 were accepted at 4%)?
5. Ownership moves at once and borrowed read-only parameters after the TLA+ model (Opus's proposal), or both after
   P1's result?
6. Values shared between contexts (enums, constants holding objects): uncounted like `FROZEN`, or counted per
   context?
