# Partial evaluation round 4: run-once code, dispatch per method, `defined()` in place (2026-11-02)

## Question
[pe-round4-diagnosis.md](pe-round4-diagnosis.md) (#22) ranked round 4's targets on WooCommerce; the owner chose the
first three: (1) don't compile run-once code that can't pay its compilation back, (2) method dispatch keyed on the
method found instead of the receiver's class, (3) `defined('NAME')` as a node, as php-src's `ZEND_DEFINED`. This page
is what each changed, the evidence that PHP's behaviour didn't, and what each did to Gate 1, Gate 2 and WooCommerce.
Then whether the Bytecode DSL interpreter has the same problems.

## Short answer
1. **Run-once code** (`RunOnceCode`): a file's top level that no include site runs twice in a request, and a
   constant initializer, are not compiled when they have no loop and over 3,000 AST nodes. On WooCommerce **no
   compilation fails any more** (the Jetpack classmap, `$country_extensions`' thunk and `states.php` stay
   interpreted, 0.3% of the requests' time), libgraal's largest isolate heap is 550 MB instead of 1.9 GB, and
   **memory.peak is 41-42% lower** in the warm A/Bs (3.3 → 1.9-2.0 GB), 21% lower from cold.
2. **Dispatch per method found** (`MethodLookupNode` per class, `MethodDispatchNode` per method): a site whose
   receivers resolve to one method is one call. **Splits halve** (6,382 → 3,351; `WC_Data::get_prop` 945 → 251,
   its compilations 477 → 229), call targets compiled −8%; the compiler's freed time goes to tier 2.
3. **`defined('NAME')` in place** (`DefinedNode`, as php-src's `ZEND_DEFINED`, only where php-src compiles it):
   `defined` falls from 6.8% of the sampled request time to 0.7%. The built-in now resolves the other forms as
   `zend_get_constant_ex` does (class constants, namespaces, `TRUE`), which it got wrong before.
4. **WooCommerce, all three against the base:** from cold (12-minute memruns) **+26% orders/s, −17% CPU-s per
   page, −32% p99, −21% memory.peak, −59% libgraal**; warm (4 interleaved rounds, the order reversed each round):
   orders/s and CPU-s per page unchanged (1.00, 0.99), memory.peak **−42%**, RssAnon −48%. Of the targets alone,
   only target 1 moves anything measurable at steady state (memory); targets 2 and 3 are within ±5% noise there.
5. **Correctness:** 4 new smoke tests pinning PHP 8.5 (the dispatch's and `defined()`'s cases), every smoke test equal,
   Gate 2's pages 19 of 21 (the known oEmbed link), the WooCommerce walk as the base's, php-src `.phpt` no new failure
   (7 more passing from this branch, 64 with main's merge), Gate 1's checksums equal (its timing is open:
   section 5). Found on the way: `issues/012-014` (class constant and method visibility, namespaced intrinsics).
6. **The bytecode interpreter** already dispatches per method and reports no polymorphism there, and keeps file
   tops and thunks on the tree (target 1 applies); `defined()` would need porting to it.

## Setup
- **Builds** (branch `pe-round4` on `pe4-diagnosis`, whose interpreter is `b6ca11c`'s, the diagnosis' "after"):
  `base` (`b580a2a`), `t3` (`312788c`, `defined()`), `t2` (`0194ec7`, and the dispatch), `t1` (`d0218d3`, and
  run-once code: all three). Each is a copy of `target/classes` (`target/classes-X`), mounted into the servers
  (`DEV=1 DEV_CLASSES`). Later commits, not in the timed builds: `41b8e70` (`defined('Trait::X')` false, the
  generic path) and the merge of main (`32ccdec`); the checks of section 4 ran on them too.
- **WooCommerce** is #21's and the diagnosis' store and load (`tests/gate2/woo.sh`, WooCommerce 11.2.1 on WordPress
  7.2-beta1, k6 on CPUs 0-5, 16 users, `HttpMain --threads 16`, GraalVM 25.4.4.1.1 with libgraal, the tree
  interpreter), on stacks of this page's own: `p4i-*` for the timed runs (server on CPUs 14-21 under the CPU lock,
  database on 22-23), `p4id-*` for the diagnosis runs (server on 6-13, database on 12-13), `p4g-*` for Gate 2's pages.
  The site is a copy of the diagnosis' (`wordpress-pe4`'s `target/gate2`), the databases its seeds.
- **The runs** are `bench/pe-round4/runs.sh` (one kind of run each), in the order of `bench/pe-round4/all.sh`: the
  timed queue (Gate 1, memruns, load A/Bs) and the untimed one (pages, diagnosis servers) side by side, each server
  waiting for its memory reservation. Every A/B is interleaved with the order reversed every round: the diagnosis
  found that the server started first in a round won 6 of 6 rounds, whichever build it was.

## 1. Run-once code is not compiled
**What it is** (`418710c`, `d0218d3`, `RunOnceCode`): `FileRootNode` and `ThunkRootNode` decline their compilation
in `RootNode.prepareForCompilation` (GraalVM 24.2+) when the code
- **runs a number of times a request bounded by the program's text:** a file's top level that no include site has
  run twice in one context (`PhpContext.includedBefore` keeps, per context, the sites that ran each file; a site
  running a file again marks its root, which from then on is compiled as any code), or a constant expression's
  initializer (a class constant or property default that doesn't fold: once per class and context);
- **has no loop:** no `LoopNode` (`for`, `while`, `do`, an array's `foreach`), no `ForeachNode`, no goto labels;
  so each of its nodes runs at most once per run;
- **and is large:** `LargeCode.LARGE`, 3,000 AST nodes, the size from which HotSpot's 640 KB code limit binds
  ([partial-evaluation.md](partial-evaluation.md)).

**Why that criterion.** Compiling loop-free code saves the interpreter's cost of each node once per run, tens of
nanoseconds, against a compilation that costs 0.7-0.9 ms per AST node on WordPress's largest functions and more on
straight-line literals (the classmap: 100-358 s for 26,552 nodes): run a few times a request, it would pay back
after thousands of requests, if it compiled at all. Large straight-line code is exactly what failed on
WooCommerce, holding a compiler thread for 40-360 s and gigabytes of libgraal's heap, and then ran interpreted all
the same. Small run-once code compiles in milliseconds and is left to Truffle; a loop, or one include site running
a file again (a template part in a loop), makes the number of runs depend on the data, so that code is compiled as
any function. There is no list of names.

**Sites, not includes.** The first version (`418710c`) compiled a file as soon as any include found it included
already in the request. The diagnosis server showed Jetpack's class map still compiled, and timing out after
249 s: WooCommerce's copy of the Jetpack autoloader requires it twice a request, from its version check
(`Autoloader_Locator::get_autoloader_version`) and from its manifest reader. Two sites are two runs, fixed by the
code; `d0218d3` counts per site.

**What a decline does.** Graal's `TruffleTierContext` turns `false` into a retryable bailout ("Compilable not
ready for compilation"): nothing is logged, nothing is compiled, and the call target is asked again after another
threshold of calls (a test of two cached fields). In small (`bench/pe-round4/run-once.sh`, `run-once.txt`: a
1,200-entry class map and an 800-element class constant, 10 contexts on one engine, thresholds of 3 and 6 calls):
before, the map's compilation fails after 20 s ("code is too large") and the constant compiles to 19,000 IR nodes;
after, the map required by two sites and the constant aren't compiled, the map required by one site in a loop is
(and fails as before); the output is the same.

**Alternatives not taken.** The diagnosis also named a constant template for literals whose parts are constant
(the classmap's entries differ only in `$baseDir`) and folding `self::CONST` at link time
(`zend_try_ct_eval_class_const`). Both would make these roots cheaper to run or to compile, not unnecessary to
compile; the declined roots cost the requests little interpreted (the classmap: 4.2 s of 1,475 s sampled, 0.3%,
`s-base-sampler.txt`), so the decline is the measured choice, and the templates would pay only where such code
runs often.

## 2. Dispatch per method found
**What it is** (`0194ec7`): `MethodCallNode.Dispatch`, `StaticCallNode.Dispatch` and `NewNode`'s constructor
call no longer key their cache on the receiver's class. A call site
- **looks the method up per class:** `MethodLookupNode`, cached on the class's language-level `ClassKey` (one
  entry serves every context), up to 8 classes; beyond them the class's method table, as php-src's runtime cache
  keeps one class and looks up again when it changes. It reports no polymorphism.
- **calls per method found:** `MethodDispatchNode`, a direct call per `PhpFunction` (methods are language-level,
  the same object in every context: `ClassTemplate`), up to 4, then an indirect call. This node reports
  polymorphism: different methods at one site are what a split copy could make monomorphic.
- `new` dispatches on `PhpClass.getConstructor()` (a field) the same way.

So `$this->get_prop()` on every product and order class of WooCommerce is one entry and one inlinable call, and
asks for no split; before, four classes filled the cache, the site went megamorphic (an indirect call) and
reported. In `src/test/resources/compile/receivers.php` (six classes inheriting one method), `total` inlines `price`
and `getProp` at the last tier (581-626 IR nodes); keyed on the class it inlined nothing (689-824).

**What depends on the class stays the call's own.** $this is the receiver and the called class (`static::`,
`get_called_class()`) its class, at every call, whatever class the method was found in: the entry keeps nothing of
that class. A static method gets no $this; a class without the method goes to `__call` (looked up per class too),
or for the static syntax to `__call` on the caller's $this or to `__callStatic`; traits import a `PhpFunction` per
using class, so a trait method's `self` and `__CLASS__` are its class's. One change, toward PHP: a static-syntax
call of a missing method with neither `__call` (for the caller's $this) nor `__callStatic` is an error before its
arguments are evaluated, as php-src's `INIT_STATIC_METHOD_CALL`; the old code evaluated them first
(`method-dispatch.php`, "argument evaluated").

**What the dispatch didn't do and still doesn't:** check visibility from the calling scope, and resolve a private
method in the calling scope (`issues/013`). The lookup stays per class at a site whose calling scope is its lexical
class, so php-src's rules (`zend_std_get_method`) can go into it later without changing the caches.

## 3. `defined('NAME')` in place
**What it is** (`312788c`): `defined('NAME')` compiles to `ConstantNodes.DefinedNode` exactly when php-src's
`zend_compile_func_defined` makes `ZEND_DEFINED`:
- one argument, a literal (`ZEND_AST_ZVAL`: a string, or an int or float, which names the constant its string
  form does: `defined(1)` is no TypeError under `strict_types`), without `\` or `:`;
- in a call whose name is resolved at compile time to the global function: `defined(...)` outside a namespace,
  `\defined(...)`, a `use function defined as d` alias. An unqualified call in a namespace is resolved at run time
  in php-src (`ZEND_INIT_NS_FCALL_BY_NAME`: a namespaced `defined()` may shadow it) and stays a call;
- `true`, `false` and `null` in any case, and the predefined constants, fold to `true`, as `zend_try_ct_eval_const`
  does; for any other name the constant's id is resolved at translation and the node tests the context's slot, an
  exact match as `zend_quick_check_constant`'s (`defined('pe4_plain')` is false for `PE4_PLAIN`). As
  `ZEND_DEFINED`'s cache slot keeps the constant found, a site that found its constant is true from then on while
  the language serves one context (constants are never undefined).

`src/test/resources/compile/defined.php` (eight guards in a function): 146-225 IR nodes in one context, 283-356 in
three (the context's thread-local read per test), against 378-470 as calls.

**The function itself**, for every other form, now resolves as `zend_get_constant_ex`: a leading backslash
stripped; `"A::B"` a class constant (its class autoloaded, its initializer evaluated, `self`, `parent` and `static`
the caller's, with php-src's errors when there is none); a namespaced name's namespace caseless (constants are now
kept under php-src's key, the namespace in lowercase, so `\PE4NS\X` also reads `Pe4Ns\X`, and
`get_defined_constants()` lists the name as defined); `true`, `false` and `null` in any case. Before, it looked
the string up as it was: class constants, `TRUE` and a namespace in another case were "not defined".

## 4. Correctness
**Smoke tests pinning PHP 8.5's behaviour first** (`tests/smoke/`, compared with PHP 8.5.12 as the suite does: one
context, three contexts on one engine, statements instrumented):
- `defined-special.php`: literal names (case, `true`/`TRUE`/`null`, predefined constants, `__LINE__`, ints and
  floats, a leading backslash), before and after a `define()` at one site and in a loop, `defined() || define()`,
  class constants (`A::X`, a class in another case, a constant initializer evaluated, `self::`/`parent::`/`static::`
  from methods and a closure, `::class`, a missing class, an autoloaded class and a failing autoload, `self` without
  a class scope, `parent` without a parent), and the dynamic forms (a variable, a concatenation,
  `call_user_func`, `array_map('defined', ...)`, `$f = 'defined'`). On the base build: 73 diff lines against PHP's
  114 lines of output (class constants, `TRUE`, ...).
- `defined-namespace.php`: in a namespace, unqualified (resolved at run time), qualified, a namespaced name in any
  case, `namespace\`, a namespaced `defined()` declared unconditionally and at run time, `use function defined`;
  `\PE4NS\X` reading `Pe4Ns\X`, `get_defined_constants()`'s names. On the base build: 28 diff lines
  against 35 (it stops at `\PE4NS\NS_CONST`, "Undefined constant").
- `defined-strict.php`: int and float literals under `strict_types` (the same on the base build, which doesn't
  enforce `strict_types` for built-ins at all).
- `method-dispatch.php`: one site over 11 classes of one method (past the lookup's 8), two of them overriding
  the method it calls, `static::class`, `get_called_class()` and `self::class` per class, `new static`, an own
  static method with `parent::`; a site whose classes resolve to the method, to `__call` and `__callStatic`; a
  missing method with an argument of side effect, by instance and static syntax; `__call` reached by `self::`,
  `static::`; trait methods (`__CLASS__`, `self`, `static`, `new self`) over four using classes; forwarding
  static calls and a non-static method called statically from an instance; constructors inherited, own, none and
  with a by-reference parameter, at one `new $c` site. Each loop runs long enough for its sites to compile. Before
  the change one line differed: the missing static method's argument was evaluated.

**The checks of [README.md](README.md)** (t3, t2, t1: the timed builds; final: `HEAD` after the merge of main,
`target/classes` with `41b8e70`; JVMs of the final checks kept off CPUs 14-21 by their process affinity):

| check | base | t3 | t2 | t1 | final |
|---|---|---|---|---|---|
| `tests/smoke/run.sh` | 115 of 116 (`method-dispatch`'s one line) | 115 of 116 (the same) | 116 | 116 | 132 (main's 16 more) |
| `tests/smoke/run.sh` of the 4 new tests on the bytecode interpreter | | | | | 4 |
| `mvn test` (`CompilationTest` with `defined.php` and `receivers.php`, Gate 1's kernels at the first tier) | | | | 40, 0 failures | 40, 0 failures |
| `tests/http/run.sh` | | | | both equal | both equal |
| Gate 2's pages (`native.sh pages`, CPUs 6-13) | 19 of 21 | 19 of 21 | 19 of 21 | 19 of 21 | 19 of 21 |
| WooCommerce's walk against PHP 8.5 (`woo.sh walk`, `compare`): 34 of 51 responses and row sets the same | as below | as base | as base | as base | as base |
| php-src `.phpt` (`tests/phpt/run.sh`, CPUs 6-13, the same oracle) | 3,306 pass | | | 3,312 | 3,369 (main's merge: 64 more than base) |
| Gate 1's checksums equal PHP's (every kernel, every iteration of the A/B) | yes | yes | yes | yes | |

- **Gate 2:** the two differing pages are the known oEmbed XML link (no `SimpleXMLElement`), on every build.
- **The walk:** the same 17 differences on every build, base included: 11 pages by the oEmbed link, the admin's
  orders list and five tables by the analytics import, which on the JVM imports order 37 in one walk, none in
  another, on base as on the others (three walks of base: one, none, none). That is #21's known difference: the
  Action Scheduler ends its batch when `memory_get_usage()` (the JVM's heap here) passes 90% of `memory_limit`.
- **php-src `.phpt`:** t1 against base: 7 newly pass (`constants/bug41633_4`, `bug42820`, `bug46304`,
  `inheritance/bug53727`, two of `attributes/deprecated/class_constants`,
  `type_coercion/float_to_int/non-rep-float-as-int-extra1`), 1 newly failed, `traits/constant_017.php`:
  `defined('Trait::X')` must be false, which `41b8e70` fixed (and `defined-special.php` now tests). The final
  build against base: 64 newly pass, and the one newly failing, `class_name/bug69754.php`, prints `date('Ymd')`:
  the oracle ran the day before.
- **Smoke:** one run of the final 132 failed `curl.php` (the extension not loaded in that process: every
  `curl_*` undefined); run again it passed, as on every other run of every build: a transient of the box's.

## 5. Measurements

### The diagnosis servers: compilation, splitting, libgraal
`runs.sh diag BUILD d-BUILD splits`: one server's life of 15 minutes on CPUs 6-13 under WooCommerce's load (16 users
half buying), `TraceCompilation`, `TraceSplitting` with its events and summary, `CompilationStatistics`, libgraal's
`PrintGC`; reports `pe4_report.py compile|splits|kinds`, `libgraal_gc.py` (`bench/pe-round4/d-*.txt`). These
servers run `HttpMain --threads 4` (woo.sh's global `THREADS=4` shadows `diag`'s default of 16, as in the
diagnosis' runs, whose counts these reproduce: 6,382 splits against its 6,258) and beside other work, so their
counts compare, their times don't.

| | base | t2 (dispatch) | t1 (all three) |
|---|---|---|---|
| splits made | 6,382 of 536 functions | **3,351** of 349 | 3,395 of 339 |
| splits where a method dispatch reported | 3,264 (`MethodCallNode.Dispatch`) + 106 (`new`) + 7 (static) | 523 (`MethodDispatchNode`) | 525 |
| `WC_Data::get_prop`: splits; compilations, compile time | 945; 477, 28.1 s | 251; 229, 26.1 s | 253; 197, 20.8 s |
| compilations; of split copies | 12,737; 4,188 | 12,176; 2,967 | 11,423; 2,711 |
| call targets compiled | 10,527 | 9,678 | 9,218 |
| failed compilations (wall s) | 3 (215 s: the classmap's timeout, the thunk's oopmap limit, `states.php` too large) | 3 (207 s) | **0** |
| compile wall time: tier 2, split copies, failed, top level and thunks | 1,546 + 28, 364, 215, 61 s | 1,657 + 23, 318, 207, 40 s | 1,727 + 31, 316, 0, 49 s |
| libgraal: largest isolate heap before / after a collection | 1,875 / 570 MB | 1,854 / 561 MB | **550 / 114 MB** |
| server's memory.peak (the diagnosis' load, busy box) | 2,990 MB | 2,942 MB | 1,659 MB |

- **Dispatch per method halves the splits.** Before, `Dispatch` reported in functions with 3,264 of the 6,382
  splits; after, the method dispatch reports in functions with 523, where a site really calls different methods
  (`$this->get_hook_prefix()` in `get_prop`, overridden per product type). `get_prop` is split 251 times, not 945.
  What's left is the other reporters (round 4's target 6): `===`, `ToBoolean`, `instanceof`, `AssignNode`,
  `ToLong` (`absint`, 198-208 splits on every build), `StableValueNode`.
- **The compiler's time doesn't fall; it moves.** The three compiler threads stay busy for the whole run on every
  build (2,378-2,559 s of compile wall time in 15 minutes); what the split copies and the failed giants no longer
  take goes to tier 2 (1,546 → 1,727 s), so more functions get their inlined second tier sooner.
- **No failed compilation** with run-once code declined: the classmap, the thunk and `states.php` stay
  interpreted, and libgraal's largest isolate heap is 550 MB, not 1.9 GB.

### The memruns: from a cold server, 12 minutes
`runs.sh mem base t1 2`: `woo.sh memrun` (16 users half buying, 12 minutes from a cold server, CPUs 14-21, NMT and
memsplit every minute, TraceCompilation, libgraal's PrintGC), base and t1 interleaved, round 2 in reverse order;
`tests/gate2/pe4_memtable.py` (`bench/pe-round4/mem-table.md`, `m-*-kinds.txt`):

| run (first in its round) | orders/s | pages/s | p50 / p99 ms | CPU-s/page | RssAnon max | memory.peak | libgraal at most | libgraal isolate after a GC, max | compilations | failed |
|---|---|---|---|---|---|---|---|---|---|---|
| r1 base (first) | 0.68 | 9.2 | 1436 / 5359 | 0.831 | 2,725 MB | 2,764 MB | 865 MB | 569 MB | 9,043 | 3 |
| r1 t1 | 0.63 | 8.3 | 1691 / 4851 | 0.925 | 2,059 MB | 2,072 MB | 388 MB | 91 MB | 8,803 | 0 |
| r2 t1 (first) | 0.88 | 11.4 | 1234 / 3278 | 0.680 | 1,906 MB | 1,948 MB | 364 MB | 104 MB | 9,752 | 0 |
| r2 base | 0.51 | 7.0 | 1950 / 6492 | 1.093 | 2,348 MB | 2,365 MB | 985 MB | 467 MB | 6,708 | 1 (the classmap's timeout) |
| **t1 / base**, geometric mean | **1.26** | 1.21 | 0.86 / 0.68 | **0.83** | 0.78 | **0.79** | **0.41** | 0.19 | | |

The first server of each round won its round again, so only the pair of rounds says anything: over both, t1 took 26%
more orders (21% more pages) in its first 12 minutes at 17% less CPU per page, with a fifth less memory.peak and less
than half of libgraal's memory, and no failed compilation; base's second run hadn't reached two of the three giants in
its 12 minutes (one failure). Compile time stays saturated (1,981-2,040 s of wall time on each, `m-*-kinds.txt`); t1's
goes to tier 2 (919-1,250 s against base's 919).

### `defined()`: the sampler
`runs.sh diag BUILD s-BUILD sampler` (the diagnosis' `d5-sampler`: the CPU sampler after 4 minutes, 10 minutes,
`sampler_report.py`, `bench/pe-round4/s-*-sampler.txt`): **`defined` was 6.8% of the requests' sampled self time
on base (100.5 of 1,474.9 s), the second entry after `wpdb::query`; on t3 0.7%** (10.7 of 1,482.9 s), the calls
left being the forms php-src calls too (a variable name, `A::B`, namespaced names). The sampler samples where
compiled code polls for safepoints, which favoured the built-in's boundary call, so the 6.8% overstates what the
call cost; the load A/B is the measure of what it gave.

### The load A/Bs: warm, every visitor buying
`runs.sh ab A B 4` per target and for all three: `woo.sh load jvm 16` (300 s of warm-up at 16 users half buying,
then 120 s at 16 users with every visitor buying), each server's own life on CPUs 14-21 from the load seed, the two
builds interleaved and **the order reversed every round** (`bench/pe-round4/abtable.py`, `ab-*.md`). A cell of the
last row is the geometric mean of the four rounds' ratios, B over A:

| A/B (target) | orders/s | pages/s | p50 | p99 | CPU-s per page | memory.peak | RssAnon max | rounds' orders ratios |
|---|---|---|---|---|---|---|---|---|
| base → t3 (`defined()`) | 1.05 | 1.05 | 0.99 | 0.95 | 0.96 | 0.95 | 0.96 | 0.99, 1.40, 1.02, 0.86 |
| t3 → t2 (dispatch) | 0.96 | 0.96 | 1.04 | 1.03 | 1.03 | 1.01 | 1.02 | 0.86, 1.00, 0.99, 1.01 |
| t2 → t1 (run-once code) | 1.09 | 1.09 | 0.91 | 0.93 | 0.91 | **0.59** | **0.54** | 1.20, 1.23, 0.92, 1.05 |
| **base → t1 (all three)** | **1.00** | 1.00 | 1.00 | 1.05 | **0.99** | **0.58** | **0.52** | 1.00, 1.13, 1.10, 0.82 |

The medians, base → t1: 1.57 → 1.64 orders/s, 12.6 → 13.1 pages/s, p50 1128 → 1086 ms, p99 2373 → 2525 ms,
0.618 → 0.590 CPU-s per page, **memory.peak 3,314 → 2,022 MB**, RssAnon 3,276 → 1,768 MB.

- **Throughput and CPU per page at steady state: within this A/B's noise for every target**, as the diagnosis
  found for #19 and #20 (each round's ratio moves by ±10-40%, the first-started server still often ahead). The
  memruns' first 12 minutes (above) are where t1 is faster: there the compilation that no longer fails and the
  splits that no longer happen are a large part of the server's work; after 300 s of warm-up the requests run
  compiled code that these targets barely change: likely the split copies had already made the dispatch's sites
  monomorphic per caller, and `defined()` cost less than the sampler's 6.8% (which counts where compiled code
  polls for safepoints) said.
- **Memory: −41-42% memory.peak and −46-48% RssAnon, from target 1** (t2 → t1 and base → t1 alike, every round
  0.46-0.66): with no giant compiling, libgraal's isolates stay small.
- **Correctness under load** (`woo_check.py`): no failed request in any of the 32 runs. Four runs (one in eight,
  base twice among them) had one product's `_stock` one higher than its orders say, the lost update of a stock
  decrement between two concurrent orders; the diagnosis' memruns show the same (`m-cap256m-r1`, `m-cap2g-r2`), so
  it isn't these targets'. Its cause (WooCommerce's stock update under concurrency, or ours) is open.

### Gate 1
`runs.sh gate1`: `bench/gate1/ab.sh all 400 4 base t3 t2 t1` (the four builds interleaved, the order reversed every
other round, CPUs 14-21; `bench/pe-round4/gate1-4builds.txt`). Steady-state time per iteration (the last quarter),
the median of each build, and the median per-round ratio to base (above 1 is slower), with its IQR:

| kernel | base ms | t3 | t2 | t1 |
|---|---|---|---|---|
| hooks | 17.4 | 1.14 (1.07-1.21) | 1.02 (1.01-1.03) | 1.06 (1.03-1.09) |
| escape | 2.15 | 1.09 (1.06-1.13) | 1.09 (1.05-1.13) | 1.19 (1.16-1.23) |
| slugs | 1.76 | 1.14 (1.05-1.24) | 1.23 (1.10-1.35) | 1.16 (1.03-1.28) |
| wpautop | 6.00 | 1.08 (0.98-1.17) | 1.05 (1.04-1.05) | 1.15 (1.02-1.28) |
| wptexturize | 4.49 | 0.96 (0.87-1.04) | 0.95 (0.94-0.96) | 1.05 (0.91-1.19) |
| blocks | 7.02 | 1.24 (1.17-1.31) | 1.16 (1.02-1.29) | 1.11 (1.11-1.12) |
| kses | 40.9 | 0.92 (0.91-0.94) | 0.94 (0.87-1.01) | 0.92 (0.82-1.02) |
| peak memory | 1,478 MB | 1.00 | 0.99 | 1.02 |

The kernels' checksums equal PHP's on every iteration of every run. **This A/B doesn't separate the builds:** t3's
change isn't on the kernels' hot paths (WordPress's files call `defined()` there only outside the kernels' loops),
yet it reads 14% slower on hooks and 24% on blocks, while t1, which contains t3, reads 6% and 11%; the
four rounds ran beside other agents' work on the box (the bytecode A/B of `wordpress-bcdefault` started as this one
ended).
A rerun of base against t1 alone, 8 rounds, was queued for after the final checks; the CPUs' lock was held by
another agent's A/B from 00:41 until this page was written (02:45), so it didn't run (`runs.sh gate1` with
`GATE1_BUILDS="base t1" ROUNDS=8` repeats it). Gate 1's timing of round 4 is therefore open: the four-build A/B
neither shows a gain nor separates a loss from the box's noise.

## 6. The Bytecode DSL interpreter
The Bytecode DSL interpreter (`PhpBytecodeRootNode`, `BytecodeTranslator`, on main since the merge) translates
functions' bodies; this page's measurements are the tree's, as the base's. On main (`06702b3`), for each target:
1. **Run-once code:** the bytecode interpreter translates function bodies only; a file's top level is still a
   `FileRootNode` and a constant initializer a `ThunkRootNode` (`Translator.translateFile`, `constantOrDeferred`),
   so **target 1 applies to it as it is**. If the tree goes and top levels become bytecode roots, the same
   `prepareForCompilation` belongs on them: no loop (the translator knows when it emits a loop or a label), large,
   and no include site having run the file twice (`IncludeNode`'s bookkeeping, wherever includes run).
2. **Dispatch:** the bytecode path was already built this way: `ResolveMethod` / `ResolveStaticMethod` cache the method
   per class key (limit 4, then a lookup per call), and `CallMethod`, `CallStatic` and `Construct` dispatch on the
   `PhpFunction` found (limit 4). These operations carry no `@ReportPolymorphism`, so they don't ask for a split,
   neither for one method over many classes nor for several methods. **No porting needed;** what differs is the lookup's
   limit (4 against the tree's 8) and that a site calling different methods doesn't report (target 6's question).
3. **`defined()`:** `BytecodeTranslator.call` sends `defined('X')` through `CallInfo` to the built-in, as any
   call: **the node would need porting** (an operation with the constant's id as a constant operand, under
   `compiledSpecially`'s same condition). The built-in's fixes (`zend_get_constant_ex`'s forms, the namespace's
   key, traits) are shared and apply to it already.

## Found on the way
- **`issues/012`:** class constants' visibility isn't checked (`A::PRIVATE` reads from anywhere, so `defined()`
  can't be false for it). The smoke test leaves non-public constants out.
- **`issues/013`:** methods' visibility isn't checked, and a private method isn't resolved in the calling scope (a
  subclass's public `f()` replaces the parent's private one in the parent's `$this->f()`).
- **`issues/014`:** an unqualified call in a namespace of an intrinsic (`strlen`, `count`, ...) ignores a namespaced
  function of that name; and on a shared engine a call that fell back to the global function in one request calls
  a namespaced function declared later in another (php-src keeps each request's first resolution).
- `declare(strict_types=1)` isn't enforced for built-ins' parameters (a TypeError in PHP), so `defined-strict.php`
  tests the literal forms only; and built-ins don't check their argument count (`issues/008`, known).

## Files
- `src/main/java/pl/codeloom/php/nodes/RunOnceCode.java`, `FileRootNode`, `ThunkRootNode`, `IncludeNode`,
  `PhpContext.includedBefore`: target 1.
- `nodes/call/MethodLookupNode.java`, `MethodDispatchNode.java`, `MethodCallNode`, `StaticCallNode`, `NewNode`:
  target 2.
- `nodes/expr/ConstantNodes.DefinedNode`, `Translator.defined`/`definedName`/`compiledSpecially`,
  `CoreFunctions.isDefined`, `PhpLanguage.constantKey`, `PhpContext`'s constants: target 3.
- Tests: `tests/smoke/defined-special.php`, `defined-namespace.php`, `defined-strict.php`, `defined-autoload.inc`,
  `method-dispatch.php`; `src/test/resources/compile/defined.php`, `receivers.php`; `bench/pe-round4/run-once.sh`.
- `bench/pe-round4/`: `runs.sh` and `all.sh` (every run of this page), `abtable.py`, the reports (`d-*`, `s-*`,
  `m-*`, `mem-table.md`, `ab-*.md`, `gate1.txt`, `run-once.txt`).
- `issues/012-014`.
