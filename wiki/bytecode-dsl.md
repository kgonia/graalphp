# Bytecode DSL gate: does a bytecode interpreter cut WordPress's cold-code cost?

Started 2026-10-30. D6 deferred the Bytecode DSL to Gate 2 "if warmup is the problem", and Gate 2 says it is: a warm server spends 6.1 CPU-s per visit against PHP's 0.46 (13×) and still compiles ~40 call targets per visit (gate-2.md, "Whose memory"). This gate decides whether Truffle PHP's interpreter moves to the Truffle Bytecode DSL (`com.oracle.truffle.api.bytecode`, in GraalVM 25.0.4, experimental API).

## Question
With the same runtime (values, arrays, classes, built-ins) and the same compiled peak, does a Bytecode DSL interpreter make WordPress's cold and lukewarm code cheaper enough to matter, without losing peak speed or memory?

## What the DSL would change, and what not
- **Interpreter speed:** one dispatch loop with quickening and boxing elimination instead of a virtual `execute` per node; an uncached tier (`enableUncachedInterpreter`) that runs a function's first calls without allocating specialization state. WordPress runs most of its code rarely, which is where this counts. Not measured yet: this gate measures it.
- **Peak:** partial evaluation of the bytecode loop reaches the same compiled code as the tree (D6), so Gate 1's steady state shouldn't move. This gate checks that it doesn't.
- **Memory:** small. The warm server's histogram (`target/memory/video-final/graal-warm-memory.txt`) holds 25 MB of `pl.codeloom.php.nodes.*` and 18 MB of `com.oracle.truffle.*` out of 948 MB RssAnon; bytecode would save part of that. The large items (machine code, libgraal, unparsed php-ast) don't depend on the interpreter.
- **Correctness:** generators. A `yield` (`enableYield`) suspends with the bytecode index and the operand stack in a `ContinuationResult`, as php-src keeps a generator's `zend_execute_data`, which fixes issue 004 and removes `GeneratorNodes`' resumable statement variants. Fiber, which suspends a whole stack, isn't covered.
- **Control flow:** `break n`, `continue n`, `goto` and `return` become branches instead of `ControlFlowException`s; try/catch/finally are the builder's own operations.

