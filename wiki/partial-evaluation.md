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
- with `TraceMethodExpansion`, the IR nodes by method of the three largest compilations.

`--check` exits 1 past its thresholds: the baseline after the fixes (below), with some room.

| | JVM | native image |
|---|---|---|
| failed compilations and permanent bailouts, other than code too large | 0 | 0 |
| code too large to install | 2 | 0 |
| deopt cycles | 0 | 0 |
| the largest compilation | 60,000 IR nodes | 100,000 |
| the slowest compilation | 20 s | 40 s |
| all compilation in a run | 1,100 s | 1,700 s |
| compilations of one target | 10 | 14 |
| performance warnings | 0 | 0 |

A run named `*-native` gets the native column; `--max-...` overrides one.

**The automatic layer** is `CompilationTest` in `mvn test` (about 30 s). It runs `src/test/resources/compile/*.php`
at both tiers, and Gate 1's kernels (when `bench/gate1/fetch.sh` has fetched them) at the first tier, at low
thresholds and on the running thread. Any failed compilation or performance warning fails the build. Each program is
a construct this page's fixes are about: `spread-arguments.php`, `object-foreach.php`, `nested-isset.php`,
`generic-dispatch.php`, and `basics.php` for the everyday ones. **Before merging a change to the interpreter**, run
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

## What is left open
- **`WP_Query::get_posts` is too large for HotSpot** (92,357 IR nodes, about 900 KB of code; HotSpot installs at
  most 640 KB, `JVMCINMethodSizeLimit`). It stays interpreted on the JVM after one 9 s attempt. Natively it compiles,
  at 17-34 s a time. What is left in it is spread thin: element reads (`SlotCacheNode.lookup`, `isKeyAt`) and the
  holders of arrays, about 20 IR nodes per AST node. Raising the limit isn't a fix; less code per operation is.
- **Graal's implicit-exception speculations recompile big functions.** Natively, every deoptimization of a large
  function is a `BoundsCheckException`, `NullCheckException` or `ClassCastException` speculation failing once per
  site and compilation (`ArithmeticNodes$ConcatNode.concat` into TruffleString's `NumberConversion`, `PhpArray.rawAt`).
  Each costs a recompilation: `get_posts` compiled 10 times in 40 rounds, about 240 s. On HotSpot the same shows
  only as "uncommon trap" deopts of `WP_Theme_JSON::sanitize` (9 compilations).
- **Compilation doesn't settle in 40 rounds** on WordPress: 6 to 50 compilations per round at the end.
- **The bytecode interpreter wasn't diagnosed.** `INTERPRETER=bytecode compile-diag.sh run` runs it; it covers 13%
  of the functions WordPress calls (gate-2.md), so its compilations are a small part of these.
