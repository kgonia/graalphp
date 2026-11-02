# Partial evaluation round 4: what WooCommerce makes Truffle compile, and why it never stops (diagnosis, 2026-11-02)

## Question
[woocommerce.md](woocommerce.md) (#21) found a JVM serving WooCommerce at a quarter of php-fpm's orders, a page costing
0.46 CPU-s against 0.11, compilation that never settles (12,346 compilations of 10,482 call targets in 15 minutes,
`WC_Data::get_prop` 462 times), `WP_Query::get_posts` at 115-122k IR nodes against ~60k on WordPress, a property
default's `ThunkRootNode` failing after 60 s, and libgraal's heap bursting to 5.8 GB. This page measures and explains,
for the owner to choose round 4's work: what the largest compilations are made of, why compilation doesn't settle,
what a cap on libgraal's heap does, what #19 (round 3) and #20 (allocations) did to WooCommerce, and the targets,
ranked. Nothing here changes the interpreter: the branch `pe4-diagnosis` (on `woocommerce` after its merge of main,
`b6ca11c`) adds diagnosis scripts only.

TBD-SHORT

## Setup
- **The store and the load** are #21's: `tests/gate2/woo.sh`, WooCommerce 11.2.1 on WordPress 7.2-beta1, the load
  seed, k6 on CPUs 0-5 with 16 users, half of them buying unless said otherwise, `HttpMain --threads 16`, Gate 2's
  `SERVER_OPTS`, GraalVM 25.4.4.1.1 (libgraal), the tree interpreter.
- **Two stacks.** The timed runs (`bench/pe4-diagnosis/runs.sh`) ran on `pe4-*` (server on CPUs 14-21 under the CPU
  lock, database on 22-23); the diagnosis runs (`bench/pe4-diagnosis/diag.sh`, `woo.sh diag`) on `pe4d-*` (server on
  6-13, database on 12-13), at the same time. The diagnosis runs are slow (6 pages/s under the full trace against
  ~11 untraced) and only their counts are read, never their times as such.
- **The box was busy:** 56-65% of its 24 CPUs during the timed runs (`tests/gate2/hostload.py`, from a sampler of
  `/proc/stat`), about a third of it the diagnosis server. Every timed comparison is interleaved, so both sides saw
  the same load, but absolute throughput is below #21's (1.3-1.7 orders/s at 16 users every visitor buying, against
  #21's 2.1).
- **Builds:** "after" is `b6ca11c` (`target/classes`), "before" is `866e156` (`target/classes-before`, #21's code
  without #19 and #20), both mounted into the server (`DEV=1 DEV_CLASSES`).

### The tools, following Optimizing.md
[Optimizing.md](https://github.com/oracle/graal/blob/master/truffle/docs/Optimizing.md) (read 2026-11-02) lists the
tools below. What each gave on this workload, on GraalVM 25.4 with libgraal:

| Tool | What it showed here |
|---|---|
| `engine.TraceCompilation`, `TraceCompilationDetails` | every compilation, its tier, wall time (PE + Graal), AST, inlined calls, IR after PE and after Graal, code size; with Details the queue: its size, its load, and the **scaled thresholds** (below) |
| `engine.CompilationStatistics`, `CompilationStatisticDetails` | at the engine's close: bailouts with **phase timings of the failed compilation**, invalidation and dequeue reasons, splits, Truffle node and Graal node histograms per compilation |
| `compiler.TraceInlining` | Truffle's inlining per compilation: callees inlined or cut off, their IR estimate |
| `engine.TraceSplitting`, `SplittingTraceEvents`, `TraceSplittingSummary` | each split and each refused split ("Target too big"), each polymorphic event with **the node class that reported it**, and the summary at close |
| `compiler.TraceNodeExpansion`, `TraceMethodExpansion` (`truffleTier`) | the IR nodes after PE of one compilation by Truffle node and by Java method; restricted with `engine.CompileOnly`. **The method tree needs `compiler.NodeSourcePositions=true`**: without it every node is charged to `<root>` |
| `engine.TraceTransferToInterpreter`, `TraceAssumptions`, `-Xlog:deoptimization=debug` | transfers by our source location; HotSpot's traps of Truffle code |
| `compiler.TracePerformanceWarnings=all` | none, as after round 2 |
| `cpusampler` with `ShowTiers` | where the requests' time goes, by PHP function and tier (interpreter, tier 1, tier 2) |
| `compiler.InstrumentBoundaries` | **unusable on 25.4 with libgraal**: every one of 7,968 compilations failed (`ClassCastException: SnippetObjectConstant cannot be cast to HotSpotObjectConstantImpl`) |
| `-XX:JVMCITraceLevel=1` (not in Optimizing.md) | each JVMCI runtime and libgraal isolate created and shut down, by thread (section 3) |
| `-Djdk.graal.internal.PrintGC=true` (not in Optimizing.md) | libgraal's collections: heap sizes, pauses, causes (`tests/gate2/libgraal_gc.py`) |
| `engine.TraceCompilationPolymorphism` | printed nothing |
| `compiler.MethodExpansionStatistics`, `NodeExpansionStatistics` | printed nothing at the engine's close (as partial-evaluation.md found) |
| IGV (`-Djdk.graal.Dump=Truffle:1`) | not used: the expansion trees and the Graal node histograms answered the questions without a viewer |

**The optimization log** ([OptimizationLog.md](https://github.com/oracle/graal/blob/master/compiler/docs/OptimizationLog.md):
`-Djdk.graal.OptimizationLog=Directory`, `OptimizationLogPath`, `TrackNodeSourcePosition=true`) **has no Truffle
compilation in it**, on libgraal and on jargraal (`-XX:-UseJVMCINativeLibrary`) alike: a run compiling one PHP
function (`CompileOnly`, confirmed by `TraceCompilation`) wrote 384 compilation units on libgraal, every one a host
method (HotSpot's JIT compiling Java: `String.startsWith`, `HashMap.putVal`, our `ConcatNode.concat`, the
interpreter's `profiledPERoot` as Java), and none whose name or positions mention the PHP function. So the
optimization log and `mx profdiff` cover how Graal compiles the interpreter itself (what runs before a function is
compiled), not partial evaluation; Truffle's own traces above are the tools for that.

## 1. What the largest compilations are made of

**The largest compilations under the load** (`d1-trace`, 15 minutes, every function; IR nodes after PE and after
Graal's tiers, wall time of the compilation, PE + Graal; the box was loaded, so the times are long):

| Compilation | AST | IR after PE / after Graal | code | time (PE + Graal) | PHP callees inlined |
|---|---|---|---|---|---|
| `jetpack_autoload_classmap.php` (top level) | 26,552 | **272,886** (921,612 before PE's own cleanup) | – | **failed: 100 s timeout** (85 s PE) | 0 of 2 |
| `PaymentsExtensionSuggestions::$country_extensions` (a `ThunkRootNode`) | 6,365 | **217,076** | – | **failed after 80 s**: "stack offset for oopmap is greater than encoding limit" | 0 |
| `woocommerce/i18n/states.php` (top level) | – | 105,599 | – | **failed after 39-52 s**: code too large | 0 of 2,042 |
| `WP_Query::get_posts` T1 / T2 | 9,796 | 53,306 / 106,154; 52,873 / 100,419 | 511 / 476 KB | 6.5 s (3.0+3.5); **19.1 s** (3.2+15.9) | 0 of 98-128 |
| `wp-settings.php` | 7,079 | 39,714 / 88,164 | 513 KB | 6.2 s | 0 |
| `WP_Theme_JSON::sanitize` T1 / T2 | 1,455 | 32,691; 31,688 | 350 KB | 4.0 s; 11.4 s | 0 |
| `WP_Theme_JSON::get_block_nodes` | 1,691 | 26,403; 25,850 | 310 KB | 4.0 s; 8.2 s | 0 |
| `default-filters.php` | 5,105 | 24,835; 24,129 | 303 KB | 4.4 s; 5.6 s | 0 of 494 |
| `WP_Term_Query::get_terms` | 2,894 | 23,862; 23,691 | 208 KB | 2.3 s; 6.3 s | 0 |
| `wp_default_scripts` | 5,859 | 21,339 | 327 KB | 6.8 s | 0 of 439 |
| `WooCommerce::includes` | 1,140 | 19,918 | 217 KB | 1.5 s | 0 |
| `wp_render_layout_support_flag` (5 copies) | 2,117 | 18,462-19,024 | 177 KB | 1.5-5.2 s | 0 |

**No large compilation inlines a PHP callee.** Truffle's inlining (`TraceInlining`) explores a root's callees only
within `compiler.InliningExpansionBudget` and `InliningInliningBudget` (12,000 each, in its IR estimate); every root
above starts over it (get_posts at 51,947-54,445), so all its 98 callees are cut off, and tier 1 inlines nothing
anyway (`FirstTierInliningPolicy`: trivial only). **So the IR is the function's own body and the Java of our nodes;
the composition is a question of code per PHP operation**, as in rounds 2 and 3.

**By the Java that made the IR** (`TraceMethodExpansion`, `d2-after`: the same targets alone, `CompileOnly`; shares of
the IR after PE, each method's self count; "frame states" is `<root>`, the nodes no method is charged with, mostly
`FrameState`s, the largest Graal node kind after PE, 338k of the run's nodes):

| | frame states | holder counts | element reads | building arrays | locals, references | calls | context, class, constant lookups | properties | strings | includes, file vars |
|---|---|---|---|---|---|---|---|---|---|---|
| `get_posts` (53,424) | 26% | 8% | 5% | 3% | 23% | 5% | 1% | 11% | 5% | 0 |
| `sanitize` (32,661) | 19% | **21%** | 17% | 13% | 12% | 1% | 0 | 0 | 0 | 0 |
| `get_block_nodes` (26,403) | 22% | 13% | **26%** | 10% | 14% | 1% | 0 | 0 | 0 | 0 |
| `get_terms` (23,501) | 23% | 10% | 13% | 1% | **27%** | 4% | 1% | 4% | 3% | 0 |
| `default-filters.php` (24,809) | 29% | 4% | 0 | 0 | 1% | **36%** | 13% | 0 | 0 | 3% |
| `wp_default_scripts` (21,293) | 35% | 1% | 0 | 6% | 1% | **34%** | 10% | 0 | 4% | 0 |
| `wp-settings.php` (39,646) | 20% | 4% | 1% | 0 | 1% | 9% | **22%** | 2% | 7% | **17%** |
| `WooCommerce::includes` (19,905) | 21% | 0 | 0 | 0 | 10% | 3% | 7% | 0 | 23% | **28%** |
| the thunk (217,076) | 21% | **16%** | 0 | ~15% | 0 | 0 | **~25%** | 0 | 0 | 0 |
| `states.php` (105,599) | 34% | 0 | 0 | 8% | 0 | **~40%** | 12% | 0 | 0 | 0 |
| the classmap (272,858) | 27% | 0 | 0 | 15% | 0 | 0 | 0 | 0 | **15%** | **13%** |

What each column is, and the sites behind it:
- **Holder counts**: `PhpArray.addHolder` and `removeHolder`, ~19-26 IR nodes a site, inline at every assignment
  of an array, every argument, every element stored. The thunk has 1,364 of them (35,162 nodes), `sanitize` 427.
- **Element reads**: the string-key read's slot check and miss (`rawIfKeyAt`, `SlotCacheNode.lookup`, `getRaw`,
  `deref`): `get_block_nodes` 328 sites. Round 3's large-function rule (one call per read outside loops) applies to
  `get_posts` and the top-level files, not to these: they have fewer than 3,000 AST nodes.
- **Locals, references**: `Variables.value` (580 sites in get_posts), `LocalVarNode.writeObject`, `readIfSet`,
  `FrameWithoutBoxing.unsafeCast`, `refArrayForWrite`, and the locals' release (`FunctionRootNode.releaseLocals`,
  1,414 nodes in one place).
- **Calls**: a user function's call is `OptimizedCallTarget.doInvoke`, `ArgumentsNode.build`, and its lookup in the
  running context: `PhpContext.functionAt` plus the context's thread-local read (`OptimizedFastThreadLocal.fastGet`),
  ~20 IR nodes a call, the cost round 3 left open. `states.php` makes 2,042 calls of `__()` (4,079 thread-local
  reads, 4,082 `functionAt`), `default-filters.php` 494 of `add_filter`, `wp_default_scripts` 439 of `$scripts->add`.
- **Context, class and constant lookups**: in the thunk, each of its 1,092 `self::X` / `PaymentsProviders::X` costs
  `ClassRefNode$Named.find` (2 per site), `PhpContext.classAt` and `ClassConstLookup.find`: ~30 IR nodes; in
  `wp-settings.php` the 686 constant reads (`requiredConstantAt`, `GlobalConstLookup`) and their thread-local reads.
- **Strings**: the classmap's 2,411 `$baseDir . '/...'` concatenate inline (`TruffleString$ConcatNode`, code ranges:
  12,044 expansions), as `534819b` (round 2) chose for two strings.
- **Includes, file variables**: the classmap reads `$baseDir`/`$vendorDir` 4,823 times (`FileVarNode`);
  `WooCommerce::includes` 224 include scopes (`IncludeScope.forCall`).
- **Slow paths are behind boundaries already**: no performance warning; what stays inline is the fast path of each
  operation, repeated per site. The Graal node histogram after PE (`CompilationStatisticDetails`) has, besides the
  frame states, `InvokeWithExceptionNode`s with their `ExceptionObjectNode` and `DeoptimizeNode`: 5,339 each in the
  thunk, one per boundary call and its exception edge.

**Why the run-once code is the largest.** The three failures are code that runs once per request: a file's top level
and a property default's initializer (evaluated per context on first use, as php-src evaluates them per request).
Each is a long straight line of array literal elements (`ArrayLiteralNode` explodes its elements: 2,411 nested
literals in the classmap, 1,092 class constants in `$country_extensions`) or of calls (`states.php`). Called once per
request, they reach the scaled first-tier threshold (2,464-2,579 calls, section 2) after ~2,500 requests and are
compiled at ~1 call per request. **They are the libgraal heap bursts:** the classmap's PE built 921,612 nodes before
`ConditionalEliminationPhase` (54 s of its 85 s) and `PartialEscapePhase` brought it to 272,878 (the bailout's phase
timings, `CompilationStatistics`); in the memruns libgraal's heap peaks when these compile (section 3).

**`get_posts` did not double from WordPress to WooCommerce.** #21's 115-122k was the IR *after Graal's tiers* (the
second number of `IR a/b`), WordPress's ~60k the IR *after PE*. Like for like, the same server and front-end pages of
Gate 2's database (`wp-after`, `wp-before`) against WooCommerce's load:

| `WP_Query::get_posts`, first tier | AST | IR after PE | IR after Graal | code |
|---|---|---|---|---|
| WordPress, before (`866e156`) | 9,214 | 53,810 | 107,331 | 509 KB |
| WooCommerce, before | 9,961 | 59,531 | 117,473 | 567 KB |
| WordPress, after (`b6ca11c`) | 9,214 | 45,241 | 91,887 | 446 KB |
| WooCommerce, after | 9,961 | 53,424 | 106,368 | 524 KB |

WooCommerce's is 11% (before) and 18% (after) larger: its queries take more of the function's paths (product
meta and tax queries), so more of its nodes specialize (8% more AST nodes) and fewer branches stay unreached.
**Round 3 took 10% off it on WooCommerce** (59,531 → 53,424): the string-key read's inline check is gone
(`rawIfKeyAt` 4,030 → 0 nodes, `SlotCacheNode.lookup` 1,869 → 810, `deref` 1,927 → 826), and a built-in found by
name no longer looks itself up (`FunctionCallNode.find` 249 → 0). At 524 KB it is at 80% of HotSpot's 640 KB.

## 2. Why compilation never settles

**Not instability: volume, and a queue that never drains.** Compilation time by kind, from two runs: the untraced
baseline memrun (`m-base-r1`, 12 minutes, `TraceCompilation` only) and the full trace (`d1-trace`, 15 minutes, a
slower server). Times are the compilations' wall time on a saturated box: the compiler threads' CPU was 1,119 s
where the wall times sum to 2,012 s, so read the shares, not the seconds.

| | `m-base-r1`: compilations | wall s | share | `d1-trace`: compilations | wall s | share |
|---|---|---|---|---|---|---|
| functions, tier 2 | 819 | 992 | **49%** | 1,616 | 1,572 | **62%** |
| functions, tier 1 | 4,533 | 489 | 24% | 8,831 (incl. splits) | 670 | 26% |
| split copies, tier 1 / tier 2 | 2,578 / 67 | 146 / 49 | **9%** | 3,764 / 235 (in the rows above) | 173 / 212 | **17%** |
| three failed giants (section 1) | 3 | 216 | **11%** | 3 | 219 | 9% |
| other run-once code (file tops, thunks), tier 1 | 1,499 | 55 | 3% | 1,538 | 275 | 11% |
| a target compiled again at the same tier | 155 | 60 | 3% | ~200 | ~50 | 2% |

- **Recompilation is rare:** 3% of the time. Invalidations over 15 minutes: 107 (93 "Profiled Argument Types", 8
  return types, 2 object-location generalizations, 5 split call nodes); HotSpot traps of Truffle code: 230, spread
  over `apply_filters` (14), `WP_Hook::apply_filters` (13), `WP_Object_Cache::get` (11)... Transfers to the
  interpreter: 244, the profiles learning (`IfNode` 42, `PropNode.accessible` 30, `LocalVarNode.met` 26). No
  performance warning, no deopt cycle. #21's 270 invalidations were the same kind.
- **What keeps it going is new call targets:** 10,392 distinct targets compiled in 15 minutes, 1,764 of them more
  than once (a tier-2 compilation after tier 1 counts). In the last minutes of `d1-trace` 40-200 first tier-1
  compilations of functions and 90-400 of split copies still arrive every minute, with bursts (1,626 in minute 8, 2,178 in `m-base-r1`'s minute 9).
- **The queue is overloaded the whole time.** `TraceCompilationDetails`: the queue held 300-700 tasks in every
  minute, its load 2.5-12.9 (1 is normal). With `engine.DynamicCompilationThresholds` (default on) Truffle raises
  the thresholds with the load: the median threshold of a tier-1 enqueue was 1,175-5,275 calls (default 400, ×3-13),
  of a tier-2 enqueue 25,625-123,125 (default 10,000). So a function used once per request waits for 1,000-5,000
  requests before it is even queued, and the thresholds fall as the load falls, which is the slow trickle of
  first compilations; 10,561 of 23,365 enqueued tasks were dropped as stale (`Dequeues`). **Compilation doesn't
  settle because WooCommerce's request path is ~10,000 call targets and 3 compiler threads (`CompilerThreads`
  default here) compile it at ~1,000 compilations a minute, mostly tier 2 by time.**
- **Tier 2 is the bulk** (49-62%): each function used often enough recompiled with its callees inlined. 1,466 of
  tier 2's 1,585 s in `d1-trace` were functions of at most 1,000 AST nodes (829 of at most 50 nodes, at 1,955 IR
  nodes on average after inlining 3.2 callees).

**Splitting: 9-17% of the compile time, 12% of the code (12 of 97 MB).** In `d1-trace` Truffle made 6,258 splits
of 534 functions (3,999 compiled) and refused 2,978 as too big (`SplittingMaxCalleeSize` 100: `get_option` 518
times, `_wp_specialchars` 224). Splitting multiplies the call targets the queue has to reach, more than their
time. Which nodes asked for them (`SplittingTraceEvents`: a polymorphic event names the node class that reported it;
a function needs a split when one of its own nodes reports, or "via parent" for a callee with one caller):

| node class reporting polymorphism | events | split functions where it reported | their splits |
|---|---|---|---|
| `MethodCallNode.Dispatch` | 1,550 | 228 | **3,264** |
| `ConstantNodes.StableValueNode` | 491 | 23 | 145 |
| `ToBooleanNode` | 301 | 46 | 327 |
| `CallableDispatchNode` | 262 | 21 | 196 |
| `CompareNodes.IdenticalNode` (`===`) | 255 | 47 | 537 |
| `SubclassTestNode` (`instanceof`, `is_wp_error`) | 144 | 28 | 256 |
| `NewNode.Construct` | 61 | 6 | 106 |
| `AssignNode` | 58 | 16 | 1,021 |
| `UnaryNodes.ToLongNode` (`absint`'s `(int)`) | 44 | 4 | 208 |
| `UnaryNodes.ToStringNode` (`esc_attr`) | 26 | 7 | 257 |

(A function where several classes reported is counted under each; 894 of the 6,258 splits were of functions none
of whose own nodes reported, split for a callee.) The functions split most: `WC_Data::get_prop` 947 (106 AST
nodes: `Dispatch` and `AssignNode`), `absint` 203 (`ToLongNode`), `WC_Data::set_prop` 200 (`===`), `esc_attr` 158
(`ToStringNode`), `SharedType::get` and `Container::get` 94-95 (WooCommerce's DI container), `is_wp_error` 90
(`instanceof`), and the product getters (`WC_Product::get_price`, `get_status`, `get_parent_id`, `is_type`, ...,
56-67 each: `Dispatch`).

**Why `WC_Data::get_prop` is split per caller and compiled 458 times.** Every product and order getter is
`return $this->get_prop('price', $context);`, and `get_prop` calls `$this->get_hook_prefix()`. `MethodCallNode`'s
cache is keyed on the receiver's class (`receiver.getPhpClass().getKey() == cachedKey`, limit 4): a site that sees
`WC_Product_Simple`, `WC_Product_Variable`, `WC_Product_Variation` and `WC_Order` goes polymorphic and reports,
although for `$this->get_prop(...)` every one of those classes resolves to the same `WC_Data::get_prop`. Splitting
then copies `get_prop` per getter and each getter per caller, and no copy becomes monomorphic: the receivers vary
inside every caller too. `AssignNode`'s report is `$value = ...` taking a string, an array, an object.
**`StableValueNode` can never stabilize across requests:** it caches a constant's value by identity, and a
constant computed at run time (`define('WP_CONTENT_DIR', ABSPATH . 'wp-content')`) is a new `TruffleString` in every
context, so the second request makes the site polymorphic, in every copy alike (`wp_get_theme`: 54 events, 53
splits).

**What we could report differently** (code-level, for the owner to choose; each needs the measurements in
section 5):
1. `MethodCallNode.Dispatch` (and `StaticCallNode`, `NewNode`'s constructor dispatch): cache on the resolved
   method, not the receiver's class: a class whose lookup gives the cached `PhpFunction` takes the same entry (one
   `DirectCallNode`), so a call site with many receiver classes and one target stays monomorphic, doesn't report,
   and compiles one inlined call instead of up to four.
2. `StableValueNode`: no `@ReportPolymorphism`; its generic case is what every later context needs, and a split
   copy meets new objects all the same.
3. `AssignNode` and the conversions and tests (`ToBooleanNode`, `ToLongNode`, `ToStringNode`, `IdenticalNode`,
   `SubclassTestNode`): `@ReportPolymorphism.Exclude`, so a value of another type no longer copies a helper per
   caller. These are where splitting paid on Gate 1 (gate-2.md: hooks, escape, slugs 11-25% slower without any
   splitting), so this one needs the kernels' A/B, not only the server's.
4. Not a reporting change but the larger part: tier 2 recompiles everything hot with inlining, and the queue's
   load raises every threshold; fewer and smaller call targets (1-3, and section 1's code per operation) are what
   bring the load down.

## 3. libgraal's heap: one isolate per compiler thread, a cap, one compiler thread

**One isolate per compiler thread, recreated after a second idle.** `-XX:JVMCITraceLevel=1` (`cap256m`, and a CLI
run) prints each JVMCI runtime and its `JavaVM`, libgraal's isolate: every Truffle compiler thread
(`TruffleCompilerThread-N`) and every HotSpot JIT thread (`JVMCI-native CompilerThread-N`) gets a runtime of its own
(GraalVM 25.4's `-XX:JVMCIThreadsPerNativeLibraryRuntime` is 1), and an isolate is shut down when its thread has
been idle for `JVMCICompilerIdleDelay` (1,000 ms) and created again on its next compilation: 70-72 isolate lives in a
12-minute memrun (PrintGC's `GC(0)` lines), 30 `JavaVM`s in `cap256m`'s 4 minutes. So **`-Djdk.graal.internal.MaxHeapSize`
caps each isolate, not the total**: with 3 Truffle compiler threads and 1-2 HotSpot JIT threads busy at once,
libgraal can hold up to 4-5 caps. (`-XX:JVMCIThreadsPerNativeLibraryRuntime=0` would share one runtime, and so one
heap, between the threads; not measured.)

**What a compilation does at the cap: it fails, and its function stays interpreted.** At 1 GB the classmap's
compilation (section 1) ended after 117 s with `java.lang.OutOfMemoryError: Garbage-collected heap size exceeded`,
reported by Truffle as a failed compilation (`opt failed ... Reason: java.lang.OutOfMemoryError`), not retried, the
server unharmed: the same outcome as without a cap, where it timed out at 100 s. Before failing, an isolate at its
cap collects again and again: in `cap256m` one isolate ran full collections of 0.7-1.2 s each with 156-247 MB live.

**The memruns** (`bench/pe4-diagnosis/runs.sh mem`, `runs2.sh mem-before`; `woo.sh memrun jvm 16`: 12 minutes at
16 users, half buying, from a cold server, NMT and a snapshot a minute, every run with `TraceCompilation` and
libgraal's `PrintGC`; the merged build unless "before"; rounds interleaved, the order reversed in round 2;
`tests/gate2/pe4_memtable.py`):

TBD-MEMTABLE

- **On the merged build there is no multi-GB burst.** libgraal's share of RssAnon (memsplit's "untracked") peaks at
  0.85-1.0 GB, and one isolate's heap after a collection at 560 MB, while the classmap, the thunk and `states.php`
  compile (minutes 6-11 of `m-base-r1`; it falls to ~75 MB after them). #21's 5.8 GB was on `866e156`: TBD-BEFORE.
- **A 2 GB cap is never reached** (an isolate's largest heap 813-826 MB): the same runs as the baseline.
- **A 1 GB cap is reached once per run, by the classmap**, which then fails with the `OutOfMemoryError` above
  after 117 s instead of timing out after 100 s; nothing else changes (the same 3 failures, the same compile time).
  libgraal's share stays at 0.9-1.0 GB, as without a cap.
- **A 256 MB cap** (`cap256m-long`, the diagnosis stack, 15 minutes) fails all three giants early (11, 36 and 22 s
  instead of 100, 80 and 39 s: 91 s of failed compilation instead of 216 s) but also one ordinary tier-2
  compilation (`WP_Widget::_register`), and its isolates collect more (338 s of pauses in 15 minutes); TBD-CAP256.
- **One compiler thread** (`engine.CompilerThreads=1`): 3,678-3,938 compilations instead of 9,245-11,444, a third
  of the compiler's CPU (420-428 s against 1,106-1,168), none of the giants (their thresholds are never reached), and
  RssAnon 1.6-2.0 GB against 2.7-3.3 GB. Its throughput was not lower in any round: TBD-CT1.
- **The box's load moves throughput more than any of these options**: 9.3-14.6 pages/s across runs, with the box
  38-62% busy (`hostload.py`); compare within a round.

## 4. #19 and #20 on WooCommerce

**Method** (`bench/pe4-diagnosis/runs.sh ab`, `runs2.sh ab-more`): `woo.sh load jvm 16`, each server's own life on
CPUs 14-21: 300 s of warm-up (16 users, half buying), then 120 s at 16 users with every visitor buying; before
(`866e156`) and after (`b6ca11c`) interleaved, the order reversed every other round. Rounds 1-3 ran while the
diagnosis server worked on 6-13 (box 56-65% busy); rounds 4-6 later, without it.

TBD-AB

## 5. Round 4's targets, ranked

**Where the requests' time goes** (`d5-sampler`: the CPU sampler after 4 minutes, 10 minutes, 3,175 requests'
histograms summed by `tests/gate2/sampler_report.py`; the sampler samples the request threads' stacks, so a
database wait counts too, and samples land where compiled code polls for safepoints, at its boundary calls): **85%
of the sampled time is in tier-2 code, 10.8% tier 1, 4.3% the interpreter.** The flat profile has `wpdb::query`
11% (its inlined `mysqli_query`, the database round trip), `defined` **6.2%**, `WP_Hook::apply_filters` 5.1%,
`apply_filters` 3.7%, `WP_Object_Cache::is_valid_key` 2.5%, `get_option` 2.1%; built-ins together 18%. What runs
interpreted is little: the classmap (2.2 s in 10 minutes, 0.2%, every request, against its 100 s compilation that
failed), `mysqli_real_connect`, the first calls of getters' new split copies. So after the warm-up the requests
are compiled code, and **their time is the code per operation, not missing compilation**; the server's CPU besides
them is the compiler (21%) and the GC (14%).

The targets, ranked by expected gain on WooCommerce's CPU per page and memory, each with its evidence and how to
confirm it:

| # | Target | Evidence | Expected | Confirm with |
|---|---|---|---|---|
| 1 | **Don't compile run-once code that can't pay back**: a top-level file or a thunk (property default, constant initializer) that runs once per request, has no loop and is large. `RootNode.prepareForCompilation` (25.4 has it) can decline it; or give a literal with constant parts a constant template (the classmap's 2,411 entries differ only in `$baseDir`), and fold `self::CONST` of the declaring class in constant expressions at link time, as php-src's `zend_try_ct_eval_class_const` does (`$country_extensions`' 1,092 references are partly other classes', so this alone doesn't make it constant) | the three failed giants: 11% of the compile time, a compiler thread blocked 36-117 s each, libgraal's peak heap (section 3), a thunk with no source position ("Src n/a"); the classmap interpreted costs 0.2% of the requests' time; with one compiler thread none of them compiled and throughput didn't fall | −10-14% compile CPU (−2-3% of the server's CPU), libgraal's peak −0.4-0.8 GB per isolate, the queue's load down | memrun: `pe4_report.py kinds` (no failures, file/thunk share), `libgraal_gc.py`, `pe4_memtable.py`; `d1-trace` with 0 permanent bailouts |
| 2 | **Method dispatch keyed on the resolved method** (`MethodCallNode.Dispatch`, also static and constructor dispatch): a receiver class whose lookup gives the cached function shares its entry | `Dispatch` reported in functions with 3,264 of 6,258 splits; `WC_Data::get_prop` 947 splits, 458 compilations, 33 s, the most of any function; the product getters 56-67 copies each; up to 4 inlined call paths per site where one target is called | splits −40-50%, split copies' compile time (9-17%) about halved, fewer call targets for the queue, one inlined call per `$this->` site | `d1-trace`: `pe4_report.py splits` and `compile`; Gate 1's hooks and blocks A/B; `woo.sh load` A/B |
| 3 | **`defined('NAME')` as a node**: the name's constant id resolved at translation, `constantAt(id) != null`, as php-src's `ZEND_DEFINED` with its cache slot; today a built-in call converting the name to a Java `String` and hashing it into the context's map | 6.2% of the sampled request time (with the sampler's bias toward boundary calls); 951 WooCommerce files start with `defined('ABSPATH') || exit;`, 1,846 literal `defined()` sites in WooCommerce and core | a few % of request time; to be measured first | count the calls per request (a counter, or JFR's Java frames); `woo.sh load` A/B |
| 4 | **A user function's call without the per-context lookup**: the running context in the frame (or arguments) instead of `PhpContext.get`'s thread-local read, and the function cached per site when the language serves one context | ~20 IR nodes a call (`fastGet` + `functionAt`); 36% of `default-filters.php`'s IR, 40% of `states.php`'s, 34% of `wp_default_scripts`', 5-6% of `get_posts`'; round 3 left it open | −5-10% code in call-heavy functions, less per call | `CompilationTest`'s per-operation costs (round 3's method), the largest compilations' `categories` |
| 5 | **Holder counts out of the straight line**: `addHolder` / `removeHolder` inline at every assignment, argument and element store (~19-26 IR nodes a site, its rare path a boundary call with an exception edge); a fresh literal's nested arrays could take their count without the tests | 21% of `sanitize`'s IR, 16% of the thunk's, 13% of `get_block_nodes`', 8% of `get_posts`' | −5-15% of array-heavy functions' code | `categories` before/after; Gate 1 (kses, blocks) A/B |
| 6 | **Report polymorphism only where a copy helps**: no `@ReportPolymorphism` on `StableValueNode` (a per-context value never stabilizes), `@ReportPolymorphism.Exclude` on `AssignNode` and the conversions and tests (`ToBoolean`, `ToLong`, `ToString`, `===`, `instanceof`) | together reported in functions with ~2,700 splits (`AssignNode` 1,021, `===` 537, `ToBoolean` 327, `ToString` 257, `instanceof` 256, `ToLong` 208, `StableValue` 145); `absint` 203 splits, `esc_attr` 158 | splits −20-30% more after #2 | as #2, and Gate 1's escape and slugs (where splitting paid in 2026-10-28's test) |
| 7 | **Less compile volume overall** (owner's choice of flags; code-level it is #1, #2, #6): one compiler thread compiled 3,678 times instead of 9,654, never the giants, at the same throughput and half the memory (section 3); libgraal's collections paused its isolates for ~200 s in 12 minutes (1,300-1,500 hinted full collections), and an isolate is torn down after 1 s idle and rebuilt 70 times | `m-ct1-*` against `m-base-*`; `libgraal_gc.py` | TBD-CT1-GAIN | interleaved memruns (done here: section 3), then a longer one (30 min) for the late compilations |

Not targets: deoptimization (3% of compile time recompiles, no cycle), performance warnings (none), the slow paths
(behind boundaries already), Truffle's inlining (the largest functions inline nothing because they are over its
budget; their size is the issue, items 4-5).

## Files
- `tests/gate2/woo.sh diag`: a diagnosis server's life under the load, its engine closed at the end (statistics).
- `bench/pe4-diagnosis/diag.sh`: every diagnosis run of this page with its options; `runs.sh`: the timed runs.
- `tests/gate2/pe4_report.py`: compile (splits against originals, by size, the largest), splits (polymorphic
  events by node class, the functions split most), inlining, expansion (own body, inlined callees, node classes,
  Java methods), deopts.
- `tests/gate2/libgraal_gc.py`: libgraal's collections from `-Djdk.graal.internal.PrintGC=true`.
- `tests/gate2/hostload.py`: how busy the box was during each timed run.
- `bench/pe4-diagnosis/`: the reports and tables of this page's runs.