## Design for the gate
- **Function by function.** A `PhpFunction`'s call target is either today's `FunctionRootNode` or a bytecode root. Both take the same arguments array, so calls between them need nothing. With `-Dphp.interpreter=bytecode`, every function the bytecode translator supports gets a bytecode root and the rest stay on the tree; the run reports how many fell back and why. The two side by side are a means of measuring, not an end state: Go means one interpreter.
- **One copy where the DSL allows it.** Operators, comparisons and conversions are the existing nodes through `@OperationProxy` (made for migrating AST interpreters). It needs static specializations, so `this` in a specialization becomes a `@Bind Node`; the tree keeps using the same classes, so a fix there lands in both interpreters. The probe on branch `bytecode-dsl` (`27f6d1c`) runs `ArithmeticNodes.AddNode` so, quickened to `addExact` on unboxed longs.
- **Two copies everywhere else, while the gate runs.** Whatever is a node with children or frame access today is written again for bytecode: write contexts and references, the variable stores that move copy-on-write holder counts, control flow, generators, `compact`/`extract`/`$$name`, and backtraces. That is where silent differences between the two would come from, so shared logic moves into static helpers both call wherever the APIs allow (frame slot and `LocalAccessor` differ), and no measurement counts until the bytecode path passes the same checks as the tree: the smoke tests, Gate 1's checksums, php-src's tests (no new failures against the tree), and the copy-on-write cases of `tla-cow.md`, all run with `-Dphp.interpreter=bytecode`.
- **What the translator emits:** blocks, `IfThenElse`, `While`, `Branch` to labels for break, continue and goto, `TryCatch`/`TryFinally` with `TryNode.setPrevious` for PHP's exception chaining, `Return`, `Yield`. PHP variables are `BytecodeLocal`s; a reference (`PhpRef`) is a value in a local, as today in a frame slot. The builder's own `StoreLocal` can't write them: a PHP assignment writes through a reference and moves copy-on-write holder counts (`LocalVarNode.write*`), so loads and stores are operations over a `LocalAccessor`, which keeps boxing elimination. Releasing the locals' arrays on exit (`FunctionRootNode`'s epilogue today) is an `@EpilogReturn`/`@EpilogExceptional`.
- **The hard part, a step of its own:** write contexts (`$a['x'][] = 1`, `$o->p[$k] .= 's'`, by-reference arguments and foreach), today `LValueNode`'s `write`/`executeRef` family. They become fetch-for-write operations returning the container to write into. By-reference arguments follow php-src: the callee is resolved before its arguments (INIT_FCALL), and an argument that is a variable is sent by reference or by value as the callee's signature says (SEND_VAR_EX). K0 already writes elements (`$q[$i] = ...`), so S1 needs the simple cases; the rest is step S2a, checked against `tla-cow.md`'s rules before the kernels that need it are measured.
- **Parsing and source information:** the DSL keeps the parser (the lambda that drives the builder) alive with the code, to run it again when a root needs metadata it was built without: source sections (`BytecodeLocation.ensureSourceInformation()`) or instrumentation tags. That is the idiomatic way to have line numbers without paying for them: a throw records only its bytecode index, and the first error or backtrace through a function reparses that function once with sources. Two consequences for us:
  - The parser must not capture the function's php-ast, or `b6000dd` (52 MB of trees held by `PhpFunction`) comes back: it holds the function's `AstBody` (`0326ad2`: the body's byte range in the file's export, whose bytes are kept from the include, so the body is the included file's whatever happens to the export later) and reads the tree again with `Ast.body()` each time it runs. With our own parser the range is in the source text, which `Source` holds anyway.
  - It must be idempotent: a second run must emit the same bytecode and not repeat side effects. Today's `Translator` creates `PhpFunction`s for nested functions and closures (`declareFunction`, the closure path) and numbers hidden slots while it walks; a reparse would make new ones. Those become values made once per AST position and looked up on later runs.
- **Frame introspection:** backtraces (`Backtrace.Callee`), `func_get_args`, `compact`/`extract`/`$$name` and `include` inside a function read or write locals by name. The bytecode root implements the same interfaces; local access by name goes through `BytecodeNode`'s local accessors. Until it does, such functions fall back to the tree.

## Steps
1. **S1, spike on K0** (the Benchmarks Game kernels: functions, numbers, arrays, loops). It answers whether `@OperationProxy` takes our nodes, whether the two roots call each other freely, and the interpreter speed ratio. Measured with compilation off (`-Dpolyglot.engine.Compilation=false`) and on.
2. **S2a, write contexts and references** in full (nested elements and properties, compound assignments, by-reference arguments, foreach by reference, `global`, `static`), with the copy-on-write cases.
3. **S2, Gate 1 coverage:** K1–K7 (classes, closures, static variables, references, foreach by reference, try, built-ins), fallbacks reported per kernel; checksums equal PHP 8.5's.
4. **S3, generators:** `yield` and `yield from` on continuations; `tests/smoke/generators*.php`, `finally-chain.php` and php-src's `Zend/tests/generators` must not get worse, and issue 004's cases must pass.
5. **S4, WordPress:** Gate 2's visits (`tests/gate2/memory.sh`) with every supported function on bytecode.

## Method
As Gate 1 and Gate 2: interleaved A/B rounds (tree, bytecode, tree, …) of the same build, the flag as the only difference, on CPUs 14-21; peak memory (cgroup `memory.peak`) next to every time. Per kernel: steady state, the warmup curve and the time of the first iterations; for S4, CPU-s per visit over many visits, the wait per visit and RssAnon.

## Go / no-go (proposed; the owner sets the numbers)
- **Go**, migrate everything and delete the tree, if S4's CPU per warm visit falls by at least 25% and the cold visit's wait doesn't grow; Gate 1's steady state stays within ±5% on every kernel; peak memory doesn't grow; smoke and phpt don't get worse.
- **No-go:** stay on the tree, and fix issue 004 there by spilling evaluated operands to hidden slots.
- **Stop early** if S1 shows the interpreter under 1.3× the tree's speed with compilation off: the rest wouldn't pay for the port.

## S1 results (2026-10-30, branch `bytecode-s1`)

**Verdict: the stop rule applies.** With compilation off, the bytecode interpreter runs K0 at **0.72× the tree's speed** (geometric mean over the ten kernels, same build, the option the only difference; 0.69× against main's tree). The rule asks for at least 1.3×. The bytecode interpreter is faster only on binarytrees (1.24×) and 1.7–2.2× slower on the arithmetic loops (intloop, floatloop, sieve, bubblesort). It doesn't reach the tree's compiled peak either (0.91×), and in a fresh JVM its first iteration costs 0.4–0.8 s more on every kernel. By the gate's own rule the rest of the port wouldn't pay for itself. Stopping is the owner's decision. **Update 2026-10-31:** most of the gap was the DSL version and the measurement. On GraalVM 25.4, with two fixes and the kernels in one JVM, the bytecode runs at 1.14× the tree, and cold code still costs it more; see "Why S1 is slower" below.

### What runs on bytecode
`-Dpolyglot.php.Interpreter=bytecode` (default `tree`) sends each function the new `BytecodeTranslator` covers to a `PhpBytecodeRootNode`. The rest stay on the tree, and the two call each other freely. `-Dphp.bytecode.report=true` lists every function's interpreter, with the reason for each fallback. Only functions are translated: a script's top-level code stays on the tree.

| K0 kernel | on bytecode | on the tree |
|---|---|---|
| fannkuch | `k0_fannkuch` | |
| spectralnorm | `k0_spectralnorm`, `k0_A`, `k0_Av`, `k0_Atv` | |
| binarytrees | `k0_binarytrees`, `k0_bottomUpTree`, `k0_itemCheck` | |
| nbody | `k0_energy` | `k0_nbody` (foreach by reference, S2a): this kernel's time is mostly the tree's |
| fib, intloop, floatloop, mandelbrot, sieve, bubblesort | `k0_<kernel>` | |

The ten kernel closures run on bytecode too: 25 of K0's 26 functions. Gate 1's kernels (S2's target, not S1's) have 18 of 102 functions on bytecode. The other 84 fall back for `global` (11), `static`, casts, `isset`/`empty`, `new`, property access and method calls, and the built-ins the tree compiles in place.

### Validation
- **Smoke:** 77 of 77 pass on bytecode, and 77 of 77 on the tree.
- **Checksums:** K0 and Gate 1 (all kernels, 3 iterations) match PHP 8.5.12's on bytecode, with and without the uncached tier, and on the tree.
- **phpt** (`--reuse-oracle`, CPUs 6-13, one build (`3591886`) with `tree` and with `bytecode`): tree 3112 pass, bytecode 3114. No case passes on the tree and fails on bytecode. The two cases bytecode passes in addition are chance: one depends on `rand()` (`non-rep-float-as-int-extra1`), the other is top-level code timed against a limit (`pcre/bug72685`), so it runs on the tree in both runs. The tree fails exactly main's cases, less one more random one (`value_unknown_at_compile_time_001`). On `f0cacf9` the bytecode run had found three real differences, fixed there: a typed read of an unset variable, `$this` or a superglobal as a parameter or capture, and `self` in a callable named from a bytecode method.
- **Copy-on-write (`tla-cow.md`):** `refcopy`, `ref-release` and `foreach-ref-follows` pass on bytecode, but most of their functions use references and stay on the tree until S2a. `tests/smoke/bytecode-cow.php` covers what S1 translates: copies, element writes, unset, calls by value and by reference, returns, and foreach by value. It has 12 functions on bytecode that call tree functions and are called by them.
- **Unit tests:** `PhpBytecodeRootNodeTest` builds operations by hand and runs them on both tiers (uncached, then cached, also switching in the middle of a loop).
- **Errors and traces** from bytecode functions (the `traces`, `exception-location` and `errors` smoke tests): lines come from the reparse with sources (`ensureSourceInformation`) and match PHP's.

### Interpreter speed, compilation off
`-Dpolyglot.engine.Compilation=false`, 10 iterations per run, 6 interleaved rounds, each kernel in its own JVMs, CPUs 14-21. Columns:
- **Steady state:** each run's median of its last quarter of iterations, then the median over rounds (IQR over rounds).
- **Speed:** tree time ÷ bytecode time per round, median and IQR; above 1 means bytecode is faster.
- **"uncached 16":** bytecode with the uncached tier on (see below).
- **Pairs (first iteration, first 10, peak memory):** tree / bytecode; peak memory is the container's `memory.peak`.

| kernel | tree ms | bytecode ms | **bytecode speed** | vs main's tree | uncached 16 | first iteration ms | first 10 ms | peak MB |
|---|---|---|---|---|---|---|---|---|
| binarytrees | 146 (137–149) | 117 (112–122) | **1.24× (1.13–1.33)** | 1.25× (1.09–1.31) | 1.16× (1.02–1.25) | 400 / 782 | 1890 / 2031 | 647 / 645 |
| bubblesort | 47.2 (46.1–50.9) | 103 (98.9–109) | **0.45× (0.42–0.51)** | 0.48× (0.46–0.57) | 0.40× (0.37–0.42) | 163 / 881 | 725 / 2018 | 662 / 430 |
| fannkuch | 393 (375–413) | 482 (471–509) | **0.81× (0.80–0.87)** | 0.73× (0.70–0.76) | 0.80× (0.78–0.84) | 562 / 1279 | 4197 / 5769 | 400 / 204 |
| fib | 37.2 (35.6–39.3) | 46.2 (41.0–47.5) | **0.84× (0.80–0.87)** | 0.98× (0.87–1.12) | 0.75× (0.72–0.78) | 104 / 461 | 439 / 1026 | 637 / 642 |
| floatloop | 46.7 (45.0–47.7) | 85.3 (83.8–89.5) | **0.54× (0.51–0.56)** | 0.54× (0.51–0.57) | 0.50× (0.45–0.55) | 164 / 1093 | 643 / 2121 | 192 / 232 |
| intloop | 56.2 (55.7–56.8) | 110 (99.0–120) | **0.55× (0.49–0.57)** | 0.47× (0.46–0.65) | 0.54× (0.53–0.55) | 136 / 1057 | 696 / 2334 | 229 / 203 |
| mandelbrot | 199 (190–200) | 262 (240–320) | **0.77× (0.60–0.78)** | 0.57× (0.51–0.61) | 0.74× (0.57–0.82) | 378 / 1433 | 2282 / 4644 | 721 / 540 |
| nbody | 121 (103–129) | 123 (122–143) | **0.95× (0.81–1.05)** | 1.02× (0.90–1.06) | 0.97× (0.91–1.04) | 352 / 709 | 1678 / 2044 | 644 / 649 |
| sieve | 36.7 (36.0–37.9) | 65.2 (61.7–65.7) | **0.59× (0.54–0.60)** | 0.58× (0.57–0.61) | 0.57× (0.49–0.59) | 161 / 928 | 507 / 1600 | 457 / 454 |
| spectralnorm | 389 (359–428) | 470 (454–487) | **0.82× (0.74–0.94)** | 0.66× (0.58–0.69) | 0.79× (0.78–0.97) | 620 / 1270 | 4143 / 5698 | 747 / 948 |
| **geomean** | | | **0.72×** | 0.69× | 0.69× | | | |

Where the time goes (JFR, compilation off, on bytecode):
- **intloop:** 56% of the samples are frame stores, the operand stack's pushes (`setLong`) and the clearing of popped slots. The tree passes those values as Java return values. Another 27% is the dispatch loop.
- **mandelbrot:** reading variables takes 23%, writing them 11%, and the loop 24%. Each variable access is an operation of our own: a cached node and a `LocalAccessor`, where the builder's `LoadLocal` would be a plain frame read.
- **The host compiler matters:** with C2 compiling the interpreter (`-XX:-UseJVMCICompiler`), intloop on bytecode is 4.6× the tree's time (677 against 146 ms), against 2× with Graal, the default. Native image (host inlining) wasn't measured then; it is now ("Why S1 is slower", H8).

### Peak and warmup, compilation on
100 iterations per run, 8 rounds, the same columns:

| kernel | tree ms | bytecode ms | **bytecode speed** | vs main's tree | uncached 16 | first iteration ms | first 10 ms | peak MB |
|---|---|---|---|---|---|---|---|---|
| binarytrees | 9.51 (9.16–10.1) | 13.3 (12.9–13.6) | **0.71× (0.68–0.74)** | 0.70× (0.67–0.71) | 0.72× (0.64–0.72) | 187 / 837 | 423 / 1146 | 1007 / 974 |
| bubblesort | 1.03 (0.92–1.09) | 1.17 (1.10–1.27) | **0.85× (0.81–0.86)** | 0.88× (0.76–1.11) | 0.01× (0.01–0.01) | 165 / 833 | 299 / 1101 | 516 / 462 |
| fannkuch | 14.6 (14.1–15.8) | 16.4 (15.3–17.0) | **0.90× (0.87–0.93)** | 0.84× (0.80–0.96) | 0.56× (0.53–0.60) | 694 / 1144 | 1695 / 1911 | 384 / 334 |
| fib | 1.11 (1.05–1.13) | 0.92 (0.87–0.94) | **1.22× (1.20–1.26)** | 1.27× (1.18–1.30) | 1.24× (1.22–1.30) | 92.1 / 530 | 141 / 627 | 560 / 563 |
| floatloop | 2.86 (2.68–2.95) | 2.68 (2.55–2.78) | **1.06× (1.01–1.09)** | 0.93× (0.89–0.94) | 1.01× (0.92–1.02) | 157 / 622 | 228 / 728 | 243 / 281 |
| intloop | 9.29 (8.95–9.52) | 9.56 (9.24–10.1) | **0.97× (0.94–1.01)** | 1.00× (0.95–1.03) | 0.97× (0.94–1.01) | 145 / 720 | 274 / 940 | 233 / 268 |
| mandelbrot | 6.96 (6.46–8.48) | 8.96 (8.87–9.06) | **0.81× (0.77–0.93)** | 0.75× (0.74–0.80) | 0.86× (0.78–0.92) | 302 / 850 | 464 / 1061 | 278 / 337 |
| nbody | 40.3 (39.2–43.3) | 41.0 (40.2–42.7) | **0.97× (0.94–1.00)** | 0.98× (0.92–1.03) | 1.00× (0.94–1.03) | 397 / 887 | 1544 / 1955 | 1390 / 1674 |
| sieve | 5.04 (4.78–5.34) | 5.56 (5.29–6.04) | **0.91× (0.89–0.94)** | 0.95× (0.87–1.00) | 0.97× (0.91–1.04) | 164 / 791 | 315 / 922 | 586 / 600 |
| spectralnorm | 5.25 (5.01–5.43) | 6.49 (6.17–6.76) | **0.75× (0.74–0.84)** | 0.87× (0.84–0.92) | 0.80× (0.75–0.83) | 383 / 1170 | 522 / 1244 | 679 / 772 |
| **geomean** | | | **0.91×** | 0.91× | 0.56× | | | |

- **Peak:** 0.91× overall. fib is 1.22× faster. binarytrees (0.71×), spectralnorm (0.75×) and mandelbrot (0.81×) are slower, which isn't analysed yet.
- **First iteration:** 0.4–0.8 s slower on every kernel, about as much on fib as on spectralnorm. So it is a cost per JVM, not per function: most likely the generated interpreter's classes, and the host compiler warming up its loop (not broken down).
- **Memory:** peak memory goes both ways: lower on fannkuch, bubblesort and binarytrees, up to 1.2× on nbody, mandelbrot, spectralnorm and the two loops.

<details><summary>Warmup curves per kernel (ms per iteration, medians over the rounds)</summary>

Compilation off:

**binarytrees (6 rounds, 10 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|
| tree (main) | 376 | 213 | 152 | 175 | 146 | 1822 | 140 (135-147) | 672 (649-681) |
| tree | 400 | 225 | 171 | 184 | 147 | 1890 | 146 (137-149) | 647 (646-658) |
| bytecode | 782 | 212 | 146 | 118 | 117 | 2031 | 117 (112-122) | 645 (645-646) |
| bytecode, uncached 16 | 760 | 212 | 135 | 130 | 125 | 2049 | 123 (115-137) | 646 (645-646) |

**bubblesort (6 rounds, 10 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|
| tree (main) | 158 | 89.9 | 90.4 | 52.6 | 51.3 | 748 | 51.3 (48.5-51.5) | 669 (665-669) |
| tree | 163 | 96.8 | 76.6 | 48.4 | 47.2 | 725 | 47.2 (46.1-50.9) | 662 (636-667) |
| bytecode | 881 | 239 | 112 | 112 | 106 | 2018 | 103 (98.9-109) | 430 (427-447) |
| bytecode, uncached 16 | 832 | 297 | 136 | 133 | 122 | 2243 | 120 (115-128) | 685 (684-689) |

**fannkuch (6 rounds, 10 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|
| tree (main) | 495 | 407 | 365 | 353 | 356 | 3774 | 356 (355-356) | 400 (399-401) |
| tree | 562 | 436 | 421 | 404 | 404 | 4197 | 393 (375-413) | 400 (399-401) |
| bytecode | 1279 | 576 | 428 | 476 | 373 | 5769 | 482 (471-509) | 204 (201-207) |
| bytecode, uncached 16 | 1250 | 669 | 555 | 539 | 477 | 6161 | 502 (452-519) | 239 (235-241) |

**fib (6 rounds, 10 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|
| tree (main) | 104 | 76.9 | 52.5 | 29.8 | 27.8 | 495 | 43.7 (40.6-48.8) | 638 (637-638) |
| tree | 104 | 73.8 | 46.0 | 22.3 | 44.0 | 439 | 37.2 (35.6-39.3) | 637 (636-638) |
| bytecode | 461 | 115 | 76.5 | 43.4 | 48.1 | 1026 | 46.2 (41.0-47.5) | 642 (642-642) |
| bytecode, uncached 16 | 477 | 98.4 | 80.5 | 50.4 | 52.0 | 1057 | 49.1 (48.8-52.9) | 641 (641-642) |

**floatloop (6 rounds, 10 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|
| tree (main) | 143 | 96.4 | 52.1 | 49.9 | 48.3 | 652 | 49.5 (43.2-52.8) | 234 (231-238) |
| tree | 164 | 91.4 | 53.9 | 52.2 | 46.1 | 643 | 46.7 (45.0-47.7) | 192 (189-198) |
| bytecode | 1093 | 205 | 96.3 | 88.0 | 84.1 | 2121 | 85.3 (83.8-89.5) | 232 (214-238) |
| bytecode, uncached 16 | 1090 | 209 | 220 | 98.9 | 90.2 | 2204 | 92.9 (84.2-106) | 228 (226-234) |

**intloop (6 rounds, 10 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|
| tree (main) | 134 | 103 | 71.6 | 63.4 | 58.6 | 754 | 58.5 (54.0-62.1) | 227 (226-228) |
| tree | 136 | 93.0 | 62.8 | 58.3 | 56.6 | 696 | 56.2 (55.7-56.8) | 229 (226-232) |
| bytecode | 1057 | 233 | 110 | 140 | 111 | 2334 | 110 (99.0-120) | 203 (198-213) |
| bytecode, uncached 16 | 1114 | 261 | 110 | 110 | 104 | 2373 | 105 (104-108) | 211 (210-213) |

**mandelbrot (6 rounds, 10 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|
| tree (main) | 271 | 178 | 173 | 166 | 154 | 1790 | 154 (147-168) | 330 (277-334) |
| tree | 378 | 199 | 239 | 192 | 200 | 2282 | 199 (190-200) | 721 (659-793) |
| bytecode | 1433 | 563 | 313 | 316 | 273 | 4644 | 262 (240-320) | 540 (536-548) |
| bytecode, uncached 16 | 1427 | 517 | 489 | 271 | 239 | 4267 | 257 (244-332) | 556 (547-563) |

**nbody (6 rounds, 10 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|
| tree (main) | 320 | 235 | 164 | 133 | 131 | 1699 | 129 (127-130) | 645 (643-647) |
| tree | 352 | 258 | 151 | 122 | 126 | 1678 | 121 (103-129) | 644 (644-646) |
| bytecode | 709 | 219 | 157 | 134 | 124 | 2044 | 123 (122-143) | 649 (649-650) |
| bytecode, uncached 16 | 742 | 232 | 160 | 129 | 123 | 2060 | 117 (114-127) | 648 (646-649) |

**sieve (6 rounds, 10 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|
| tree (main) | 171 | 38.9 | 36.9 | 36.8 | 36.5 | 515 | 37.5 (36.7-38.5) | 458 (453-462) |
| tree | 161 | 41.0 | 39.7 | 38.5 | 36.2 | 507 | 36.7 (36.0-37.9) | 457 (450-461) |
| bytecode | 928 | 141 | 69.8 | 68.3 | 65.5 | 1600 | 65.2 (61.7-65.7) | 454 (451-455) |
| bytecode, uncached 16 | 1004 | 149 | 67.6 | 65.4 | 63.3 | 1668 | 65.4 (63.3-74.5) | 452 (451-453) |

**spectralnorm (6 rounds, 10 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|
| tree (main) | 600 | 397 | 302 | 318 | 306 | 3510 | 312 (284-314) | 827 (826-830) |
| tree | 620 | 471 | 382 | 414 | 389 | 4143 | 389 (359-428) | 747 (668-827) |
| bytecode | 1270 | 520 | 461 | 487 | 467 | 5698 | 470 (454-487) | 948 (840-1055) |
| bytecode, uncached 16 | 1204 | 513 | 475 | 468 | 462 | 5497 | 453 (439-478) | 840 (837-842) |

Compilation on:

**binarytrees (8 rounds, 100 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | it 20 | it 40 | it 60 | it 80 | it 100 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tree (main) | 180 | 36.3 | 29.3 | 27.5 | 12.2 | 20.4 | 12.4 | 9.11 | 8.95 | 9.06 | 402 | 9.13 (8.90-9.55) | 1003 (996-1004) |
| tree | 187 | 43.4 | 30.2 | 28.0 | 12.8 | 21.6 | 13.4 | 9.10 | 9.03 | 9.64 | 423 | 9.51 (9.16-10.1) | 1007 (982-1010) |
| bytecode | 837 | 63.8 | 48.6 | 45.4 | 14.5 | 21.2 | 13.1 | 12.9 | 15.3 | 19.5 | 1146 | 13.3 (12.9-13.6) | 974 (971-992) |
| bytecode, uncached 16 | 955 | 63.2 | 59.4 | 44.5 | 14.5 | 20.6 | 14.7 | 13.0 | 13.1 | 13.9 | 1289 | 14.1 (12.8-15.3) | 1130 (1105-1141) |

**bubblesort (8 rounds, 100 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | it 20 | it 40 | it 60 | it 80 | it 100 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tree (main) | 165 | 39.8 | 18.7 | 16.5 | 6.57 | 1.03 | 1.12 | 0.99 | 0.99 | 1.00 | 293 | 0.97 (0.97-1.22) | 505 (504-507) |
| tree | 165 | 43.6 | 17.6 | 16.4 | 6.75 | 1.11 | 1.02 | 1.03 | 1.03 | 1.06 | 299 | 1.03 (0.92-1.09) | 516 (512-520) |
| bytecode | 833 | 159 | 12.1 | 11.8 | 11.6 | 1.23 | 1.26 | 1.17 | 1.17 | 1.18 | 1101 | 1.17 (1.10-1.27) | 462 (457-464) |
| bytecode, uncached 16 | 921 | 303 | 39.9 | 16.7 | 14.7 | 17.8 | 19.5 | 117 | 109 | 113 | 1923 | 111 (109-112) | 983 (980-985) |

**fannkuch (8 rounds, 100 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | it 20 | it 40 | it 60 | it 80 | it 100 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tree (main) | 678 | 90.6 | 560 | 60.1 | 17.1 | 15.8 | 15.3 | 14.7 | 14.4 | 14.0 | 1650 | 14.1 (13.8-14.6) | 382 (378-399) |
| tree | 694 | 105 | 594 | 55.1 | 15.6 | 15.6 | 14.4 | 14.3 | 14.5 | 14.9 | 1695 | 14.6 (14.1-15.8) | 384 (378-395) |
| bytecode | 1144 | 371 | 51.0 | 50.0 | 17.0 | 16.9 | 15.7 | 15.4 | 16.4 | 15.6 | 1911 | 16.4 (15.3-17.0) | 334 (327-352) |
| bytecode, uncached 16 | 1515 | 690 | 73.3 | 73.1 | 28.1 | 26.7 | 26.4 | 26.2 | 26.2 | 26.3 | 2679 | 26.8 (26.2-27.0) | 378 (376-383) |

**fib (8 rounds, 100 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | it 20 | it 40 | it 60 | it 80 | it 100 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tree (main) | 94.9 | 11.3 | 11.2 | 3.98 | 3.76 | 3.34 | 3.59 | 1.11 | 1.09 | 2.42 | 157 | 1.10 (1.05-1.15) | 566 (564-568) |
| tree | 92.1 | 10.6 | 10.1 | 3.58 | 3.25 | 3.14 | 4.81 | 1.11 | 1.15 | 3.20 | 141 | 1.11 (1.05-1.13) | 560 (554-563) |
| bytecode | 530 | 11.1 | 10.6 | 10.3 | 9.88 | 2.44 | 0.98 | 0.98 | 0.99 | 0.95 | 627 | 0.92 (0.87-0.94) | 563 (562-588) |
| bytecode, uncached 16 | 444 | 11.3 | 11.4 | 10.5 | 9.87 | 2.42 | 1.02 | 0.92 | 0.92 | 0.85 | 541 | 0.86 (0.79-0.94) | 587 (563-589) |

**floatloop (8 rounds, 100 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | it 20 | it 40 | it 60 | it 80 | it 100 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tree (main) | 143 | 7.92 | 7.32 | 7.33 | 2.70 | 2.67 | 2.60 | 2.56 | 2.52 | 2.47 | 199 | 2.53 (2.47-2.58) | 240 (240-241) |
| tree | 157 | 8.55 | 7.90 | 8.06 | 2.93 | 3.07 | 3.03 | 2.88 | 3.00 | 2.89 | 228 | 2.86 (2.68-2.95) | 243 (241-243) |
| bytecode | 622 | 88.8 | 3.13 | 3.14 | 3.05 | 2.76 | 2.75 | 2.78 | 2.82 | 2.66 | 728 | 2.68 (2.55-2.78) | 281 (280-282) |
| bytecode, uncached 16 | 687 | 94.7 | 3.42 | 3.26 | 3.23 | 2.96 | 2.90 | 2.91 | 2.94 | 2.94 | 810 | 2.87 (2.72-2.91) | 283 (278-287) |

**intloop (8 rounds, 100 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | it 20 | it 40 | it 60 | it 80 | it 100 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tree (main) | 146 | 15.2 | 15.2 | 18.5 | 10.8 | 10.1 | 9.72 | 9.97 | 9.01 | 9.21 | 275 | 9.27 (9.24-10.4) | 232 (231-233) |
| tree | 145 | 15.5 | 15.8 | 18.2 | 11.2 | 10.3 | 10.0 | 9.71 | 9.56 | 8.91 | 274 | 9.29 (8.95-9.52) | 233 (233-234) |
| bytecode | 720 | 93.1 | 18.7 | 11.9 | 11.9 | 11.3 | 10.9 | 9.89 | 9.32 | 9.62 | 940 | 9.56 (9.24-10.1) | 268 (268-269) |
| bytecode, uncached 16 | 630 | 90.0 | 18.1 | 11.2 | 11.2 | 11.3 | 10.7 | 9.80 | 10.00 | 9.92 | 824 | 10.0 (9.31-10.6) | 268 (267-270) |

**mandelbrot (8 rounds, 100 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | it 20 | it 40 | it 60 | it 80 | it 100 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tree (main) | 250 | 60.8 | 41.7 | 9.10 | 9.23 | 7.59 | 7.43 | 7.37 | 6.92 | 6.68 | 420 | 6.82 (6.67-7.02) | 274 (272-277) |
| tree | 302 | 62.5 | 41.3 | 9.27 | 10.3 | 8.70 | 7.92 | 7.81 | 6.91 | 6.85 | 464 | 6.96 (6.46-8.48) | 278 (278-466) |
| bytecode | 850 | 101 | 13.6 | 16.4 | 10.2 | 9.87 | 9.44 | 9.12 | 8.96 | 8.86 | 1061 | 8.96 (8.87-9.06) | 337 (336-338) |
| bytecode, uncached 16 | 796 | 183 | 14.9 | 15.4 | 10.5 | 9.36 | 8.90 | 8.79 | 8.40 | 8.16 | 1076 | 8.12 (7.89-8.69) | 340 (338-341) |

**nbody (8 rounds, 100 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | it 20 | it 40 | it 60 | it 80 | it 100 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tree (main) | 378 | 240 | 199 | 64.7 | 65.3 | 57.0 | 54.4 | 53.2 | 41.5 | 39.5 | 1439 | 39.5 (38.4-42.7) | 1373 (1320-1389) |
| tree | 397 | 290 | 202 | 81.6 | 72.7 | 64.1 | 61.6 | 54.0 | 40.0 | 40.1 | 1544 | 40.3 (39.2-43.3) | 1390 (1389-1392) |
| bytecode | 887 | 248 | 203 | 76.4 | 65.1 | 57.3 | 58.6 | 55.1 | 41.0 | 41.9 | 1955 | 41.0 (40.2-42.7) | 1674 (1607-1682) |
| bytecode, uncached 16 | 981 | 234 | 215 | 73.5 | 66.6 | 58.2 | 56.6 | 56.9 | 43.9 | 42.3 | 2057 | 43.8 (40.9-44.1) | 1685 (1681-1698) |

**sieve (8 rounds, 100 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | it 20 | it 40 | it 60 | it 80 | it 100 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tree (main) | 169 | 45.6 | 31.2 | 8.78 | 16.6 | 7.68 | 7.57 | 5.37 | 5.04 | 5.06 | 325 | 5.04 (4.99-5.13) | 587 (586-588) |
| tree | 164 | 43.2 | 34.4 | 8.93 | 16.3 | 7.65 | 7.31 | 5.31 | 5.07 | 5.03 | 315 | 5.04 (4.78-5.34) | 586 (584-587) |
| bytecode | 791 | 51.2 | 10.2 | 10.2 | 9.32 | 9.39 | 7.84 | 7.92 | 5.65 | 5.21 | 922 | 5.56 (5.29-6.04) | 600 (600-601) |
| bytecode, uncached 16 | 752 | 50.7 | 9.75 | 9.58 | 9.26 | 7.95 | 7.42 | 7.55 | 5.30 | 5.27 | 877 | 5.18 (5.10-5.60) | 598 (598-598) |

**spectralnorm (8 rounds, 100 iterations)**

| variant | it 1 | it 2 | it 3 | it 5 | it 10 | it 20 | it 40 | it 60 | it 80 | it 100 | first 10 | steady (IQR) | peak MB (IQR) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tree (main) | 388 | 104 | 23.5 | 6.71 | 6.43 | 6.00 | 5.99 | 6.52 | 6.20 | 5.70 | 560 | 5.74 (5.42-6.50) | 743 (712-747) |
| tree | 383 | 81.5 | 21.6 | 5.74 | 5.80 | 5.89 | 5.50 | 5.47 | 5.52 | 5.15 | 522 | 5.25 (5.01-5.43) | 679 (668-683) |
| bytecode | 1170 | 9.04 | 8.38 | 7.92 | 7.36 | 7.33 | 7.37 | 6.67 | 6.74 | 6.17 | 1244 | 6.49 (6.17-6.76) | 772 (725-788) |
| bytecode, uncached 16 | 988 | 8.83 | 7.95 | 7.85 | 7.53 | 7.50 | 6.85 | 6.79 | 6.81 | 6.66 | 1060 | 6.85 (6.47-7.00) | 772 (734-788) |

</details>

### The tree, against main
- **Compilation on:** 12 interleaved rounds of main's build (`6040e42`) against this branch put fannkuch at 0.99, floatloop at 1.02 and spectralnorm at 0.97 of main's time. The other kernels are within ±5% in the table run (geomean 1.01).
- **Compilation off:** the branch's tree was 10–29% slower on fannkuch, mandelbrot and spectralnorm (12 rounds). A bisection by file group of the tree refactor (`2d778ce`) found no single cause: each group alone was within noise of main or faster, and only all of them together were slower. The clearest single effect was splitting the comparisons into one class per operator (mandelbrot 0.78× alone). The fix (`3591886`) gives the tree back main's comparison classes, with a field telling `<` from `<=`, and puts the per-operator classes the proxies need in `Comparisons`, over the same rules. With it, the tree's interpreter is at 1.00 (fannkuch), 0.91 (mandelbrot) and 0.83 (spectralnorm) of main's speed (10 rounds, phpt running on other cores). The rest isn't found: the HotSpot-hosted AST interpreter's speed moves with the host compiler's inlining decisions, run to run by up to ±25%.
- **What this means:** if S1 stops here, the refactor shouldn't reach main. It exists only to share code with the bytecode interpreter.

### What the design met
- **`@OperationProxy` takes the tree's nodes.** Arithmetic, unary operators, `ToBooleanNode`, `ElementReadNode` and `<=>` run in both interpreters from one copy. The changes were mechanical: `public static` specializations, `@Bind Node` where a specialization used `this`, and `@GenerateUncached` on the nodes the operations cache. Comparisons need a class per operator (above).
- **The two roots call each other freely.** They share the arguments array and the `PhpFunction`. By-reference arguments resolve the callee first and send each variable as its parameter says (SEND_VAR_EX), whichever interpreter the callee runs on.
- **Variables as `BytecodeLocal`s over `LocalAccessor`** work, with the rules shared with `LocalVarNode` through `Variables`. Four things cost time to find:
  - Truffle 25.0.4 moves a function from the uncached to the cached interpreter at a back-edge by re-storing every local. A local not assigned yet takes the Object kind for good: its ints and floats stay boxed and its operations generic. with compilation on, bubblesort takes 111 ms per iteration at steady state instead of 1.17 and fannkuch 26.8 instead of 16.4 (the "uncached 16" column). Upstream Truffle no longer re-stores. So `php.bytecode.uncached` defaults to 0: functions specialize from their first call, and the uncached tier stays unused until a Truffle with the fix.
  - A typed read (`getLong`) of a local that holds a boxed int must not accept it. `UnexpectedResultException`'s constructor invalidates compiled code (`SlowPathException` calls `transferToInterpreterAndInvalidate`), so every such read deoptimized. The operation goes generic instead. A cleared local has to be tested first: the frame has no value to put in the exception (`FrameSlotTypeException`).
  - Booleans aren't boxing-eliminated. A short circuit's result reached `Not` unboxed on one path and boxed on the other, and compiled code deoptimized at each change of path.
  - Releasing the locals' arrays on exit is an `@EpilogReturn`/`@EpilogExceptional` loop over the locals by offset (`BytecodeNode.getLocalValue`, which boxes ints and floats in the interpreter).
- **The parser lambda** holds the function's `AstBody`, not its tree. The first run takes the body the tree translator already read; later runs (`ensureSourceInformation`, at the first error or backtrace through the function) read it again. Locals are numbered in a fixed order: parameters, captures, then the body's variables as met. What a run makes for a place in the body (a call site's `CallInfo`, a constant array) is made by the first run and taken by later runs in the same order. So a reparse emits the same bytecode and makes nothing new. Closures and functions declared inside a bytecode body would make a `PhpFunction` per run, so they fall back to the tree for now.
- **Locations:** the generated root overrides `getSourceSection()`, so a bytecode function's locations come from `BytecodeLocation`s. A throw records the instruction (`PhpError.surfacedAt`, innermost first, through `interceptTruffleException`), and the line is looked up when a message or a trace needs it. Backtraces find the call site from the calling instruction's `CallInfo` operand.

### Reproduce
```
mvn package -DskipTests && cp -r target/classes target/classes-s1
# main's build: git archive 6040e42 pom.xml src | tar -x -C target/main-src
#   (cd target/main-src && mvn -o -q compile) && cp -r target/main-src/target/classes target/classes-main
BC=-Dpolyglot.php.Interpreter=bytecode; OFF=-Dpolyglot.engine.Compilation=false
bench/k0/ab-each.sh target/s1-off 10 6 "main@$OFF" "s1@$OFF" "s1@$OFF $BC" "s1@$OFF $BC -Dphp.bytecode.uncached=16"
bench/k0/ab-each.sh target/s1-on 100 8 main s1 "s1@$BC" "s1@$BC -Dphp.bytecode.uncached=16"
# validation
JAVA_OPTS=$BC tests/smoke/run.sh
CPUSET=6-13 PHP_CLASSES=target/classes-s1 JAVA_OPTS=-Dpolyglot.php.Interpreter=tree tests/phpt/run.sh --reuse-oracle
CPUSET=6-13 PHP_CLASSES=target/classes-s1 JAVA_OPTS=$BC tests/phpt/run.sh --reuse-oracle
JAVA_OPTS="$BC -Dphp.bytecode.report=true" bench/gate1/truffle.sh bench/k0/run.php all 3   # against bench/gate1/php.sh php bench/k0/run.php all 3
```
The tables are from `f0cacf9`. The comparison fix after it changes only the tree's classes; the bytecode's comparisons are the same specializations under new class names. Re-measured at `866a98b` (the review fixes, still 25.0.4) in the runs of the next section: 0.73× with compilation off (15 iterations, 5 rounds) and 1.06× with it on (120 iterations, 5 rounds).

## Why S1 is slower (2026-10-31, GraalVM 25.4.4.1.1)

The owner asked why, before deciding. Each hypothesis below got an experiment of its own: one variable changed (a JVM option, a DSL attribute, or a patch), the rest held, interleaved runs on CPUs 14-21, effect as a speed ratio per round (median, IQR over rounds). The measurements run on GraalVM 25.4.4.1.1 (`graalvm-bump`, merged into this branch), whose DSL changed the generated interpreter a lot (below).

**Short answer.** Three things made S1's 0.72× (per kernel, compilation off, GraalVM 25.0.4), and one thing didn't change.
1. **The DSL version.** GraalVM 25.4's DSL generates a faster interpreter: the same S1 runs **1.26×** faster on it, and against the tree it goes from 0.73× to 0.92× (H1).
2. **The measurement.** Each kernel in its own JVM lets the host compiler specialize the tree's `execute` methods for one program. With the ten kernels in one JVM, as an application runs, the bytecode is **0.99–1.16×** the tree (H2).
3. **Our operations.** Comparisons took boxed operands, and statements pushed values only to pop them; fixed, together **1.12×** per kernel and 1.17× in one JVM (H4, H5). Variables go through our own `LocalAccessor` operations; doing their reads and scalar stores with the builtins is worth up to 1.09–1.12× more (H6, a ceiling).
4. **What stays: cold code.** First executions cost the bytecode **2.8×** the tree's time on the JVM and 1.11× on native image (H9, H10). The bytecode interpreter doesn't cut WordPress's cold-code cost. Native image does, for either interpreter: its cold pass is 2.8× faster than the JVM tree's.

1.3× is in reach two ways (next section): in one JVM with exact builtin locals (1.34× measured as a ceiling), or on native image with tail-call handlers (1.32× measured, before the fixes).

### What the documentation and the literature say
- **The DSL promises no speedup.** Neither the [user guide](https://github.com/oracle/graal/blob/master/truffle/docs/bytecode_dsl/UserGuide.md) nor the [optimization guide](https://github.com/oracle/graal/blob/master/truffle/docs/bytecode_dsl/Optimization.md) gives a number against AST interpreters. The user guide offers quickening and boxing elimination "to improve interpreted (non-compiled) performance", and the uncached interpreter to "reduce the footprint of your language and improve start-up times".
- **Superinstructions and tracing aren't there.** The optimization guide: "Superinstructions are not yet supported", "Tracing is not yet supported". Tracing would infer quickenings and superinstructions from runs. Until then, quickenings beyond the DSL's automatic ones are manual (`@ForceQuickening`), and every PHP operation stays an instruction of its own.
- **Builtin locals are the preferred access.** The user guide calls `LoadLocal`/`StoreLocal` "the preferred way to access locals because they are efficient"; accessors are for operations the builtins can't express. The "extra indirection" it mentions is the `BytecodeNode` helpers' (`getLocalValue` and the like: "the built-in operations and accessors are preferred"), not the accessors'. S1 reads and writes every PHP variable through a `LocalAccessor` in an operation of its own, because a PHP variable holds references, releases arrays and counts holders.
- **Larose, Kaleba, Burchell and Marr, "AST vs. Bytecode: Interpreters in the Age of Meta-Compilation" (OOPSLA 2023)** built TruffleSOM both ways. Interpreter only, on native image, the AST interpreter ran at 3.07× Java's time and the bytecode one at 4.96×. Peak performance was on par. The AST took 2.89× the bytecode's memory unexecuted, and 1.21× once executed. So the literature's expectation for a Truffle language is the result S1 got per kernel: the bytecode interpreter isn't faster, and saves memory.

### Hypotheses, experiments, effects
Speed is the reference's time ÷ the variant's per round (median, IQR over rounds); above 1 the variant is faster. "Per kernel" means each K0 kernel in its own JVMs (ab-each.sh, the S1 tables' setup); "one JVM" means the ten kernels interleaved in one process (bench/k0/mixed.php), as an application's code shares a JVM. Compilation off unless said.

| # | hypothesis | experiment | effect (IQR) | fixable, cost |
|---|---|---|---|---|
| H1 | **GraalVM 25.0.4's DSL generated a slow interpreter** | the same S1 on 25.0.4 and 25.4.4.1.1 (`graalvm-bump`), interleaved, per kernel | the bytecode is **1.26×** faster on 25.4 (spectralnorm 1.93×, bubblesort 1.37×, intloop 1.28×, fib 1.27×; nbody 1.03×), the tree 0.98×. Bytecode against the tree: **0.73× → 0.92×** (the S1 table's 0.72×, reproduced, then 0.92–0.96× in three 25.4 runs). The new code: handlers are methods (213, a threaded switch between them), specialization state lives in the bytecode (64 singleton nodes, where 25.0.4 had 9), primitive constants are inline, and popped primitive slots are cleared only in compiled code (H7) | done: the bump (PR #5) |
| H2 | **The per-kernel JVMs flatter the tree** (the host compiler specializes the AST interpreter's `execute` methods for one program) | the same builds, the kernels interleaved in one JVM | bytecode against the tree: 0.96× per kernel → **0.99–1.16× in one JVM** (two runs). The tree loses up to 3.5× (fib 28 → 98–102 ms, intloop 52 → 87–92, spectralnorm 328 → 524–531); the bytecode up to 1.5× (intloop 73 → 121–148). Control: on native image, which has no host JIT, per kernel and one process agree (native tree fib 70.9 against 70.8 ms, bytecode 0.82× against 0.83×) | not a fix: the measurement. An application is the one-JVM case |
| H3 | **More instructions than the tree has nodes** | `engine.BytecodeHistogram`, compilation off, one iteration | no: intloop runs 24 instructions per iteration, the tree ~22–24 node executions. What differs is their kind (H4–H6) | – |
| H4 | **Comparisons take boxed operands** (E1: `Less$Longs$Doubles` takes Objects, so `$i < $n` reads `$i` and `$n` boxed) | `@ForceQuickening` on the comparisons' int and float specializations (`cmpq`) | **1.07×** per kernel (fannkuch 1.28×, floatloop 1.20×, intloop 1.14×; binarytrees and nbody 0.94×) | **done**, 15 annotations (`9c8f17b`) |
| H5 | **Expression statements push a value only to pop it** (pops are 8–16% of the instructions) | void statement operations: `WriteVarStatement`, `CompoundVarStatement`, `IncDecVarStatement` (`stmt`) | without `@ForceQuickening` 0.98× (intloop 0.87×, sieve 0.88×): the DSL didn't quicken the void operations. With it, H4 and H5 together (`cs2`) are **1.12×** the head per kernel (fannkuch 1.31×, floatloop 1.28×, intloop 1.21×; nbody 0.97×) and **1.17×** in one JVM. H5's own share is what's left over H4's 1.07× | **done** (`b9d4e85`), ~90 lines |
| H6 | **Variables go through our own operations and `LocalAccessor`s** where the guide prefers `LoadLocal`/`StoreLocal` | ceiling (experiment only, wrong for unset reads and arrays' holders): reads of variables that can't be references are `LoadLocal`, scalar assignments `StoreLocal` (`locals`) | **1.09×** per kernel (fannkuch 1.25×, mandelbrot 1.20×, floatloop 1.17×). On top of H4 and H5 (`combo2`): 1.22× the head per kernel, and in one JVM **1.34× the tree** (the head 0.99×, cs2 1.14×) | partly: exact for variables that are never references, always assigned before read and only ever scalar. Needs a per-function pass (definite assignment, scalar-only variables) in the translator; medium |
| H7 | **The operand stack's stores and clears** (E4) | the generated code, 25.0.4 against 25.4 | 25.4 clears popped primitive slots only in compiled code: 140 clears in the cached interpreter became 83, plus 266 compiled-only. The rest clear Object slots (for the GC). Nothing left to configure | part of H1 |
| H8 | **The interpreter depends on the host compiler** (E2) | C2 instead of Graal compiling the interpreter (`-XX:-UseJVMCICompiler`) | bytecode **0.47×** of itself with Graal; tree 0.87×. With C2 the bytecode is 0.53× the tree | yes: keep Graal as the host compiler. **Native image** (the same commit `4747eae` built ahead of time, so no host JIT): with compilation off the native interpreters are slower than the Graal-hosted ones (tree 0.62×, bytecode 0.54×); the bytecode is 0.82× the native tree, and **1.32× with tail-call handlers** (feature 7). With compilation on: peak 0.98–0.99× the tree, the first iteration about the tree's (fib 81 against 98 ms, mandelbrot 156 against 274; bubblesort 140 against 88), peak memory 86–330 MB against the JVM's 220–1550 |
| H9 | **Cold code** (E5): WordPress runs most functions a few times | bench/cold: 2000 new functions called 1–20 times, compilation on, fresh JVMs, 10 rounds | bytecode time ÷ tree time on the JVM: **2.80× (2.43–3.18)** on a cold engine; **1.34× (1.20–1.45)** for new functions on a warm engine; 1.02× for the same functions again; 0.84× once warm. Peak memory 371 against 449 MB. **On native image: 1.11× (1.09–1.15)** cold, 0.96× warm, 0.78× again. The native tree's cold pass (86 ms) is 2.79× faster than the JVM tree's (239) and 8× faster than the JVM bytecode's (695) | the bytecode doesn't cut cold-code cost: it adds 11% on native and 180% on the JVM. Native image does (the JVM's own warmup goes). See H10 |
| H10 | **The first iteration** (E6) | JFR (1 ms samples, main thread), TraceCompilation, compile events | cold code: translation 3× the tree's samples (157 against 51, the DSL builder and our translator), execution 2.4× (334 against 137). K0: translation ~0%; the interpreter runs 2× the tree's time before compilation; bytecode OSR compiles from the loop to the function's end, so the first exit deoptimizes (2–5 deopts on the loop kernels, the tree none); the host compiles the 127 KB `continueAt` in 336 ms on a compiler thread | partly: the host's warmup goes away on native image (H8); the translation cost is ours and the builder's |
| H11 | **The uncached tier** (now that 25.4 no longer re-stores locals on the move to cached) | `php.bytecode.uncached=16` | 0.99× per kernel with compilation off and on; on cold code (H9) the bytecode takes 3.03× the tree's time on a cold engine (2.80× without) and 1.53× on a warm one (1.34×). The tier works now (25.4: no Object kind for good, no boxed loops), it just doesn't pay | no: leave it at 0 |
| H12 | **25.4 lost a peak the bytecode had** (found on the way) | the same S1 on 25.0.4 and 25.4, compilation on, 120 iterations | fib compiled on bytecode: 0.78 ms on 25.0.4 (3.3× the tree's speed), 2.59 ms on 25.4 (the tree's 2.48). Other kernels within ±12% except mandelbrot 1.23× and spectralnorm 1.20× faster. 25.4 changed guest inlining to Graal IR call-site frequencies (GR-79418), the likely cause for a recursive call; not confirmed | open: inlining trace of fib on both versions |

### What could reach 1.3×
Bytecode speed against the tree, compilation off (the gate's measure):

| combination | per kernel (a JVM each) | one JVM (kernels interleaved) |
|---|---|---|
| S1 on 25.0.4 (the S1 table) | 0.72–0.73× | – |
| the head on 25.4 (`4747eae`) | 0.92–0.96× | 0.99–1.16× (two runs) |
| + comparisons quickened, statement forms (`b9d4e85`, committed) | **1.03×** | **1.14×** |
| + builtin locals, ceiling (`combo2`, experiment only) | 1.12× | **1.34×** |
| native image, the head, no tail calls | 0.82× | 0.83× |
| native image, the head (`4747eae`), tail-call handlers | **1.32×** | **1.34×** |
| native image at `4cb8cef` (both fixes, tail-call handlers): against the native tree | **1.44×** | **1.37×** |
| the same, against the **JVM** tree at `4cb8cef` | 0.90× | **1.25×** |

- **On the JVM**, 1.3× needs the builtin locals for real. That means a pass over each function before emitting: variables that are never references (no `&`, no by-reference argument or parameter, no capture), always assigned before they're read (definite assignment), and only ever assigned scalars. Those get `LoadLocal`/`StoreLocal`; the rest keep the operations. The ceiling bounds what it can give (1.34× in one JVM, 1.12× per kernel), so it is 1.3× with no margin. Cost: medium, a translator pass and its tests.
- **On native image**, one attribute (`enableTailCallHandlers = true`) gives 1.32× per kernel without the fixes. They gave 1.12× per kernel on the JVM, so native with both is likely past 1.4×; not measured. But the native interpreters are slower than the Graal-hosted ones in absolute terms (tree 0.62×, bytecode 0.54× without tail calls), and native regex is 12× slower than the JVM's on Gate 1 (downcall handles made at run time, gate-1.md), so WordPress on native needs that fixed first.
- **What no combination changes:** compiled peak is the tree's within ±5% (0.98–1.07×), and cold code costs the bytecode more than the tree on both hosts. If the gate's question is WordPress's cold-code cost, the answer is native image, not the bytecode interpreter.

### Native image at the head (`4cb8cef`)
One native image of the head (both fixes, tail-call handlers, StackValue), both interpreters, against the same commit on the JVM, all four in the same interleaved rounds (CPUs 14-21, 5 rounds; cold code 10). The reference is the JVM tree: speed above 1 is faster than it; cold code and the first iteration are times. Checksums equal PHP's on K0 and Gate 1 with both interpreters. Another job loaded the box during this run (absolute times about 1.4× the earlier runs', for every variant alike); the ratios come from the same rounds.

| | native tree | **native bytecode** | JVM bytecode |
|---|---|---|---|
| K0 per kernel, compilation off (15 its) | 0.62× | **0.90×** (spectralnorm 1.38×, fannkuch 1.18×; floatloop 0.57×, nbody 0.63×) | 1.03× |
| K0 in one process, compilation off (15 rounds) | 0.93× | **1.25×** (fib 2.20×, binarytrees 1.91×, spectralnorm 1.85×; nbody 0.70×) | 1.17× |
| K0 per kernel, compilation on (120 its) | 1.24× | **1.24×** (fib 8.6×, the JVM's compiled fib being slow; without fib about 1.0×) | 1.01× |
| K0 in one process, compilation on (100 rounds) | 1.09× | **1.07×** | 1.03× |
| first iteration, K0 per kernel, compilation on | 102–631 ms | **79–431 ms** | 545–1173 ms |
| JVM tree's first iteration, same | 134–734 ms | | |
| cold code, first pass (bench/cold), time | 137 ms, 0.30× the JVM tree's (427) | **147 ms, 0.35×** | 1176 ms, 2.73× |
| cold code, new functions on a warm engine | 0.73× | **0.61×** | 1.14× |
| peak memory, K0 compilation on | 111–293 MB | **121–295 MB** | 258–1314 MB (JVM tree 209–1276) |
| peak memory, cold code | 163 MB | **139 MB** | 364 MB (JVM tree 450) |

- **Inside the native image** the bytecode is 1.44× the tree per kernel and 1.37× in one process with compilation off (1.32× and 1.34× at `4747eae`, before the two fixes), and level with it compiled.
- **Against the JVM tree**, native bytecode is faster in one process (1.25×) and slower per kernel (0.90×): the native interpreters lose the host JIT's per-program specialization along with its warmup. On cold code it is the fastest of the four (2.9× the JVM tree's speed on a first pass), with a third of the memory.
- **The build:** with tail-call handlers 2 min 24 s and 88.8 MiB (93,129,024 bytes), without 2 min 51 s and 88.4 MiB (92,670,272); the builder peaks at 5.7 GiB either way.
- Native image still has to fix regex before WordPress runs on it (gate-1.md: 12× slower than the JVM on Gate 1), and runtime compilation's memory isn't capped by `-Xmx`.

### The DSL features of 25.x, one at a time
Against 25.4's defaults on the bytecode interpreter, per kernel (5 rounds; compilation off 15 iterations, on 120), geomean speed (IQR of the extreme kernel):

| # | feature | 25.4 default | interpreter | compiled | first iteration | peak memory | keep |
|---|---|---|---|---|---|---|---|
| 1 | threaded switch (`enableThreadedSwitch`) | on | off: **0.86×** (sieve 0.44×, intloop 0.80×, mandelbrot 0.81×) | 1.00× | equal | equal | yes (the default) |
| 1 | specialization state in the bytecode (GR-61293) | always | part of H1's 1.26× | | | | (no switch) |
| 1 | primitive constants inline (`inlinePrimitiveConstants`) | on | off: 0.98× (fannkuch 0.86×, spectralnorm 0.92×) | 0.99× | equal | equal | yes (the default) |
| 2 | instruction rewriting (`enableInstructionRewriting`) | on | off: 0.99× (fannkuch 0.93×) | 1.00× | equal | equal | yes (the default) |
| 3 | uncached tier (`php.bytecode.uncached=16`) | ours: 0 | 0.99× | 0.99× | cold code: 3.03× the tree's time (2.80× at 0) | equal | no, stays 0 (H11) |
| 4 | `storeBytecodeIndexInFrame` and per-operation `storeBytecodeIndex` | off | on: 0.97× (intloop 0.91×, bubblesort 0.91×) | – | – | – | no: off, the cached interpreter stores no bytecode index at all (none of the frame stores are bci stores); with it on, 31 handlers store one |
| 5 | `ClearLocal` for unset and a finished foreach (two instructions where `UnsetVar`/`ForeachEnd` was one) | builtin | 0.99× (binarytrees 0.95×, sieve 0.94×) | 0.96× (binarytrees 0.87×) | equal | equal | **no**: not committed |
| 6 | `StackValue` for a call's callee (by-reference arguments) | builtin | 1.01× (binarytrees 1.09×); cold code equal within noise | 1.01× (cs2 against sv) | equal | equal | **yes** (`4747eae`): no hidden locals, one store and a load less per such call |
| 7 | tail-call handlers on native image (`enableTailCallHandlers`) | off | native: **1.60×** the bytecode without (fib 2.16×, spectralnorm 1.98×, fannkuch 1.35× over the tree); 1.32× the native tree | native: 1.01× (0.99× the tree) | native: equal or faster (fib 67 against 81 ms) | +0–30 MB (fib 128 against 119) | **yes**: one attribute; on the JVM 1.00× with compilation off and 1.01× on (sieve 1.24×, fib 0.94×) |
| 8 | `engine.BytecodeHistogram` | – | intloop 72M instructions (24 per iteration), mandelbrot 170M, fannkuch 180M, sieve 19M; cmpq+stmt: 66M, 154M, 161M, 17M | | | | the E1 tool; ForceQuickening's effect shows as `ReadVar$ReadLong` (boxed) → `$unboxed` |
| – | the generated interpreter on the host's C2 (`-XX:-UseJVMCICompiler`) | Graal | **0.47×** | – | – | – | Graal (H8) |

### Reproduce (this section)
The builds are class directories `target/classes-X` beside one another; `bench/gate1/ab.sh` runs a variant `X@options` from its directory, with `target/lib-X` and the image named in `target/classes-X.image` when they exist (25.0.4 runs on `gate1-truffle:8.5` with its own jars).
```
BC=-Dpolyglot.php.Interpreter=bytecode; OFF=-Dpolyglot.engine.Compilation=false
# s1old: 866a98b on 25.0.4 (pom and jars of then); s1new: b619d2e; sv: 4747eae; cs2: b9d4e85
# DSL options: s1new with one @GenerateBytecode attribute flipped (enableThreadedSwitch, enableInstructionRewriting,
# inlinePrimitiveConstants = false; storeBytecodeIndexInFrame, enableTailCallHandlers = true)
# prototypes: s1new + a patch: locals (reads of variables that can't be references as LoadLocal, scalar assignment
# statements as StoreLocal; experiment only), clear (UnsetVar -> ReleaseVar + ClearLocal), combo2 (cs2 + locals)
bench/k0/ab-each.sh target/f-off 15 5 "s1old@$OFF" "s1old@$OFF $BC" "s1new@$OFF" "s1new@$OFF $BC" ...
bench/k0/ab-each.sh target/f-on 120 5 s1old "s1old@$BC" s1new "s1new@$BC" ...
AB_OUT=target/mixed-p-off DRIVER=bench/k0/mixed.php bench/gate1/ab.sh all 15 5 "s1new@$OFF" "sv@$OFF $BC" "cs2@$OFF $BC" "combo2@$OFF $BC" ...
python bench/cold/generate.py && AB_OUT=target/cold-ab DRIVER=bench/cold/run.php bench/gate1/ab.sh cold 4 10 s1new "s1new@$BC" ...
python bench/gate1/speed.py target/f-off 2 tree=2 bytecode=3      # speed tables (any reference, any variants)
# E1: JAVA_OPTS="$BC $OFF -Dpolyglot.engine.BytecodeHistogram=true" bench/gate1/truffle.sh bench/k0/run.php intloop 1
# E6: -XX:StartFlightRecording=...,settings=profile,jdk.ExecutionSample#period=1ms; -Dpolyglot.engine.TraceCompilation=true
# native: bench/native/build.sh (NI_CLASSES and NI_NAME for a variant), then ab.sh variants native/NAME
```

