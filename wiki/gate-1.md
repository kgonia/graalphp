# Gate 1: does Graal beat PHP 8.5 JIT on real WordPress code?

Started 2026-10-22. Decisions D4–D8 in [decisions.md](decisions.md) apply.

## Question
Does a Truffle PHP, run on GraalVM, execute WordPress's own hot PHP functions clearly faster than PHP 8.5 (and 8.4) with OPcache's tracing JIT, once warm? Does it get warm fast enough for a server?

## Kernels
All kernels run **unmodified WordPress files**, fetched at a pinned commit by `bench/gate1/fetch.sh`, plus a small stub file for the functions they reach outside those files. Function bodies are translated to Truffle nodes on first call, so unsupported constructs in functions that never run don't block anything.

| # | Kernel | WordPress code | What it stresses |
|---|---|---|---|
| K0 | Benchmarks Game: binarytrees, fannkuchredux, nbody, spectralnorm | — (graalphp's set) | the numeric and array core; sanity against graalphp's results |
| K1 | hooks | `class-wp-hook.php`, `plugin.php`: `add_filter`, `apply_filters`, `do_action` with closures, function names, `[$obj, 'method']` and static callables at several priorities | dynamic dispatch, `call_user_func_array`, array internal pointers |
| K2 | escaping | `esc_html`, `esc_attr` (`_wp_specialchars`, `wp_check_invalid_utf8`) over corpus strings | string built-ins, static variables |
| K3 | slugs | `sanitize_title` → `sanitize_title_with_dashes`, `remove_accents` over corpus titles | `strtr` with large maps, `preg_replace`, UTF-8 |
| K4 | wpautop | `wpautop` over classic posts | `preg_*`, `str_replace`, string building |
| K5 | wptexturize | `wptexturize` over posts | `preg_split`, static caches, loops over tokens |
| K6 | blocks | `parse_blocks` (`WP_Block_Parser`) over block posts | `preg_match` with offsets, `substr`, objects, `json_decode` |
| K7 | kses | `wp_kses_post` over posts | `preg_replace_callback`, large arrays, many small calls |

**Corpus:** the post contents from WordPress's Theme Unit Test data (WordPress/theme-test-data), generated into a PHP array file by the fetch script.

## Method
- **One PHP driver** (`bench/gate1/run.php`) runs on both engines. It loads the stubs and WordPress files, then runs each kernel for N iterations, timing each with `hrtime(true)`. Each iteration prints the time and a **checksum** of its output (length + `crc32`).
- **Correctness:** checksums must equal PHP 8.5's on every iteration. A mismatch fails the kernel, whatever its speed.
- **Reference:** `php:8.5-cli` (newest stable; 8.4 measured too) + the `ast` extension, with `opcache.enable_cli=1`, `opcache.jit=tracing` and `opcache.jit_buffer_size=128M`. Also measured with JIT off.
- **Truffle PHP:** Oracle GraalVM 25.0.4 (JVM mode first; the native standalone later), in the `gate1-truffle` image: same Debian and CPU as PHP, and the same PCRE2 code (PHP's bundled pcre2lib built as a shared library), so native regex time is like for like. With Debian's libpcre2 instead, a no-match scan was about 1.5× slower than PHP's.
- **Runs:**
  - Linux Docker containers on the same cpuset, interleaved rounds (PHP, Truffle, PHP, Truffle…), 3 rounds.
  - Log the governor and power mode.
  - Publishable numbers come from a quiet Linux host with pinned cores.
- **Reported per kernel:**
  - steady state: the median of the last 25% of 200 iterations, std within a run and between rounds. Truffle reaches its plateau after 40–60 iterations (Tier 2 compilations land late), so the last 50% of 100 mixes in warmup;
  - the **warmup curve**: time per iteration vs iteration, plus time to reach 90% of peak;
  - CPU time including Graal's compiler threads.

### Native share (added 2026-10-22, after a side agent's note)
Five kernels (wpautop, wptexturize, kses, blocks, slugs) lean on `preg_*`. Per D7, both engines run the same PCRE2 with its JIT, and the Truffle side caches compiled, JIT-ed patterns per pattern string as PHP does. The regex part therefore costs about the same on both sides and **caps the end-to-end speedup**: a kernel that spends 50% of its time in regex can't exceed 2× however fast Graal runs the rest.

So the Truffle side times every native call (PCRE2 and any other FFM-bound built-in) per kernel. That gives **T_native**, which counts as equal on both engines. For a PHP-side cross-check, sample the PHP run with `perf` (`opcache.jit_debug` perf map) on the Linux host.

**How T_native is measured:** a reading of `System.nanoTime()` costs about 37 ns in the container, about as much as a short match. Native timing is therefore opt-in (`-Dphp.nativeclock=true`), comes from a separate run, and has the clock's own cost (calibrated at startup) subtracted. Timed runs leave it off.

**Reported per kernel:**
- end-to-end speedup: T_php / T_truffle;
- native share: T_native / T_php;
- **PHP-code speedup**: (T_php − T_native) / (T_truffle − T_native). This is what Graal controls.

## Go / no-go
- **Go** if the geometric mean of the **PHP-code speedup** over K1–K7 against PHP 8.5 tracing JIT is **≥ 2×** (the owner may adjust the target), checksums are identical, and every kernel reaches 90% of its peak within seconds, not minutes.
- End-to-end speedups are always reported next to it. The regex-light kernels (hooks, escape) are also shown as a separate group.
- **Rethink** if the PHP-code speedup is under about 1.3×, or if it only shows after minutes of warmup.
- **If the PHP-code speedup is high but end-to-end is capped by regex:** that's a finding for Gate 0's real-request profile and for a TRegex PCRE flavor (D7), not a no-go.

## Milestones
1. **M1, reference and harness:** the PHP reference image, the php-ast JSON exporter, the WordPress fetch at a pinned commit, the corpus, and the driver producing PHP 8.5 and 8.4 baselines and checksums.
2. **M2, Truffle skeleton:** the Maven project, `PhpLanguage`, a launcher, the zend_ast JSON loader, functions, ints, floats, strings, echo; K0 runs.
3. **M3, value model:**
   - byte strings (`TruffleString`);
   - `PhpArray`: an ordered hash with packed strategies, copy-on-write and an internal pointer;
   - references, closures, classes (properties, methods, static, `$this`, `self`/`static`/`parent`, interfaces), static variables, globals, include.
4. **M4, built-ins:** the set the kernels reach (from a static scan), plus `preg_*` via PCRE2 FFM.
5. **M5:** all kernels match the checksums. Measure and write the Gate 1 report.

## Status log
- **2026-10-22:** spec written. Pinned WordPress commit: `d86e2079edbf869a0393ee99a91da19351420461` (WordPress/WordPress trunk, 2026-10-22).
- **2026-10-22, M1 done:** PHP 8.5.12 and 8.4 reference images, php-ast exporter, the corpus (62 posts), and the driver. Checksums are identical on 8.4 and 8.5.
- **2026-10-22, M2–M4 first cut (correctness):** the Truffle PHP (`src/`, ~13.4k lines) runs all 7 kernels with **checksums identical to PHP 8.5**. It covers the AST-interpreter core, COW arrays, objects on DynamicObject, closures, exceptions, and 244 built-in functions, including PCRE2 via FFM (D7), json and mbstring. Smoke tests are in `tests/smoke/run.sh`; run Truffle with `./bin-php.sh --ast-root=target/ast bench/gate1/run.php all 60`. `run.php` now prints a fifth column, native (PCRE2) ns, on Truffle.
- **First timing (Windows dev box, Docker PHP vs JVM-mode Truffle, 60 iterations, median of the last 30; indicative only):** Truffle is **3–7× slower** than PHP 8.5 tracing JIT end to end (hooks 18 vs 76 ms, kses 30 vs 123 ms). The measured native time for PCRE2 is also too high: in wpautop it exceeds PHP's whole run (10.3 vs 6.7 ms), so the FFM/PCRE2 path itself is suspect (JIT availability in Git's libpcre2, per-call subject copy, UTF re-validation). No performance work has been done yet; that is the next step (M5).
- **2026-10-22, performance work (M5, first pass).** Measured on Linux in Docker on the dev box (indicative only, not pinned).

  | Kernel | PHP 8.5 | Truffle | End-to-end | Native share (of PHP time) |
  |---|---:|---:|---:|---:|
  | hooks | 17.5 ms | 29.6 ms | 0.59× | 0% |
  | escape | 3.3 ms | 3.8 ms | 0.87× | 4% |
  | slugs | 2.4 ms | 3.3 ms | 0.71× | 23% |
  | wpautop | 6.6 ms | 16.3 ms | 0.41× | ~92% |
  | wptexturize | 3.9 ms | 9.8 ms | 0.40× | 70% |
  | blocks | 12.5 ms | 13.6 ms | 0.92× | 8% |
  | kses | 30.9 ms | 65.5 ms | 0.47× | 22% |

  - **Geometric mean:** 0.59× end to end, up from 0.24× at the first cut. All checksums are identical.
  - **Micro-benchmarks (`bench/micro`) separate the engine from the library code:**
    - User calls, methods, closures and property access now run about **12× faster than PHP's JIT** (inlining plus escape analysis).
    - A bare loop is at parity.
    - Arrays, string concatenation and generic built-in calls are still slower.
  - **What mattered, in order of effect:**
    - `@CompilationFinal(dimensions = 1)` on node metadata arrays. Without it, partial evaluation compiled every parameter-binding path into every call.
    - OSR loops (`LoopNode`).
    - Unboxed locals and properties.
    - Intrinsic nodes for small built-ins.
    - Zero-copy string reads in built-ins (`strspn` at an offset used to copy the whole document).
    - Interned literals (array keys then match by identity).
    - An unsynchronized byte builder.
  - **Why wpautop and wptexturize stay low:** in PHP they are almost all PCRE time, so only a few hundred µs of PHP's run is its own code. The PHP-code metric (D9) only reaches parity there if the Java around each regex call is about as lean as PHP's C wrapper. That gap is per-match allocation and copying in the `preg_*` built-ins, not the engine.
  - **Tools that worked:** `polyglot.cpusampler` (now that roots carry source sections), `compiler.TraceMethodExpansion` with `NodeSourcePositions` (it showed the unfolded arrays and the inlined error-message string building), and JFR for the Java runtime code that compiled PHP calls into.
- **2026-10-22, M5 second pass, and a correction.** The 0.59× above compared Truffle against a PHP baseline recorded earlier, while other jobs loaded the box. Re-recorded on a quieter machine, PHP's own times dropped by about 30%. Every comparison now comes from `bench/gate1/compare.sh`: PHP, Truffle and the native-time run back to back on the same pinned cpuset (14–21; other jobs use cores 0–1, 10–13, 22–23).

  | Kernel | PHP 8.5 | Truffle | End-to-end | Native share | PHP-code |
  |---|---:|---:|---:|---:|---:|
  | hooks | 12.8 ms | 18.6 ms | 0.69× | 0% | 0.69× |
  | escape | 2.5 ms | 3.1 ms | 0.79× | 4% | 0.79× |
  | slugs | 1.7 ms | 2.3 ms | 0.77× | 17% | 0.73× |
  | wpautop | 5.0 ms | 7.9 ms | 0.63× | 66% | 0.36× |
  | wptexturize | 2.8 ms | 4.9 ms | 0.57× | 41% | 0.44× |
  | blocks | 8.2 ms | 8.4 ms | 0.98× | 9% | 0.98× |
  | kses | 21.9 ms | 36.7 ms | 0.60× | 25% | 0.53× |

  - **Geometric mean:** 0.71× end to end, 0.61× PHP code. All checksums are identical. Go needs 2× PHP code, so the gap is still about 3.3×.
  - **What mattered in this pass:**
    - **Throw paths out of compiled code.** Node code built `PhpError` messages with string concatenation, and partial evaluation compiled it in: two thirds of `WP_Hook::apply_filters`' graph was `DimNode`'s "Cannot use object of type … as array". A `transferToInterpreter()` before every node-level throw cut it.
    - **One frame tag per local slot.** Writes picked `setLong`/`setDouble`/`setObject` by each value's runtime type, so the tags differed on the paths that merge after any write of statically unknown type. Graal then disables frame intrinsics for the whole function: 3588 "incompatible frame slot types at merge" warnings over the kernels. Slots now carry a kind in the frame descriptor (Illegal, then Long/Double, then Object, generalized once with a deopt, SL-style), and unwritten slots are illegal (`defaultValueIllegal`). That leaves 0 warnings.
    - **Inline cache for `['Class', 'method']` callables.** Without it, one such hook callback made WP_Hook's dispatch generic for every callback.
    - **Object creation:** a per-`new`-site initializer with cached shape stores (`new WP_HTML_Tag_Processor` 1113 → 239 ns; PHP 117 ns).
    - **Built-ins:** a vectorized TruffleString search in `str_replace`, a byte table for `strtr` with one-byte keys (`wp_kses_hair`), and variadic arguments collected without a boundary.
  - **Measuring lessons:**
    - Micro-benchmarks shorter than about 1 s measure Truffle's warm-up, not its steady state. Run them on a time budget (2–3 s per case, median of the second half).
    - A/B tests of a change run the two builds alternately (`PHP_CLASSES` picks a build in `bin-php.sh`) and take the best of 3 rounds. One run per build varied by ±15% on this box.
  - **Next:**
    - The per-call cost of built-ins (`BuiltinRootNode`: argument array, boundary). Its self time is 6% in kses.
    - The `preg_*` wrappers' allocation, which decides wpautop/wptexturize PHP code.
    - Array element access (`PhpArray.find`).
    - WP_Hook's iteration bookkeeping (copy-on-write separation for `next()`).
- **2026-10-22, M5 third pass.** `compare.sh` (pinned to 14–21) while the box was quiet, at 5–7% load:

  | Kernel | PHP 8.5 | Truffle | End-to-end |
  |---|---:|---:|---:|
  | hooks | 7.1 ms | 9.3 ms | 0.77× |
  | escape | 1.4 ms | 1.3 ms | 1.07× |
  | slugs | 1.0 ms | 1.6 ms | 0.61× |
  | wpautop | 2.7 ms | 3.8 ms | 0.71× |
  | wptexturize | 1.5 ms | 3.2 ms | 0.48× |
  | blocks | 3.7 ms | 6.7 ms | 0.55× |
  | kses | 12.5 ms | 25.0 ms | 0.50× |

  - **Geometric mean:** 0.64× end to end; 0.69× in the previous quiet run. All checksums are identical.
  - **Measurement caveats, now the main uncertainty:**
    - **Load moves PHP more than Truffle.** On a quiet box PHP's times drop 25–45% (kses 21.9 → 12.5 ms), Truffle's less, so the ratios measured under load (0.71–0.73×) flattered Truffle.
    - **Truffle's steady state varies about ±15% between JVM runs** (blocks 5.5–6.7 ms quiet), presumably from different compilation decisions. A/B tests therefore take medians over alternating rounds.
    - **The PHP-code metric is not trustworthy for the regex kernels.** The Truffle-side native estimate (timed downcalls, clock cost subtracted in place) includes FFM transitions and clock residue: for wpautop it is 95% of PHP's whole run, giving a meaningless 0.11×. The fix is to record the PCRE2 call sequence and replay it in C against PHP's pcre2lib, which gives one exact native time for both engines. To do before the Gate 1 report.
  - **What mattered in this pass:**
    - **Hash lookups out of line** (`PhpArray.hashGet`/`slotOf` as boundaries): the bucket walk had been inlined at every element read. hooks −33%, kses −26%.
    - **`preg_*` with large subjects:** a fresh `Arena.ofAuto` segment per subject cost ~6 µs on a 10 KB post. Reusable buffers fixed that (the micro got 17× faster). Output builders are pooled, and matches use `pcre2_jit_match` like php-src.
    - **Built-ins:**
      - `implode` and `array_unique`/`diff`/`intersect` no longer convert through Java strings.
      - `strtr` uses a byte table.
      - `str_replace` uses a vectorized search.
    - **Smaller compiled graphs:**
      - Runtime throws transfer to the interpreter.
      - Class profiles on `isset`/`empty`/`?:`/casts.
      - An inline type check for typed parameters.
  - **Tried and reverted:** packed-array growth out of line. The boundary stops escape analysis of short-lived arrays (slugs +27%, kses +16%). ParallelGC was mixed (hooks 2× worse).
  - **Where the gap is now:** no single hotspot is left. The kses functions run at 0.6–0.9× each, and the rest is per-call overhead spread across array access, `preg_*` wrappers on short strings, and object creation (`new` is still 2–3× PHP's cost). Reaching the 2× go line on PHP code needs a structural change, not more trimming. Candidates: TRegex for the regexes it supports (D7), array storage specialized by shape, and a native image for steadier steady state.
- **2026-10-23, first-principles pass** (details in [first-principles.md](first-principles.md)):
  - **What changed:** packed int arrays are now unboxed `long[]`, and an element access compiles only the case it has seen. A call or field write left on a cold path made every access reload the array's fields.
  - **K0 arithmetic kernels:** 1.96× PHP's JIT (geomean; was 1.63×). fannkuch (0.66×) and mandelbrot (0.81×) remain below PHP.
  - **WordPress kernels:**

    | Kernel | PHP 8.5 | Truffle | End-to-end |
    |---|---:|---:|---:|
    | hooks | 7.96 ms | 9.34 ms | 0.85× |
    | escape | 1.47 ms | 1.54 ms | 0.95× |
    | slugs | 0.99 ms | 1.48 ms | 0.67× |
    | wpautop | 2.82 ms | 4.21 ms | 0.67× |
    | wptexturize | 1.60 ms | 2.91 ms | 0.55× |
    | blocks | 4.55 ms | 5.84 ms | 0.78× |
    | kses | 12.18 ms | 22.32 ms | 0.55× |

    Geomean **0.70×** end to end (was 0.64–0.69×), same window, cores 14–21, 100 iterations. Checksums are identical.
  - **Conformance:** php-src's tests now run beside the kernels: 2,043 of 7,341 pass. They're the safety net for engine changes.
  - **Measurement fix:** PHP's opcache skipped files modified less than 2 seconds earlier (`file_update_protection`), so freshly written micro-benchmarks ran without the JIT on the PHP side. Now off in the image. The kernel files were old, so earlier Gate 1 numbers stand.
  - **Next:** apply the same rule to WordPress's hot paths: hash and string-key reads, `foreach`, property access and calls, where cold generic paths are compiled in too. Find what keeps fannkuch's function large.
- **2026-10-23, measurement pass** (after "what is missing to reach the gate"):
  - **Native time is now exact:**
    - The Truffle PHP records each kernel's last-iteration PCRE2 calls (`-Dphp.pcre2.record`).
    - `bench/gate1/pcre-replay.c` replays them against the same pcre2lib, called the way the preg wrappers and php-src call it, and reports the median of 200 passes.
    - It replaces the Java-side estimate, which was 2–3× too high (wpautop 2.72 → 1.47 ms, kses 4.42 → 1.39 ms).
    - It's a lower bound: in a real run, PHP code between matches evicts some cache lines.
  - **Truffle needs 250–300 iterations to reach steady state on these kernels.** Tier 2 compiles of the big WordPress call trees take seconds; one `WP_Hook::do_action` compile took 1.8 s. Earlier runs judged iterations 75–100 or 151–200 and measured partly warmed-up code. `compare.sh` now defaults to 400 iterations.
  - **Warmup** (the gate's third criterion): roughly 3 s for hooks and 6–7 s for kses until the plateau, on 8 pinned vCPUs.
  - **Steady state** (500 iterations, a loaded window):

    | Kernel | PHP 8.5 | Truffle | Native (replay) | End-to-end | PHP code |
    |---|---:|---:|---:|---:|---:|
    | hooks | 8.79 ms | 9.11 ms | 0 | 0.97× | 0.97× |
    | escape | 1.67 ms | 1.66 ms | 0.05 ms | 1.00× | 1.00× |
    | slugs | 1.03 ms | 1.77 ms | 0.13 ms | 0.58× | 0.55× |
    | wpautop | 2.66 ms | 5.00 ms | 2.12 ms | 0.53× | 0.19× |
    | wptexturize | 1.60 ms | 2.38 ms | 0.46 ms | 0.68× | 0.60× |
    | blocks | 3.86 ms | 5.73 ms | 0.33 ms | 0.67× | 0.65× |
    | kses | 13.13 ms | 20.70 ms | 1.89 ms | 0.63× | 0.60× |

    Geomean **0.70× end to end, 0.58× PHP code**. The replay is load-sensitive too: wpautop's native time read 1.47 ms in a quieter window, which puts its PHP code at about 0.32×.
  - **Per-primitive micro kernels** (`bench/micro`, 200k operations each, PHP's JIT on runtime-built arrays):
    - **Faster than PHP:** inlined calls (11–70×), `isset` (1.85×), `new` (1.7×), property access (1.8×).
    - **Slower:**

      | Primitive | Speedup |
      |---|---:|
      | copy-on-write copy of a hash array | 0.20× |
      | `foreach` over a list (0.34× before the fix below) | 0.51× |
      | `.=` | 0.41× |
      | `foreach` over a hash | 0.51× |
      | string-key reads | 0.46–0.60× |
      | string built-ins | 0.49× |

    - **Calls Graal doesn't inline cost more than PHP's:** 6.5–7 ns for methods and statics against PHP's 3–3.5. A built-in costs **48 ns** against 2.6, because it goes through its own call target.
  - **Tried:**
    - `foreach` compiling only the array layouts it has seen, with the loop state in hidden frame slots: micro 1.7× faster, Gate 1 neutral, kept.
    - Built-ins called directly from the call site, skipping their call target (48 → 13 ns per call when not inlined): Gate 1 neutral to slightly worse at steady state, and it made compiles bigger and slower. Reverted.
  - **Profile of kses** (Truffle's CPU sampler, per PHP function): no hotspot. About 6% of samples are direct calls Graal didn't inline, each allocating an argument array and a frame. `preg_replace_callback` allocates a matcher and group arrays per match. Truffle drops the Tier 2 compile of a function "inlined into its only caller", even when the function is also reached indirectly; such functions keep running their Tier 1 code.
  - **Where that leaves the gate:** hooks and escape are at parity. The regex-heavy kernels are at 0.2–0.65× on PHP code. To reach 2×, the slow primitives above need to become 3–10× faster, and calls that aren't inlined need to cost what PHP's do.
- **2026-10-23, FFM review of the PCRE2 bindings** (against GraalVM's [FFM API in Native Image](https://www.graalvm.org/jdk25/reference-manual/native-image/native-code-interoperability/ffm-api/) page and the JVM rules):
  - **Already fine:**
    - `static final` downcall handles, called with `invokeExact` and exact types.
    - `--enable-native-access` is set.
    - `size_t` is mapped to `JAVA_LONG` (correct on 64-bit Linux and Windows; no C `long` anywhere).
    - Downcalls only run behind the built-ins' `@TruffleBoundary`.
  - **Shared arenas cost a session acquire and release on every downcall.** Subjects of 4 KB and up lived in `Arena.ofShared()` buffers, which added 11 ns per match (13-byte match: 12 → 23 ns; global or auto arena: 12–14 ns). They now come from `Arena.ofAuto()`. Gate 1: neutral to slightly better.
  - **Critical downcalls (`Linker.Option.critical(false)`) were tried and rejected.** They are cheaper per call (12 → 8 ns), but made wpautop and wptexturize about 40% slower. The critical stub skips `vzeroupper`, so PCRE2's SSE code runs with the upper vector halves dirty from Graal-compiled Java code. A 4 KB scan right after an AVX array copy: 570 ns normal, 656 ns critical. Normal downcalls stay the default; `-Dphp.pcre2.critical=native|heap` keeps both variants for experiments. `bench/ffm/FfmBench.java` measures all of this.
  - **Not yet ready for Native Image:**
    - There's no `reachability-metadata.json` with a `"foreign"` `"downcalls"` section for the 13 PCRE2 signatures.
    - `PcreFunctions.Native` creates its handles, and a native match-context `MemorySegment`, in a static initializer. The page says such classes must be initialized at run time, and native segments may not be in the image heap.

    To do when the native image (D8) is built.
- **2026-10-23, Gate 0.5 (D10) progress check after steps 1–2:**
  - **Step 1:** per-context state replaces the static fields.
  - **Step 2:** 25 hot built-ins are DSL nodes compiled in place of the call. Their fast specializations use cached TruffleString nodes (vectorized scans), and every other case goes to the regular built-in's body.
    - `array_filter` and `array_map` call their callbacks through an inline cache of direct calls.
    - `--engine.InstrumentBoundaries`, which would have counted boundary calls, crashes libgraal on GraalVM 25.0.4 (`ClassCastException` in `ConstantNode.findLength`), so the hot set came from built-in call counts per kernel.
  - **Gate 1 effect** of step 2 (ABBA, 2 pairs, 300 iterations): kses −14%, hooks −12%, wptexturize −11%, slugs +8%, the rest neutral. Checksums and the php-src run unchanged.
  - **Not done in step 2:** the `preg_*` front ends. A node would cache the compiled pattern per call site, which has to wait for step 4's language-level pattern cache.
- **2026-10-23, Gate 0.5 (D10) step 3, per commit:**
  - **What changed.** These node families are now DSL nodes:
    - assignment (`5964aa1`);
    - array element access and `foreach` binding (`c9a3424`);
    - comparisons and object creation (`3363a93`);
    - unary operators, casts and truthiness (`ToBooleanNode`).

    Every hand-written "seen" flag in them is gone: the DSL's storage and type guards replace them, and `BranchProfile`s cover branches that need no type dispatch. Each commit passed smoke, both checksum sets and the php-src run. The run lost no tests and gained the four `range_bug70239` tests from the `range()` fix (`9c3d9e5`).
  - **Speed, from builds run interleaved in rotating order** (ABCD, DCBA, …; 4–6 rounds, 400 iterations; ratio to the parent commit, <1 is faster). The shared box was noisy: per-round values of one build varied by 30–50% (another agent's WSL VM was busy). Only ratios whose whole range sits on one side of 1.0 count:
    - **Element access and `foreach`:**
      - slugs **0.59×** (0.52–0.60) and escape 0.92× (0.88–0.93);
      - wpautop 1.10× (1.00–1.16) and kses 1.06× (1.03–1.11) slightly slower.
    - **Comparisons:** escape 0.96× (0.81–0.99), the rest neutral.
    - **Assignment, object creation, `LocalVarNode` profiles:** neutral within the noise. A first run hinted that hooks and escape were 13–25% slower after `3363a93`; a bisect with 6 rounds didn't reproduce it.
  - **Not done in step 3:** calls. They depend on step 4's per-context lookup design (`FunctionCallNode`, `ClassRefNode` and constant caching), so they move there.
- **2026-10-23, Gate 0.5 (D10) step 4: context-independent ASTs and `ContextPolicy.SHARED`.**
  - **What landed, one commit per part:**
    - built-in functions are language-level, with per-context `BuiltinState` (4a);
    - the translator no longer holds a context: `ClassTemplate`, declaration nodes, only predefined constants folded (4b);
    - translated files are cached in `PhpLanguage` and revalidated against the AST export's modification time and size (4c);
    - functions, constants and classes are looked up per context by language-level ids, with single-context speculation (4d);
    - class caches key on an interned `ClassKey` (4d-3);
    - constant arrays are frozen (immutable, as in opcache);
    - `SHARED` (4e).

    Every commit passed smoke, both checksum sets and the php-src run, which lost no tests.
  - **New tests:**
    - The smoke run also runs each test in three contexts on one engine.
    - `tests/shared/run.sh` runs 40 contexts on 4 threads that write to arrays which start as language-level data. Without frozen arrays, every run sees corruption.
  - **Speed, single context, end of step 3 (`fe273fc`) → `90d4176`** (interleaved, 4 rounds, 400 iterations):
    - hooks **1.11×** (1.04–1.15) and wptexturize **1.09×** (1.04–1.29) slower; escape 1.08× (1.00–1.21);
    - the rest neutral.

    4a–4c alone measured neutral to faster (slugs 0.88×). 4d-1's per-call function lookup cost hooks 10% before the single-context speculation was added.
  - **Server mode (`PHP_CONTEXTS=2`: two contexts on one engine, the second measured) against single context, same build:**
    - hooks 1.02×, escape 0.99×, wptexturize 0.98×, kses 1.03×;
    - slugs **1.20×** (1.07–1.33), blocks 1.09×;
    - wpautop 0.85× (unexplained).

    From here on both modes are reported; the multi-context number is the one that counts for Gate 2.
  - **Bisect of the single-context cost** (4 rounds over 4d–4e, then 8 rounds over the last two commits): no single commit stands out. Each measured 0.9–1.09×, every range straddling 1.0. A first hint that frozen arrays cost wptexturize 15% didn't reproduce over 8 rounds (1.02×). The cost is the sum of small per-commit costs:
    - key-guarded caches add a load per method call;
    - `getKey()` has a null check;
    - holder updates take a branch for frozen arrays.

    Worth recovering during Gate 1 tuning, measured in both modes.
- **2026-10-26, where Gate 1 time goes now, the array stores, `preg_*` and TRegex.**
  - **Re-measured at `c51feb0`** (`compare.sh`, 400 iterations): 0.93× end to end, 0.92× on PHP code. **Not comparable** with the 0.70× above. Five processes of another job ran at 100% on all cores, and PHP's own times were 1.5–1.8× those of the last quiet window (blocks 6.77 vs 3.86 ms, wpautop 4.04 vs 2.66 ms). Load slows PHP more than Truffle, so the ratio flatters Truffle.

    The micro kernels at the same time:

    | Primitive | Speedup |
    |---|---:|
    | string-key read | 0.27× |
    | built-in call | 0.30× |
    | `foreach` over a hash | 0.34× |
    | `foreach` over a list | 0.43× |
    | copy-on-write copy | 0.44× |
    | literal record | 0.49× |
    | `.=` | 0.49× |

  - **Profile:** JFR with 1 ms sampling over the last 40% of 500 iterations, samples put in categories by their top Java frame. Truffle-compiled PHP code shows as the runtime methods inlined into it. Native time isn't sampled, so "regex" below is the Java around PCRE2.

    | Kernel | Arrays | Strings | Calls | Regex |
    |---|---:|---:|---:|---:|
    | hooks | 59% | 8% | 14% | 0% |
    | kses | 38% | 17% | 17% | 12% |
    | blocks | 36% | 39% | 2% | 3% |
    | wptexturize | 33% | 19% | 16% | 8% |

    In blocks, 27% is TruffleString's lazy concatenation being flattened and copied (`.=` on a string that is then read).
  - **Array stores, three commits:**
    - `68ebd0d`: the elements are one field whose class is the strategy (`long[]`, `Object[]` or a `Hash`), as in TruffleRuby. A hash of up to 8 slots has no bucket index and is scanned. An int key hashes to itself, as in php-src.
    - `6f8a2bb`: literal records (distinct literal keys, one a string) are built as hashes directly, with keys normalized and hashed once. `foreach` by value finds the next position by the layout it binds with.
    - `bb2f5d4`: removes `6f8a2bb`'s inline small-hash reads and writes.

    `68ebd0d` alone cost hooks 1.2×: sites that don't specialize paid for the dispatch (`rawAt` tried two strategies before the hash; `foreach`'s hash guard was two negations). `6f8a2bb` fixed those.

    **Inline code at element access sites costs more than it saves.** `6f8a2bb` also scanned small hashes inline on reads and inserted inline on writes, with TruffleString's hash and equality nodes. That grew every string-key access in the PHP function's graph. In hooks it pushed `WP_Hook::apply_filters` out of `apply_filters`' inlining budget (0 of 1 calls inlined instead of 7 of 8; `TraceInlining`), about 20% slower. Even the identity-only scan kept it out. A site now keeps one inline check (its remembered slot) and looks a key up out of line. **The inlining budget for PHP call trees is the scarcer resource.**

    Against `c51feb0` (6 rounds; hooks and slugs again over 10): kses **0.92×**, blocks **0.94×**, hooks 1.00×, slugs 1.01×, escape 1.00×, wpautop 1.01×, wptexturize 1.04×. On the micro kernels the literal record vanishes (escape analysis removes it) and the copy-on-write copy is 0.76×. Smoke (with a new `array-stores` test, also with `CompileImmediately`), both checksum sets, 40 contexts, boot, mysqli and php-src (2932 strict, the same) all pass.
  - **`preg_*` front ends.** The Java around PCRE2 in kses:
    - group arrays 4.7%;
    - group string copies 2.0%;
    - FFM transitions about 5% (97k matches at about 12–16 ns);
    - pattern and template map lookups about 1.7%.

    What was tried:
    - Presizing the group lists measured nothing (reverted).
    - A call-site pattern cache would save at most the 1.7%, below this box's noise, so it wasn't built.
    - Raw `long` addresses instead of `MemorySegment`s in the downcall measured the same (16.1 vs 16.6 ns; `FfmBench`): the segment checks are free.

    The cost is the thread-state transition: `critical` downcalls are 11.6 ns, but stay rejected for the `vzeroupper` reason above.
  - **TRegex beside PCRE2, per pattern: worth it.** Branch `exp/tregex` replays the kernels' recorded PCRE2 calls through TRegex 25.0.4's Python flavor (the closest to PCRE; it has no PCRE flavor) in one JVM. PCRE2 runs through FFM as the wrappers call it. TRegex runs from a compiled loop with cached interop, as a node would call it.

    No match differs, comparing every capture group's bounds (`3ede04a`).

    **Per kernel, TRegex alone loses** (`078c552`; TRegex time over PCRE2 time, 150 passes):

    | Kernel | TRegex / PCRE2 |
    |---|---:|
    | escape | 0.72× |
    | slugs | 0.30× |
    | wptexturize | 1.75× |
    | wpautop | 7.1× |
    | kses | 6.9× |
    | blocks | 22.7× |

    That is 55.7 vs 7.7 ms over all covered matches. 11 patterns can't be written in its flavors at all: conditionals, variable-width lookbehind, recursion, `\g`.

    **Per pattern the picture splits** (`dc5a734`, `bench/gate1/results/tregex-*.txt`; the Java flavor measures the same):
    - TRegex is 2–4× faster on character classes, literals and short anchored patterns.
    - It is 8–20× slower on:
      - long tag-name alternations (wpautop);
      - `wp_kses_split`'s big pattern;
      - lazy quantifiers;
      - a lookbehind before `\s*`;
      - backreferences.

    A rule on syntax doesn't separate the two: the slow ones mostly have no lookaround or backreference either.

    **Adaptive choice** (`3ede04a`, simulated on the recording). A pattern TRegex accepts runs its first 1024 matches on both engines, timed in batches of 64. The first half is TRegex's warmup; the second decides, and TRegex must be 20% faster to be chosen. Steady-state regex time against PCRE2 alone, with the per-pattern oracle beside it:

    | Kernel | Adaptive | Oracle | Patterns on TRegex |
    |---|---:|---:|---:|
    | escape | 0.59 | 0.56 | 4 |
    | slugs | 0.31 | 0.30 | 1 |
    | wpautop | 0.98 | 0.98 | 3 |
    | wptexturize | 0.99 | 0.99 | 1 |
    | blocks | 1.01 | 1.00 | 0 |
    | kses | 0.68 | 0.67 | 14 |

    On the kernels that is about 1.1 ms of kses (about 5% of it), 0.2 ms of slugs (about 17%) and 2% of escape. D7 stands for the general case (PCRE2 is the reference and the fallback).

    A product version needs:
    - **A syntax subset that means the same in TRegex and PCRE**, translated by our own parser; everything else stays on PCRE2. `/u` patterns stay out at first: PCRE checks UTF-8 validity, and its UCP classes differ from Python's.
    - **The empty-match retries** (`NOTEMPTY_ATSTART|ANCHORED`) on TRegex's `MustAdvance` and `MatchingMode=match`.
    - **Cached interop from a node**, so a preg call doesn't pay uncached dispatch.
    - **Language-level decisions shared by contexts.**
    - **Shadowing only hot patterns**, to bound TRegex's compile time and memory.
  - **Next:**
    - the hash lookups that stay out of line, and holder counting;
    - `.=` and flattening of lazy concatenations (blocks);
    - a re-measurement against PHP in a quiet window.
