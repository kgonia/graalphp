# GraalVM 25.5: what is worth taking (2026-11-04)

GraalVM 25.5.5.0.3 came out on 2026-11-03, on JDK 25.0.5.0.3 (innovation release; tag `vm-25.5.5.0.3`, branch
`release/graal-vm/25.5`). We are on 25.4.4.1.1 with a patched DSL processor. This page lists what 25.5 changes for
Truffle PHP, what each item needs from us, and in what order to try it. Nothing here is measured yet.

Sources: the [25.5 release notes](https://www.graalvm.org/release-notes/25.5/),
[AuxiliaryEngineCaching.md](https://github.com/oracle/graal/blob/master/truffle/docs/AuxiliaryEngineCaching.md),
and the upstream repository's tags and PRs (checked with `gh api`).

## Summary

| item | what it gives us | where | cost for us | when |
|---|---|---|---|---|
| **The upgrade itself** | GR-79787 in a release; 25.5's compiler optimizations | JVM and native | pom version, the processor question below, `ClearLocal` | first, its own small step |
| **Auxiliary engine cache + auxiliary images** | the cold visit starts from a training run's bytecode, compiled code and a pre-initialized context | **native image only** | `prepareForAOT`, audit of context-specific data in nodes, the image build | step 7, with a feasibility probe before |
| **Unboxed calling convention for compiled Truffle→Truffle calls** | cheaper calls between compiled PHP functions | native image | none (automatic) | measured in step 7 |
| **Tail duplication of threaded bytecode handlers** | possibly a faster native interpreter (we already use `enableTailCallHandlers`) | native image | one attribute | experiment in step 7 |
| **`GenerateBytecode.unwindExceptions`** | cleanup that runs `finally` but skips guest `catch`, keeping the exception across suspend/resume | JVM and native | translator change | check in step 5 (generators destroyed while suspended) |
| Graal compiler: loop unrolling, PRE scheduling, write sinking, deduplication, guard grouping, ... | possibly faster steady state; risk: more compile time on a compile-bound workload | JVM (libgraal) and native | none (on by default, each has a switch) | measured in the upgrade's A/B |

Not relevant to us: `CompilerDirectives.mergeExplodeKey()` (we have no `@ExplodeLoop(MERGE_EXPLODE)`), GraalJS and
GraalPy items, the bundle format, `TruffleFile` channels (`flock()` already works on our own streams).

Minor, only if a profile points there: `DynamicObject.SetAllPropertyFlagsNode` (flags on several properties at once)
and `PropertyGetter.getShape()`. `PhpObject` is a `DynamicObject` (`InstantiateNode`, `PropNode`), so they could
serve property flags (readonly, hooks), but nothing today asks for them.

## 1. The upgrade: 25.4.4.1.1 → 25.5.5.0.3
- **GR-79787 is in 25.5.** The split-copy fix (stale state bits trusted by `@Fallback`; [bytecode-dsl.md](bytecode-dsl.md))
  was committed to master as `9a8b5489c5` on 2026-09-23; that commit is an ancestor of `vm-25.5.5.0.3` and not of
  `vm-25.4.4.1.1`.
- **oracle/graal#14872 (GR-81207, `@ReportPolymorphism` on operations) is still open**, so it isn't in 25.5.
  We don't put `@ReportPolymorphism` on any Bytecode DSL operation (the working patterns are `@Cached` child nodes
  and manual `reportPolymorphicSpecialize()`), so **the stock 25.5 processor may be enough** and
  `truffle-dsl-processor/build.sh` could go. That is the first thing the upgrade must prove: build with the stock
  processor, then the split-heavy tests (instanceof, ToBool, ReadElement, isset, foreach on split copies). If
  something still needs #14872, the patched processor is rebuilt on 25.5 instead.
- **`ClearLocal` is now a built-in that can't be turned off.** We have no custom operation of that name; the
  build will confirm.
- **The compiler's new optimizations are on by default.** Our warm cost is dominated by compilation (the Truffle
  compiler threads, libgraal's heap bursts behind memory.peak; [woocommerce.md](woocommerce.md)), so the A/B must
  report compile CPU and memory.peak, not just time. Each optimization has a switch
  (`-Djdk.graal.AggressivePartialUnroll=false`, `...PartialRedundancyScheduling=false`, `...OptDeDuplication=false`,
  ...) if one of them turns out to cost more than it gives.
- **The A/B:** 25.4 against 25.5 on the same commit: Gate 1, Gate 2 (warm, cold, CPU per thread class), WooCommerce,
  with memory.peak; interleaved, order reversed each round.

## 2. Auxiliary engine cache and auxiliary images (the cold visit's main lever)
**What it is.** A training run stores the engine (parsed and translated code, the machine code compiled at run
time, and a pre-initialized context) into an *auxiliary image* next to the native executable. Later runs load it
"almost instantaneously". Until 25.5 the engine cache was in Oracle GraalVM only; 25.5 brings it to Community Edition,
and Native Image's new auxiliary images are the persistence mechanism under it.

**Why it fits us.** The cold visit is translation, interpretation of cold code and the compile queue
([cold visit numbers in bytecode-dsl.md](bytecode-dsl.md), H9/H10). The cache removes all three without changing
WordPress. Our HTTP server already has the shape the cache wants: one shared `Engine`, a new `Context` per request
(`HttpMain`), and `PhpLanguage` declares `ContextPolicy.SHARED`.

**What it does not do.** It persists code only, "no application context-specific data like global variables". The
state WordPress builds during its bootstrap (hooks, options, globals) is not snapshotted; each request still runs
the bootstrap, only on warm code. Snapshotting that state is the separate question of
[bootstrap-share.md](bootstrap-share.md).

**Limits.**
- **Native image only.** HotSpot can't store the cache to disk (only in-memory debug options), so the JVM's cold
  visit needs other fixes (the cold-visit diagnosis, the JDK 25 AOT cache experiment).
- The auxiliary image works only with the exact native executable that wrote it: it is regenerated with every build.
- One auxiliary image per isolate; it can't be unloaded yet.
- Storing compiles code and takes time; that is the training run's cost, not the request's.

**What it needs from us.**
1. `RootNode.prepareForAOT()` on our roots, for the `aot` compile policy (forced compilation without profiles).
   The default policy `hot` keeps what the training run compiled and may be enough to start.
2. **No context-specific data in nodes or compiled code.** With `SHARED`, a node that caches a value of one context
   (a function, class or constant lookup bound to the first request's context) makes contexts deoptimize each
   other, and in a stored image it would be plainly wrong. An audit of the caches in nodes is needed; the engine
   TLA+ model ([tla-engine.md](tla-engine.md)) covers per-request state and is the starting point.
3. The image build flags (`-H:+AuxiliaryEngineCache`, `-H:ReservedAuxiliaryImageBytes`) and a training script:
   the server, the Gate 2 pages and a WooCommerce walk, then `--engine.CacheStore`.
4. Correctness: a request served from the cache must give the same output as one without it (Gate 2 pages,
   WooCommerce walk, phpt on the cached image).

**The probe before step 7.** On today's native build: implement the audit's findings and `prepareForAOT`, store a
cache after the Gate 2 visits, and measure the cold visit with and without it (interleaved, memory.peak). That
decides whether the cache is step 7's main item or a footnote.

## 3. Native-only items to measure in step 7
- **Unboxed calling convention for compiled Truffle-to-Truffle calls.** Automatic; WordPress is call-heavy (hooks,
  filters, small helpers), so it shows in the native-vs-JVM steady-state gap (0.59× on Gate 1, PR #6).
- **`BytecodeInterpreterHandlerConfig.enableTailDuplication()`** for threaded handlers, and template-aware main
  dispatch. We already have `enableTailCallHandlers = true` on `PhpBytecodeRootNode` (1.32× the native tree with
  compilation off; [bytecode-dsl.md](bytecode-dsl.md), feature 7). Tail duplication is off by default: an
  interpreter-speed experiment, with the image size reported.

## 4. `GenerateBytecode.unwindExceptions` (step 5, destructors)
It marks exceptions that run `finally` cleanup while bypassing guest catches and exception interception, and
keeps the exception's identity across suspension and resumption.
- **Not for `exit()`**: php-src skips `finally` blocks on exit (`zend_is_unwind_exit`), so exit needs the opposite.
- **Candidate: destroying a suspended generator.** php-src runs the generator's pending `finally` blocks and none
  of its `catch` blocks when a suspended generator is destroyed. That is exactly this mechanism, and it matters for
  step 5, where a generator's destruction time becomes exact. Check how our generators do it today before adopting.

## Order
1. **Upgrade (its own PR):** pom to 25.5.5.0.3, try the stock processor, split tests, then the A/B above.
2. **Auxiliary cache probe** on native, before step 7 starts (audit + `prepareForAOT` + cold visit with and without).
3. **Step 7:** native image with the cache, PGO, the unboxed calling convention and tail duplication measured.
4. **Step 5:** `unwindExceptions` for generator destruction, if it matches php-src.
