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
| `compiler.InstrumentBoundaries` | the Truffle boundaries compiled code calls, counted |
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
  than once (a tier-2 compilation after tier 1 counts). In the last minutes of `d1-trace` 100-300 first-time tier-1
  compilations a minute still arrive, with bursts (1,626 in minute 8, 2,178 in `m-base-r1`'s minute 9).
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

TBD-Q3

## 4. #19 and #20 on WooCommerce

TBD-Q4

## 5. Round 4's targets, ranked

TBD-Q5

## Files
- `tests/gate2/woo.sh diag`: a diagnosis server's life under the load, its engine closed at the end (statistics).
- `bench/pe4-diagnosis/diag.sh`: every diagnosis run of this page with its options; `runs.sh`: the timed runs.
- `tests/gate2/pe4_report.py`: compile (splits against originals, by size, the largest), splits (polymorphic
  events by node class, the functions split most), inlining, expansion (own body, inlined callees, node classes,
  Java methods), deopts.
- `tests/gate2/libgraal_gc.py`: libgraal's collections from `-Djdk.graal.internal.PrintGC=true`.
- `tests/gate2/hostload.py`: how busy the box was during each timed run.
- `bench/pe4-diagnosis/`: the reports and tables of this page's runs.
