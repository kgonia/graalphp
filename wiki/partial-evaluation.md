# Partial evaluation: what our interpreter compiles, and how to check it

Truffle compiles a PHP function by partial evaluation (PE): it inlines everything reachable from the root node's
`execute`, folds what is constant for that function, and hands the rest to Graal. Code not written for it makes
the graph explode, fails to compile, or deoptimizes again and again. An outside review reported "compilation
failures and exploding compilations" on our code. This page is how we see them ourselves, what they were, and
what fixed them.

Sources: [PartialEvaluation.md](https://github.com/oracle/graal/blob/master/truffle/docs/PartialEvaluation.md),
[Optimizing.md](https://github.com/oracle/graal/blob/master/truffle/docs/Optimizing.md),
[DeoptCyclePatterns.md](https://github.com/oracle/graal/blob/master/truffle/docs/DeoptCyclePatterns.md) (GraalVM
master, read 2026-10-31).

## The rules, and where they meet our code

| Rule | In our interpreter | What broke it, and the fix |
|---|---|---|
| **PE inlines everything reachable from `RootNode.execute` and uses no host profiles.** A branch it can't fold is compiled on both sides. | Every case a node handles is in its compiled code, unless a profile says it never happened: a DSL specialization, a `BranchProfile`, a `@CompilationFinal` field. | `LocalVarNode.write`: an assignment of a value of unknown type compiled a long, a double and an Object store, each with a reference's write and the old array's release. `FunctionRootNode.releaseLocals`: every local's release at the end of every compiled function. Both now record what they have met in `@CompilationFinal` state. |
| **Runtime code goes behind `@TruffleBoundary`**: JDK collections, strings, I/O, recursion, code with many branches. | `PhpArray`'s hash walk, conversions and error paths already are. | `ForeachNode.iterateObject` iterated a `List<String>` in compiled code: three interface calls PE couldn't inline. `PhpRef.arrayForWrite` and `Variables.separated` inlined a shared array's copy with its holder updates. |
| **`@ExplodeLoop` needs a PE-constant bound**, and applies only to the annotated method. Defend the bound with `CompilerAsserts.partialEvaluationConstant`. | The 44 uses loop over `@Children` or final arrays. | `ArgumentsNode.buildWithSpread` and `valuesWithSpread` loop over the argument nodes without the annotation: `build`'s doesn't reach a callee. |
| **A `VirtualFrame` must not escape** into a call PE can't see through. | A child's `execute(frame)` must be a monomorphic, inlined call. | The same two loops: each argument's `execute(frame)` was a virtual call, the frame escaped, and the compilation bailed out for good. |
| **Every evaluation of a subtree is another copy of its code.** | `isset()`, `??`, compound assignment evaluate places. | `DimNode.readIfSet` fell back to `isSet(frame) ? execute(frame)` for anything but an array, and both evaluated the container again. So `$a[x][y][z] ?? d` compiled 2^depth copies of the lookups below it. It also ran an `ArrayAccess` container's `offsetGet()` and a key's side effects more than once, unlike php-src. |
| **A `finally` block is compiled once per exit**: javac copies it onto the normal and the exceptional path. | | `FunctionRootNode.execute` released the locals in a `finally`: two copies in every compiled function. |
| **Stable state must stabilize**, and a `@CompilationFinal` field folds only on a PE-constant receiver. Deoptimizing on a non-constant one is a deopt cycle. | Our new profiles are bit sets that only grow: each case deoptimizes once per site. | `PhpFunction.getCallTarget` made the call target on first call and deoptimized the caller for it, also through generic dispatch (`call_user_func_array`, a hook's callbacks), where the function isn't a constant. That is DeoptCyclePatterns.md's dispatch-node cycle: `WP_Hook::apply_filters` deoptimized for every callback not called before. |
| **Skipped exceptions deoptimize** (`UnexpectedResultException`, `ArithmeticException`, `IndexOutOfBoundsException`, ...). | Hand-written handlers: `ExprNode.executeCondition`, `PropNode.executeLong`; number parsing is behind boundaries. | None repeating on the pages or the kernels. |

What a compiled PHP function costs, roughly: 10 to 25 IR nodes after PE per AST node, and about 10 bytes of machine
code per IR node. HotSpot doesn't install code over `JVMCINMethodSizeLimit` (640 KB): a function of more than about
60,000 IR nodes after PE never compiles on the JVM.

## How to run it

```
tests/gate2/compile-diag.sh setup            # MariaDB (pe-mariadb) and the site's volume (pe-site)
tests/gate2/compile-diag.sh build-native     # optional: target/native/wp-http-diag, about 16 GB (memory lock)
tests/gate2/compile-diag.sh run              # kernels-jvm wp-jvm kernels-native wp-native, then the report
python -I tests/gate2/compile_report.py target/compile-diag --check
```

- **The setup** runs `tests/gate2/native.sh db` and `volume` under its own prefix (`WPN_NAME`, default `pe`). The
  database is a dump of `gate2-mariadb`'s `wordpress` (`WPN_DB_SOURCE` names another container). Rerun `setup`, or
  just `native.sh volume`, after `mvn package`: the volume holds a copy of `target/classes`.
- **The servers** are `HttpMain` with our parser and the tree interpreter, on CPUs 6-13 (`CPUS`), off the benchmark
  cores. `INTERPRETER=bytecode` runs the bytecode interpreter.
- **`kernels-ENGINE`**: one request runs Gate 1's kernels, 300 iterations each (`/gate1-kernels.php`).
- **`wp-ENGINE`**: rounds of `native.sh`'s 21 pages (front end, login, seven wp-admin pages) until a round finishes
  at most 2 compilations, twice in a row, or 40 rounds (`SETTLE`, `MIN_ROUNDS`, `MAX_ROUNDS`).
- **Then the server closes its engine** (`-Dphp.http.closeFile`): that prints the compilation statistics.
- **The options**: `engine.TraceCompilation`, `engine.CompilationFailureAction=Print`,
  `compiler.TracePerformanceWarnings=all`, `engine.CompilationStatistics`, `engine.TraceTransferToInterpreter` and
  `compiler.NodeSourcePositions`. On 25.4 the PE options are `compiler.*`: the `engine.*` names are deprecated, and
  `compiler.MethodExpansionStatistics` prints nothing at the engine's close here. They go in as
  `-Dpolyglot.engine.X` / `-Dpolyglot.compiler.X`: `HttpMain`'s engine allows experimental options.
- **On the native image** `TraceTransferToInterpreter` prints every deoptimization with its reason and stack trace
  (to the server's output), which needs an image built with `-H:+IncludeNodeSourcePositions`. On HotSpot it prints
  only explicit `transferToInterpreter` calls, so implicit deoptimizations show only as `opt deopt ... uncommon
  trap`.
- **One compilation in detail**: `EXTRA="-Dpolyglot.engine.CompileOnly=sanitize,get_posts
  -Dpolyglot.compiler.TraceMethodExpansion=truffleTier" ... run wp-jvm`. The report then sums each expansion tree by
  the method that made the IR nodes. That is how the 2^depth `isset()` and the variable writes showed.
- **Two runs side by side**: another `WPN_NAME`, `WPN_NET`, `WPN_DB_PORT`, `DIAG_OUT` and `PORT`.

## How to read the report

`compile_report.py` prints, per run:
- **failures and bailouts**, by kind and by the first frame of our code in their stack trace: `frame escapes`,
  `deopt cycle` ("Deopt taken too many times"), `graph too big`, `code too large`, `other`;
- **the largest compilations** by IR nodes after PE (`IR(PE)`), with the IR after Graal, the code size and the time
  (PE + Graal), and **the slowest**;
- **targets compiled, invalidated or deoptimized many times**, with the invalidation reasons;
- **performance warnings** and **transfers to the interpreter**, by our source location;
- on a native run, **every deoptimization** by reason and our location: `TransferToInterpreter` is ours (a
  profile, a cache miss); `BoundsCheckException`, `NullCheckException`, `ClassCastException` are Graal's
  speculations failing once per site;
- with `TraceMethodExpansion`, the IR nodes by method of the three largest compilations;
- on a `wp` run, **the last 10 rounds**: first compilations of a target at a tier (code that reached its call count
  only then) against recompilations (an instability), with the targets recompiled.

`--check` exits 1 past its thresholds: round 2's results (below), with some room.

| | JVM | native image |
|---|---|---|
| failed compilations and permanent bailouts, other than code too large | 0 | 0 |
| code too large to install | 0 | 0 |
| deopt cycles | 0 | 0 |
| the largest compilation | 65,000 IR nodes | 65,000 |
| the slowest compilation | 30 s | 30 s |
| all compilation in a run | 950 s | 950 s |
| compilations of one target | 10 | 10 |
| performance warnings | 0 | 0 |
| recompilations in a `wp` run's last 10 rounds | 2 | 2 |

A run named `*-native` gets the native column; `--max-...` overrides one.

**The automatic layer** is `CompilationTest` in `mvn test` (about 30 s). It runs `src/test/resources/compile/*.php`
at both tiers, and Gate 1's kernels (when `bench/gate1/fetch.sh` has fetched them) at the first tier, at low
thresholds and on the running thread. Any failed compilation or performance warning fails the build. Each program is
a construct this page's fixes are about: `spread-arguments.php`, `object-foreach.php`, `nested-isset.php`,
`generic-dispatch.php`, and `basics.php` for the everyday ones. A program can also hold **IR budgets**, lines
`// IR budget: TARGET MAX`: every compilation of the call target named TARGET (or of the code at `file:line`) stays
at or under MAX IR nodes after PE. `query-builder.php` (a method in the style of `WP_Query::get_posts`) and
`includes.php` (top-level code with 150 includes, as `wp-settings.php`) hold round 2's code per operation. The
test's engine may hold several contexts, as the HTTP server's does, so its numbers are larger than one context's
(`bin-php.sh`): a constant, for one, is read from the running context. **Before merging a change to the interpreter**, run
`mvn test` and, with smoke, phpt and Gate 1, `compile-diag.sh run` with `--check`.

## The baseline (2026-10-31) and what each fix changed
Branch `pe-diag` (on `wp-native`), GraalVM 25.4.4.1.1. Every run: `compile-diag.sh run`, 40 rounds of the 21 pages
(840 requests) and 300 iterations of each kernel. "Before" is `wp-native` with the tooling only.

**What failed and exploded, before:**

| | JVM (CPUs 6-13) | native image (CPUs 0-5) |
|---|---|---|
| permanent bailouts | **6**: 4 code too large (`WP_Theme_JSON::sanitize`, `WP_Theme_JSON::get_block_nodes`, `WP_Query::get_posts`, a file's top level), 2 frame escapes (`user_can`, `wp_count_posts`) | **2**: a frame escape (`user_can`), a compilation that ran out of its 100 s (`WP_Theme_JSON::get_block_nodes` at the last tier) |
| time in failed compilations | 59.6 s | 100 s |
| the largest compilation | 60,718 IR nodes after PE (`WP_Comment_Query::get_comment_ids`), 562 KB of code | 168,032 IR nodes (`WP_Theme_JSON::get_block_nodes`), 1.5 MB of code, 49 s |
| the slowest compilation | 37.5 s (`wp_render_layout_support_flag`, last tier) | 63.6 s (`WP_Theme_JSON::sanitize`, last tier) |
| compilations over 20,000 IR nodes | 58 | 56 |
| performance warnings | **129**: `ArgumentsNode` (spread arguments, 111), `ForeachNode.iterateObject` (18) | none printed (the native image prints none) |
| deopt cycles | none detected; `WP_Hook::apply_filters` compiled 10 times | `WP_Hook::apply_filters` deoptimized 10 times at `PhpFunction.getCallTarget` |
| all compilation | 4,479 compilations, 1,082 s | 2,709 compilations, 1,199 s |

The kernels compiled without a failure or a warning on both: their functions are small, and the largest
compilation was 15,207 IR nodes.

**What exploded, inside:** `TraceMethodExpansion` of the largest functions, before (IR nodes after PE by the method
that made them):
- `WP_Theme_JSON::get_block_nodes` (165,000): `PhpArray.isKeyAt` 19,675, `SlotCacheNode.lookup` 17,091,
  `DimNode.isSet` 12,015, `Convert.toBool` 9,442. Nested `isset()` and `??`, each level copied twice more.
- `WP_Query::get_posts` (132,161 with the `DimNode` fix): `PhpArray.removeHolder` 21,472 in 971 copies,
  `addHolder` 8,478, `PhpRef.set` 4,584. Variable writes of every kind at every assignment, and the locals'
  release, twice.

**The fixes**, one commit each, in order:

| Commit | Root cause | Effect |
|---|---|---|
| `72c2b78` ArgumentsNode | Spread-argument loops not exploded: the frame escaped | `user_can`, `wp_count_posts` compile; 111 warnings gone |
| `7699550` ForeachNode | A JDK list's iterator in compiled code | 18 warnings gone |
| `8388a57` DimNode | `readIfSet` evaluated the container three times per level: 2^depth copies; `offsetGet()` and key side effects ran more than once (a PHP difference: `tests/smoke/isset-once.php`) | `wp_render_layout_support_flag` 57,432 → 30,388 IR nodes; `get_block_nodes` 164,996 → 41,962 |
| `78ac261` PhpFunction | `getCallTarget` deoptimized its caller on a non-constant function: a deopt cycle in generic dispatch | `apply_filters`'s 10 deopts there gone; `generic-dispatch.php`: a "Deopt taken too many times" bailout before |
| `416ae7d` LocalVarNode | Writes of a value of unknown type compiled every case: no profile | with the next: `get_posts` 132,161 → 93,254, `get_comment_ids` 51,508 → 32,991, `sanitize` 53,284 → 36,700 |
| `ef80474` FunctionRootNode | The locals' release in a `finally` (two copies), every slot in each | |
| `ce55f35` PhpRef, Variables | A shared array's copy inlined at every write through a reference | |

**After all of them** (the same runs; the native run on CPUs 6-13, with 8 compiler-thread CPUs against 6):

| | JVM, before → after | native, before → after |
|---|---|---|
| permanent bailouts | 6 → **2** (code too large: `WP_Query::get_posts`, 92,357 IR nodes, and a file's top level) | 2 → **0** |
| time in failed compilations | 59.6 → 18.6 s | 100 → 0 s |
| the largest compilation | 60,718 → 52,413 IR nodes | 168,032 → 92,357 (`get_posts`, which only the native image installs) |
| the slowest compilation | 37.5 → 11.6 s | 63.6 → 33.9 s |
| compilations over 20,000 IR nodes | 58 → 29 | 56 → 38 |
| IR nodes per compilation (mean) | 2,281 → 1,834 | 2,822 → 2,183 |
| performance warnings | 129 → **0** | none printed |
| all compilation | 4,479 compilations, 1,082 → **800 s** | 2,709 → 4,546 compilations, 1,199 → 1,510 s (more compiler threads finished more of the queue in 40 rounds) |
| the most compilations of one target | 10 → 9 (`apply_filters`, `WP_Theme_JSON::sanitize`) | 10 → 10 (`apply_filters`, `get_posts`) |

- **The kernels didn't change:** no failure before or after; the JVM compiled 218 times in 80.5 and 88.7 s (the
  host's load differs from run to run by about that much); the largest compilation is 15,207 and 15,060 IR nodes.
- **Correctness:** `mvn test` (34 tests), smoke 78/78 against PHP 8.5, Gate 1's checksums equal to PHP's over 300
  iterations on both engines, WordPress 19/21 pages equal to PHP on the JVM and natively (the two differ only in
  the oEmbed link, as before), php-src `.phpt` 3,119 of 7,341 strict (42.5%) against 3,110 before: none that passed fails, and six `bug75420` cases (`isset` on `ArrayAccess`) pass now.

**What it does to a visit** (`tests/gate2/native.sh measure 3 200 1 jvm-main jvm-java native-main native-java`,
2026-11-01 01:44–03:05, `CPU_LOCK_HELD=1`):
- `*-main` is before (the tooling commit's classes, `wp-http-main`), `*-java` after (all the fixes, `wp-http`); both
  our parser and the tree interpreter, production builds (no node source positions).
- Three rounds, the variants interleaved, the order reversed every other round; the server on CPUs 14-21, the client
  on 0-5, the database on 22-23; each run on a server just started and a database just reset.
- The host was busy with other sessions (median CPU 26–49% during the runs), so every wait is slower than in
  gate-2.md's runs; the comparison is between interleaved runs. A cell is the median of three rounds (their range).

| | cold visit: wait, CPU-s | warm wait per visit | warm CPU-s per visit | RssAnon warm | memory.peak warm |
|---|---|---|---|---|---|
| JVM, before | 5.51 s (5.39–6.19), 30.1 | 0.677 s (0.655–0.679) | 2.99 | 1331 MB | 2588 MB (2512–2649) |
| **JVM, after** | 5.64 s (5.36–5.70), 30.2 | **0.636 s** (0.631–0.687) | **1.78** | 1067 MB | **1701 MB** (1650–1728) |
| native, before | 2.93 s (2.55–2.96), 9.1 | 1.008 s (0.989–1.067) | 6.12 | 717 MB | 1966 MB (1619–1969) |
| **native, after** | **2.66 s** (2.66–2.96), 8.3 | **0.857 s** (0.849–0.869) | **2.50** | 524 MB | **1094 MB** (1044–1112) |

- **CPU per warm visit is the large change:** 0.60× on the JVM (2.99 → 1.78 CPU-s) and 0.41× natively (6.12 →
  2.50). That CPU is mostly compilation: less code to compile, no failed attempts, no deopt cycle.
  - On the JVM it falls to 2.1 CPU-s from visit 101 on, against 5.0 before.
  - Natively it falls to 2.2 CPU-s at visits 151–200, against 6.3 before, which never fell (gate-2.md's "natively it
    stays at 3.6–4.6"). This is the native image's biggest open problem there, halved.
- **Memory:** `memory.peak` 0.66× on the JVM (2588 → 1701 MB) and 0.56× natively (1966 → 1094 MB). Runtime
  compilation's memory isn't capped by `-Xmx` (gate-2.md): smaller graphs, smaller peaks.
- **Wait per warm visit:** 0.94× on the JVM, 0.85× natively. Every page is faster natively (home 138 → 114 ms,
  dashboard 135 → 118 ms) and on the JVM (home 81 → 76 ms).
- **Cold visit:** native 0.91× (2.93 → 2.66 s); the JVM's is level (5.51 → 5.64 s, the ranges overlap).
- **The kernels'** compilations didn't change (above), so Gate 1's steady state wasn't measured again.

## Round 1's open items
These were left open after round 1; round 2 (below) took them up.
- **`WP_Query::get_posts` was too large for HotSpot** (92,357 IR nodes, about 900 KB of code; HotSpot installs at
  most 640 KB, `JVMCINMethodSizeLimit`), and so was `wp-settings.php`'s top level. Both stayed interpreted on the
  JVM after a 9-11 s attempt. Raising the limit isn't a fix; less code per operation is.
- **Graal's implicit-exception speculations recompiled big functions**, natively: `get_posts` compiled 10 times
  in 40 rounds.
- **Compilation didn't settle in 40 rounds.**
- **The bytecode interpreter wasn't diagnosed.**

## Round 2 (2026-11-01): code per operation, the implicit-exception recompiles, settling
Branch `pe-size` (on `pe-diag`), GraalVM 25.4.4.1.1, the same runs as round 1. "Before" is `pe-diag` with this
round's tooling only (`fb2d6cf`).

**Where the code went.** `TraceMethodExpansion` of `get_posts` (91,450 IR nodes after PE) and of
`wp-settings.php`'s top level (66,982, 1,072,192 bytes of code), summed by the method that made the nodes. There
was no one large mistake. Each operation compiled a few dozen nodes more than it needed, at hundreds of sites:
- **A string-key read** (`$q['x']`, about 500 in `get_posts`) inlined `PhpArray.isKeyAt`, `hashValueAt`, the
  miss's lookup and two null tests: about 64 IR nodes.
- **Each `.`** inlined TruffleString's `ConcatNode`: code ranges, the short result's copy and the rope, 30 to 75 IR
  nodes. An int operand added its digit loop (`FromLongNode`), about 85 more.
- **Each interpolation** (`"... {$x} ..."`) built a `TruffleStringBuilder`, with an inlined append per part: about
  30 IR nodes a part, about 6,000 in `get_posts`.
- **Each `include`** made five boundary calls, looked up the call target, built an arguments array and called
  through an `IndirectCallNode`. `wp-settings.php` has 340 of them, each with a path built as
  `ABSPATH . WPINC . '/x.php'`.
- **Property writes and writes through references** compiled every case at every site: a reference's write,
  `__set()` and both holder updates. `new` stored each property default by testing for an int, an array and
  anything else; `new WP_Query` has 63 properties.
- **Holders, releases and reads:**
  - every holder update (`addHolder`, `removeHolder`, `isShared`) read two fields and tested both;
  - element writes and `foreach` held their array in a `try`/`finally`, which is compiled twice;
  - a local's release compiled both releases, the array's and the reference's, per slot;
  - reads of a local tested the long, double and object tags in turn;
  - `Convert.toLong` and `toDouble` inlined their whole chain of type tests at every conversion.
- **`in_array`, `array_slice`, `array_map`, `array_filter` and `call_user_func_array`'s arguments** walked the
  array through the generic accessors, with a layout test per element. These were also the recompiles (below).

**The recompiles.** Natively, nearly every deoptimization of a large function was one of Graal's
implicit-exception speculations failing (`BoundsCheckException`, `NullCheckException`, `ClassCastException`). Each
fails once per site and compilation, and each costs a recompilation of the whole function. There were 58 of
them, of 93 deoptimizations:
- **TruffleString's own code** made 28 of the 33 `BoundsCheckException`s: `NumberConversion`, reached through
  `ConcatNode.concat` (an int operand, `FromLongNode`) and the builder's `AppendLongNumberNode`. It deoptimized
  because we compiled it into each site.
- **`PhpArray.rawAt`** in loops over an array of any layout made the 16 `ClassCastException`s and most
  `NullCheckException`s: `in_array` 15, `call_user_func_array`'s arguments 5 (in `WP_Hook::apply_filters`),
  `array_slice` 2 (in `WP_Theme_JSON::sanitize`). Graal moves the layout's type checks and the index's bounds out
  of the loop as speculations, and these fail on a layout or a size the site hadn't met.
- **On HotSpot** the same failures show as "uncommon trap" deopts. `-Xlog:deoptimization=debug` prints them, and
  its `cid` is the compilation's `CompId` in `TraceCompilation`.
  - `WP_Theme_JSON::sanitize`'s were `ArraySliceNode`'s `null_check` (fixed).
  - Two more were guards that Graal's speculative guard movement (SGM) hoisted to a loop's entry:
    `ForeachNode.runArray` (bci 20, after `firstPosition()`) and `ElementReadNode.otherKey`.
  - With `-Djdk.graal.SpeculativeGuardMovement=false` (one run), neither appeared, and `sanitize` compiled 4 times
    in 6 rounds instead of 8.

**The fixes**, one commit per cause. The columns are `CompilationTest`'s programs at their first compilation, in
IR nodes after PE, with one context (`bin-php.sh`): `query-builder.php`'s `Query::sql` and `includes.php`'s top
level. A dash means no change there; that fix is for constructs the programs don't have.

| Commit | Cause, and the fix | `Query::sql` | includes |
|---|---|---|---|
| `fb2d6cf` | before | 44,450 | 14,790 |
| `c4626f5` ElementNodes | A string-key read: one check of the remembered slot inline, a miss one call that also gives PHP null | 41,402 | – |
| `9fe0ba9` PhpArray | The holder count marks a frozen array (`Integer.MIN_VALUE`): one field and one test per holder update | 40,807 | – |
| `23957db` Interpolation, implode | The parts joined in one call (`Str.join`: one exact array, one copy per part), not an inlined builder | 32,825 | – |
| `f6b2a0c` ConcatNode | `.` one call, an int operand formatted in it: no digit loop per site, nor its bounds-check deopts | 27,010 | 17,639 (code 322 → 191 KB) |
| `b2d18f4` ConcatChainNode | `a . b . c` with three or more operands is one join, evaluated and converted in php-src's order | 26,627 | 15,539 |
| `38f8204` IncludeNode | An include is two calls (resolve, run); the included file runs with the include as encapsulating node | – | 6,108 |
| `3ab330d` PropNode, RefWrites | Property writes and writes through references compile only the cases met (`@CompilationFinal` bits) | – | – |
| `f5367db` DimNode, ForeachNode | A held array let go once on each exit (catch and rethrow, out of line); a write holds only an array | 26,659 | – |
| `b9eb18e` FunctionRootNode | The locals' release compiles per slot only the kinds released there | – | – |
| `061d3d2` InstantiateNode | With several contexts, `new` stores each default by the kind the defaults have had | – | – |
| `c83f749` LocalVarNode | Reads test only the tag of the slot's kind, a compile-time constant | – | – |
| `40c0bac` Convert | `toLong`, `toDouble`: an int and a float inline, anything else one call | 26,226 | – |
| `cdce63b` array walks | `array_slice` and `call_user_func_array`'s arguments loop once per layout; `in_array` is one call | – | – |
| `7125071` array_map, array_filter | The entries taken in one call, the result built in one, around a loop that only calls back | 23,450 | – |
| `534819b` ConcatNode | Two strings concatenate inline again: out of line cost Gate 1's slugs 8%, for 1.5% less code | – | – |

`CompilationTest` holds the two as budgets, in its own engine, which may hold several contexts as the HTTP server's
does:
- `Query::sql`: 44,641 → 23,641 IR nodes (448 → 236 KB), budget 26,000.
- `includes.php`'s top level: 26,192 → 16,011 (465 → 173 KB), budget 18,000. There a constant is read from the
  running context, about 25 IR nodes a read: 7,600 of the 16,011.

**After all of them** (`compile-diag.sh run`, the same rounds and kernels as round 1, CPUs 6-13; the JVM run shared
the host with a phpt run for its first 20 minutes):

| | JVM, before → after | native image, before → after |
|---|---|---|
| failed compilations | 2 code too large (`get_posts`, `wp-settings.php`) → **0** | 0 → 0 |
| `WP_Query::get_posts` | not installed (91,450 IR nodes, 9 s lost per attempt) → **60,182 IR nodes, 586 KB, installed** | 93,012 IR, 905 KB, **compiled 10 times** → 61,657 IR, 564 KB, **twice** (both tiers) |
| `wp-settings.php`'s top level | not installed (1,072 KB) → **38,609 IR, 514 KB, installed** | 74,392 IR, 1,070 KB → 40,929, 448 KB |
| the largest compilation installed | 52,413 IR (`wp_default_scripts`, now 29,816) → 60,182 (`get_posts`) | 93,012 → 61,657 |
| IR nodes per compilation (mean); over 20,000 | 1,833; 28 → **1,601; 23** | 2,203; 38 → **1,700; 24** |
| the slowest compilation | 12.9 s (`sanitize`) → 25.1 s (`get_posts` at the last tier, which never compiled before) | 27.5 → 24.1 s (`get_posts`) |
| all compilation | 4,492 compilations, 916 s + 23 s failed → 4,433, **791 s** | 4,542, 1,217 s → 4,436, **777 s** |
| invalidations; deoptimizations | 27; 80 → 32; 53 | 106; 93 → **59; 48** |
| implicit-exception deoptimizations | (HotSpot: uncommon traps) | **58 → 10**: bounds checks 33 → 0, class casts 16 → 1, null checks 9 → 9 |
| the most compilations of one target | 11 (`apply_filters`) → 9 (`apply_filters`, `sanitize`) | 10 (`apply_filters`, `get_posts`) → 8 (`apply_filters`) |
| **the last 10 rounds** | 295 first compilations, **6 recompilations → 275, 0** | 378, **8 → 276, 0** |

- **Both now compile on HotSpot.** `get_posts` installs at 586 KB of HotSpot's 640 KB, a margin of 9%. Its last
  tier takes 25 s, a background compilation once per server.
- **Compilation settles.** In the last 10 rounds nothing compiles a second time on either engine. What still
  compiles there (about 28 a round) is code reaching its call count late, not instability.
  - Natively, the 10 implicit-exception deopts left are null checks: `PhpArray.getRaw` (6, an element read's guard
    hoisted out of a loop) and `ArraySliceNode.packed` (2, in `sanitize`). The second fires for an empty slice,
    where the `Object[]` loop's store test is hoisted although the loop never runs.
- **The kernels:**
  - the JVM compiled 220 → 214 times, in 87.7 → 66.9 s; natively 241 → 218 times, in 144 → 83 s, with 45 → 17
    deoptimizations;
  - the largest kernel compilation is 15,060 → 13,324 IR nodes.
- **`compile_report.py --check`** has round 2's thresholds: no code too large, at most 65,000 IR nodes in a
  compilation, 30 s for one, 950 s for a run, 10 compilations of one target, and 2 recompilations in the last 10
  rounds. The table under "How to read the report" lists them. `pe-diag`'s runs fail these thresholds, and round 2's
  runs pass them.

**Gate 1** (`bench/gate1/ab.sh`, 400 iterations, interleaved, CPUs 14-21, `CPU_LOCK_HELD`). The host was shared,
and the spread between runs is ±15%. Two A/Bs of the final build against `pe-diag`, in all-kernels mode:

| kernel | A/B 1: after / before (IQR) | A/B 2 |
|---|---|---|
| hooks | 1.01 (0.90-1.20) | 0.97 (0.96-1.39) |
| escape | 1.06 (0.80-1.12) | 1.00 (0.97-1.14) |
| slugs | 1.05 (0.96-1.24) | 1.01 (0.94-1.17) |
| wpautop | 1.00 (0.70-1.09) | 1.00 (0.93-1.14) |
| wptexturize | 0.85 (0.56-1.12) | 1.07 (0.94-1.08) |
| blocks | 1.03 (0.83-1.19) | 1.08 (0.90-1.25) |
| kses | 1.11 (1.05-1.24) | 0.91 (0.87-1.05) |
| peak memory | 1.05 (0.91-1.07) | 1.08 (1.02-1.11) |

- **No kernel is slower beyond the spread.** kses was 1.11 in two all-kernels runs (one of them before
  `534819b`), and 0.91 in a third and 0.88 alone.
- **slugs alone** (8 rounds): `f6b2a0c`'s out-of-line pair concatenation was 1.08 (IQR 0.98-1.17); with `534819b`
  it is 1.02 (1.00-1.05).
- **The checksums equal PHP's** on both engines: 2,100 kernel iterations each in the diagnosis runs, and every A/B
  run on the JVM.
- **Peak memory:** on the three kernels alone, it was 27-35% higher from `23957db` on.
  - The GC logs show the same allocation (kses, 400 iterations: 17.8 and 18.1 GB) and the same live set (18-19 MB
    after a young GC).
  - The difference is when G1's first concurrent cycle, which a Metaspace threshold triggers, lets the heap shrink.
  - On all kernels the ratio is 1.05-1.08.

**Correctness:**
- **`mvn test`:** 36 tests, 0 failures. 2 skipped: the parser's reference corpus isn't generated in this worktree.
- **Smoke:** 77/78 against PHP 8.5. `stdstreams` fails before and after alike: `ftell` on `php://stdin` in the
  container (`int(9)` against `NULL`).
- **WordPress:** 19/21 pages equal to PHP on the JVM and natively. The two differ only in the oEmbed link, as
  before.
- **php-src `.phpt`:** 3,116 against 3,117 strict.
  - The one case is `in_array_variation1.php`, which timed out at 10 s once while the host ran the diagnosis beside
    it.
  - Run alone, its output is identical on both builds, and its time varies with the host's load (4-12 s on both).

**What it does to a visit** (`tests/gate2/native.sh measure 3 200 1 jvm-main jvm-java native-main
native-java`, 2026-11-01, `CPU_LOCK_HELD=1`). `*-main` is before: `pe-diag`'s classes and `wp-http-main`, which are
round 1's result. `*-java` is after (`wp-http`). Both are production builds. There were three rounds, interleaved,
with the order reversed every other round. The server ran on CPUs 14-21, the client on 0-5 and the database on
22-23. The host's median CPU during the runs was 29-44%. Each cell is the median of three rounds, with their range.

| | cold visit: wait, CPU-s | warm wait per visit | warm CPU-s per visit | RssAnon warm | memory.peak warm |
|---|---|---|---|---|---|
| JVM, before | 5.72 s (5.21–6.68), 30.5 | 0.666 s (0.659–0.692) | 1.78 | 1062 MB | 1655 MB (1595–1712) |
| **JVM, after** | 5.58 s (4.76–5.80), 29.8 | **0.650 s** (0.641–0.667) | 1.74 | 1028 MB | **1583 MB** (1574–1667) |
| native, before | 2.95 s (2.48–3.03), 9.1 | 0.916 s (0.915–0.921) | 2.70 | 611 MB | 1226 MB (1120–1263) |
| **native, after** | 2.96 s (2.94–3.06), 9.0 | **0.882 s** (0.777–0.886) | **2.04** | **505 MB** | **876 MB** (810–891) |

- **Native, the large change:**
  - warm CPU per visit is 0.76× (2.70 → 2.04 CPU-s) and memory.peak 0.71× (1226 → 876 MB);
  - the CPU falls to about 2 CPU-s from visit 101 on, where before it stayed at 6 until visit 150. That CPU is
    compilation: `get_posts` compiles twice instead of ten times, and nothing compiles again;
  - the warm wait is 0.96× (home 125 → 121 ms, dashboard 126 → 120 ms).
- **JVM:**
  - the warm wait is 0.98× (home 82 → 76 ms) and memory.peak 0.96×;
  - CPU per warm visit is level (1.78 → 1.74), and so is the cold visit (5.72 → 5.58 s, the ranges overlap);
  - `get_posts` and `wp-settings.php` run compiled now, where before they stayed interpreted. Compilation still
    ends sooner: visits 76-125 cost 3.75 and 1.71 CPU-s, against 4.81 and 2.41 before.

**The bytecode interpreter** (`INTERPRETER=bytecode compile-diag.sh run wp-jvm`, 12 rounds, notes only, no fixes):
- **Its compilations are mostly the tree's.** It covers about 13% of the functions WordPress calls (gate-2.md). Its
  largest compilations are the tree's: `get_posts` (59,937 IR nodes, installed), `sanitize`, the top-level files.
- **No failures.** 1,903 compilations in 436 s, no failure or bailout, no performance warning. The tree run had
  1,930 compilations at the same round.
- **It settles later.**
  - Round 12 still compiled 95 targets, against 64 on the tree.
  - The last 10 rounds recompiled 9: `sanitize` 5, `wpdb::query` 2, `WP_Hook::apply_filters` and a built-in's root
    1 each.
  - `apply_filters` compiled 11 times with 9 deopts, 2 of them on "typed object location generalizing object
    type" (a property's shape generalizing), against 9 compilations on the tree.
  - `--check` fails it on these two counts.

## What is left open
- **`get_posts` is 9% under HotSpot's limit.**
  - What is left in it is spread over about 9,400 AST nodes, about 6.4 IR nodes each.
  - The next costs are the element reads' slot check and the unattributed frame state (about a fifth of the IR
    nodes after PE).
  - With several contexts, a constant read costs about 25 IR nodes (`PhpContext.get`, the context's slot array,
    the stable-value test): 300 of them are 7,600 IR nodes in `includes.php`.
- **Speculative guard movement still recompiles `WP_Theme_JSON::sanitize`** (7 to 9 compilations). Its hoisted
  null checks in `foreach` and element reads fail once per compilation.
  - SGM is a global Graal option that speeds up loops everywhere, so turning it off is the owner's choice and wasn't
    measured.
  - The `array_slice` case has a local fix: return early from an empty slice.
- **`include` in a PHP exception's trace** prints its path (`require('/srv/...')`) where php-src prints `require()`.
  This was so before round 2.
- **`$GLOBALS['x'] += 1`** is unsupported ("Unsupported compound assignment"), as before: `includes.php` writes
  `$GLOBALS['count'] = $GLOBALS['count'] + 3` instead.
