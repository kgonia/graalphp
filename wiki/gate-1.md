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

    The driver's checksum (`bench_render`, `crc32`) runs outside the timed region but inside the process, so a profile of `run.php` includes it. The table comes from a copy of the driver that checksums only the last iteration:

    | Kernel | Arrays | Strings | Calls | Regex |
    |---|---:|---:|---:|---:|
    | hooks | 62% | 2% | 14% | 0% |
    | kses | 31% | 16% | 22% | 13% |
    | blocks | 48% | 11% | 5% | 11% |
    | wptexturize | 33% | 14% | 13% | 9% |

    **Correction:** the first profile, with the checksum, showed 39% strings in blocks, 27% of it lazy concatenation being flattened. That was the checksum: `bench_render`'s `.=` and `crc32` reading the result. In the timed region concatenation is 0.1–1.4% of the samples in all four kernels.

    The top frames that do count:
    - blocks: hash lookups (`Hash.find` 15%) and hash building (insert, add, new stores, conversions about 15%, much of it `json_decode`'s objects);
    - hooks: `Hash.find` 13%, holder counting (`countIn`, `addHolder` 12%);
    - kses: group arrays, holder counting, built-in calls.
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
  - **Hash lookups and building**, from the profile without the checksum:
    - `4cd3588`: an `(array)` cast of an object, and `preg_match`'s groups with distinct names, build their hash at its size with no lookups. They were most of blocks' hash work (`WP_Block_Parser` casts its block objects, its token regex has named groups). A key the code has as a literal is that interned literal (`PhpLanguage.propertyKeyString`; only literals enter the map, so `json_decode`'s keys can't grow it), so `$cast['blockName']` hits the slot cache by identity. `array_slice`'s result is sized for the slice.

      Against `bb2f5d4` (10 rounds): blocks **0.88×** (IQR 0.84–0.93), kses 0.96×, escape 0.95×. Over 6 rounds: wptexturize 0.91×, wpautop 0.92×, hooks 0.98×, slugs 1.01×.
    - `a1c2ca5`: a read with an int key into a hash walks the bucket chain inline. This is a specialization of its own, so sites that only meet packed arrays don't carry it. These are WP_Hook's priority-keyed callbacks and the iteration stacks.

      Against `4cd3588`: hooks **0.87–0.93×**, escape **0.79–0.86×**, slugs 0.86×, wpautop 0.92–0.97×, kses 0.94–0.99×. Blocks measured 1.09×, 0.99× and 1.02× over three runs (8, 8 and 10 rounds), within the noise.
    - **Tried and dropped:**
      - A remembered slot for int keys and for writes measured nothing: hooks asks for a different priority each time round its loop.
      - One specialization with a profiled packed/hash branch gave hooks 0.82× but blocks 1.05×.
      - Doubling Truffle's inlining budgets (`InliningInliningBudget`, `InliningExpansionBudget` 24000) gave nothing either: hooks 1.01×, wpautop 0.95×, kses 1.10×.
  - **Holder counting** (`addHolder`, `removeHolder`, `countIn`; 2–5% per kernel) is the refcount approximation's own work, one field update per binding as in php-src. It doesn't shrink without changing the model, so it was left.
  - **State at the end of the day, and next:**
    - **Arrays:** strategy stores; hashes built at their size where the key count is known; int-key hash reads inline (`68ebd0d`–`a1c2ca5`). Next: string-key normalization of computed keys (`ArrayKeyNode.string`, 5% of kses), and the copy-on-write copies.
    - **Regex:** PCRE2 through FFM. TRegex beside it, chosen per pattern, is decided ([D12](decisions.md)) but not built; the experiment stays on `exp/tregex`. Front-end costs are measured and need no change now.
    - **Strings:** `.=` is not a hot spot of the timed kernels (above); no work planned.
    - **Pending:** a re-measurement against PHP in a quiet window. The last comparable number is still 0.70× end to end and 0.58× on PHP code (2026-10-23).

## Quiet-window re-measurement against PHP, with memory (2026-10-27)

`compare.sh all 400` at `cd1eaa6`, CPUs 14–21, PHP and Truffle PHP back to back. Native time is the C replay of the recorded PCRE2 calls; "code" is the ratio with that time taken out of both.

| kernel | PHP ms | Truffle ms | native ms | share | end to end | PHP code |
|---|---|---|---|---|---|---|
| hooks | 6.99 | 9.95 | 0.00 | 0% | 0.70× | 0.70× |
| escape | 1.34 | 1.21 | 0.04 | 3% | 1.11× | 1.11× |
| slugs | 1.06 | 0.95 | 0.09 | 9% | 1.11× | 1.13× |
| wpautop | 2.95 | 3.53 | 1.50 | 51% | 0.84× | 0.71× |
| wptexturize | 1.53 | 2.24 | 0.33 | 22% | 0.68× | 0.63× |
| blocks | 3.93 | 4.42 | 0.24 | 6% | 0.89× | 0.88× |
| kses | 12.13 | 18.20 | 1.40 | 12% | 0.67× | 0.64× |

Geomean **0.84× end to end, 0.81× PHP code**, from 0.70× and 0.58× on 2026-10-23. Checksums are the same. Escape and slugs are now faster than PHP's JIT. Hooks, kses and wptexturize are the furthest from 2×.

**Peak memory: PHP 32 MB, Truffle PHP 1534 MB (48.6×).** That is the whole container's cgroup `memory.peak`: the JVM, Graal's compiler, the compiled code and a heap with no limit set. The JVM's default maximum is a quarter of the machine's memory, and a heap that size grows rather than collect. What the heap needs at a set limit (`-Xmx`), and what that limit costs in time, is the next memory measurement.

### Where Truffle PHP's memory goes (2026-10-27)

`ab.sh` variants can now carry JVM options (`g9@-Xmx512m`). Heap limits at `90a6797`, 6 interleaved rounds, all kernels at 400 iterations:

| heap limit | peak memory | memory ratio | time |
|---|---|---|---|
| none | 1583 MB | 1.00 | 1.00 |
| `-Xmx1g` | 1053 MB | 0.66 (IQR 0.64–0.70) | 0.97–1.02, wpautop 1.06 |
| `-Xmx512m` | 910 MB | 0.58 (IQR 0.57–0.60) | 0.98–1.02, wpautop 1.09 |
| `-Xmx256m` | 772 MB | 0.50 (IQR 0.46–0.53) | 0.98–1.04 |

A heap limit halves the footprint and costs little time; with no limit the heap only grows. Below that, about 500 MB stays outside the heap:
- **Sampled at the peak (256 MB heap):** the process's anonymous memory is 692 MB, while Native Memory Tracking accounts for 249 MB. At exit NMT shows 356 MB: heap 202, GC structures 55, code cache 44, metaspace 21. The page cache charged to the container is under 1 MB.
- **libgraal** is most of the untracked rest. libgraal is Graal compiled into a native-image shared library, and its isolate heap is invisible to NMT. Single runs at a 256 MB heap peaked at 746 MB with libgraal and 504 MB with jargraal (`-XX:-UseJVMCINativeLibrary`, the compiler as Java code on the heap). At 512 MB the jargraal run peaked at 707 MB.
- **Ruled out:**
  - `MALLOC_ARENA_MAX=2` (840 MB, one run).
  - `-XX:CICompilerCount=2`: memory 1.00, while warmup got slower (hooks 1.34, escape 1.26).

Not measured yet: jargraal's time. Its warmup is the known cost. That A/B was stopped when the machine ran short of memory.

For a server this is a fixed cost per process, not per request: PHP-FPM pays its ~30 MB for each worker. The comparison that matters for Gate 3 is one JVM serving N concurrent requests against N workers.

### Native image: tried, not better (2026-10-27)

Truffle PHP built as a native standalone with Oracle GraalVM 25 (`native-image`, runtime compilation included; scripts kept in `tests/scratch/native-*.sh`, not committed). It is an 80 MB binary, and it gives the same checksums as PHP on the Gate 1 and K0 kernels. Running it: `PHP_NATIVE=target/native/php` for `bin-php.sh` and `truffle.sh`, or the `ab.sh` variant `native/php`.

- **The build found three real bugs against the Truffle idiom:** `PhpError`'s and `PhpException`'s message building, and `new HashMap` in `IncludeNode`, sat on paths that compilation of PHP code reaches. They are now behind `@TruffleBoundary`. On the JVM this changed nothing measurable (6 rounds: 0.94–1.02 per kernel, memory 0.99).
- **Regex is 25–160× slower:** `preg_match` 3.8 µs against 0.15 µs, `preg_replace` 95 µs against 0.6 µs. A downcall handle created at run time isn't a constant in native image, so every PCRE2 call takes the generic method-handle path. Handles made at build time are refused (`linkToNative`). A fast path would need `@CFunction` for the native build, or Truffle NFI.
- **Other built-ins are as fast as on the JVM or faster** (`strtolower` 121 against 175 ns, arrays 618 against 739 ns).
- **PHP code is slower** (hooks: 11–12 ms against 9 ms).
- **Memory is no lower:** hooks over 400 iterations peaked at 881 MB native, and at 1040 MB with `-Xmx256m`, against 695 MB for the JVM at `-Xmx256m`. The limit doesn't cover runtime compilation's memory.

Native image is shelved. It would be worth reopening for startup time (per-request processes), not for throughput or footprint.

## TRegex on GraalVM 25.4: why the slow patterns are slow, and what fixes them (2026-10-31)

Branch `tregex-25.4` (off `graalvm-bump`), with the `exp/tregex` harness cherry-picked and extended (`369e0dc`, `a8fc531`): TRegex options, a call mode (`full`, `boolean`, `two_step`, `group0`), a pattern filter, and rewrites. Every TRegex result is still compared with PCRE2's on every group of every recorded call; no match differs. `bench/gate1/tregex-ab.sh` runs interleaved rounds. A run waits until 14–21 have been free of other benchmarks for two minutes, and is repeated if one starts during it. The recording (`compare.sh`'s, 30 iterations), re-recorded on 25.4, is byte-identical to the one of 2026-10-27 and has the same matches per kernel as `3ede04a`.

Timed results: CPUs 14–21, 150 passes, 3 rounds, Python flavor. TRegex/PCRE2 is measured inside one JVM, so it is the number to compare; PCRE2's own ns moved up to 2× between quiet and loaded windows. Exploratory runs (logs, traces, single rounds) were on 6–13 and are marked as such.

### 25.4 against 25.0.4: no change

The same source, built against each version's jars (`bench/gate1/results/tregex-25.4-version.txt`). TRegex/PCRE2, median of 3 rounds:

| Pattern | 25.0.4 | 25.4 |
|---|---:|---:|
| kses 46, `wp_kses_split` | 16.9× | 17.0× |
| blocks 44, `WP_Block_Parser` | 21.2× | 21.6× |
| wpautop 21, 18: `(</?(?:table\|…)` | 11.8×, 12.0× | 10.2×, 10.2× |
| wpautop 14, 17: `<p>\s*(</?(?:table\|…)` | 15.6×, 16.7× | 13.2×, 14.1× |
| wpautop 20: `(?<!<br />)\s*\n` | 15.5× | 16.0× |
| wpautop 19: backreference | 17.3× | 17.9× |
| classes and literals (kses 45, 47, 48, slugs 4) | 0.21–0.39× | 0.22–0.34× |

- **Per kernel, TRegex alone** over all covered patterns: kses 5.4× and 5.2×, wpautop 8.9× and 6.8×, blocks 21× and 20×.
- **The adaptive choice** is the same: kses 0.58 and 0.53, slugs 0.30 and 0.31, escape 0.57 and 0.58, the rest about 1.0.
- **Peak memory:** 1639 MB and 2019 MB.
- **Execution paths:** the strategy logs show the same path for every slow pattern on both versions.

TRegex's CHANGELOG for 25.1–25.4 lists only Unicode 17 (25.1.3). Between the `graal-25.0.4` and `graal-25.4.4.1.1` tags, 131 files of `regex/` changed. The performance commits:
- capture-group tracking micro-optimizations (`fb77482615`);
- smaller NFA executors, with the backtracker's group bounds encoded (`7c44bcf961`);
- `ByteIndexOfStringSetNode` for literal word lists (`f941f54268`);
- resource limits (`aab95acdc8`).

None of them touches the choice of execution path or the DFA size limits.

### Why, per slow pattern

The path comes from TRegex's own logs: `-Dpolyglot.log.regex.MatchingStrategy.level=FINE`, plus `BailoutMessages`, `SwitchToEager` and `AutomatonSizes`. The code is graal at `graal-25.4.4.1.1` (`95ce149`), under `regex/src/com.oracle.truffle.regex/src/com/oracle/truffle/regex/`. A regex starts on the NFA simulation and compiles a lazy DFA after 10 calls (`tregex/TRegexOptions.java:68`).

- **The tag-name alternations of wpautop (14, 17, 18, 21) and `wp_kses_split` (46): the DFA is too big, so they stay on the NFA simulation for good.**
  - The log says `too many transitions`. The cap is 1300 DFA transitions (`TRegexOptions.java:166`, option `MaxDFASize` at `RegexOptions.java:272-275`), checked in `tregex/dfa/DFAGenerator.java:174` and `tregex/nodes/dfa/TRegexDFAExecutorNode.java:239-240`.
  - On wpautop it is the *backward* DFA, the one that finds the match start: 2026–2031 transitions over 119–123 states. The forward DFA has 698–1462. For kses 46 it is the forward DFA (1733).
  - One executor over the cap fails the whole lazy DFA, and the regex stays on `TRegexNFAExecutorNode` (`tregex/nodes/TRegexExecNode.java:207-213`).
  - **Fixable:** `MaxDFASize=32000`.
- **`wp_kses_split` after that fix: capture groups.**
  - With the DFA it is still 2.2×. Its eager DFA (one pass that tracks the groups) is refused as `Too much additional capture group tracking overhead` (`tregex/TRegexCompiler.java:94-97`). The cost cap is 3000 (`TRegexOptions.java:172`) and is not an option. So each match takes the forward, backward and group-tracking passes.
  - `execBoolean` alone is 0.44×.
  - **Inherent** when preg needs the groups, as `preg_replace_callback` does here.
- **The backtracking executor: blocks 44 (negative lookahead, possessive), wpautop 20 (negative lookbehind), wpautop 19 (backreference), wptexturize 28 (negative lookahead).**
  - The DFA refuses backreferences, negative lookaround, non-literal lookbehind and atomic groups (`tregex/parser/ast/RegexAST.java:724-737`).
  - The backtracker *is* partially evaluated into the pattern. With at most 200 states and transitions (`TRegexOptions.java:233`) it runs a `MERGE_EXPLODE` loop (`tregex/nodes/nfa/TRegexBacktrackingNFAExecutorNode.java:300, 465-478`). For blocks 44 that is a 4.1 s Tier-2 compilation into 288 KB of code (`TraceCompilation`, exploratory).
  - Turning it off (`MaxBackTrackerCompileSize=0`: the same loop behind a boundary) made blocks 44 23.7× instead of 15.3× (exploratory). Partial evaluation is worth 1.5×; the rest is the algorithm.
  - Every choice point copies the whole frame: the index, two ints per group, the Python flavor's last group and the quantifier counters (`TRegexBacktrackingNFAExecutorLocals.java:204-206, 350-357`; `TRegexBacktrackingNFAExecutorNode.java:782`).
  - Per call (Python `re` over the recording), blocks 44 skips 33 bytes and matches 44 on average. That is about 15 ns per byte, against PCRE2's 0.7. wpautop 20 skips 45 bytes to a one-byte match and tries every start position: about 15 ns per byte, against PCRE2's 0.9.
  - **Inherent** to the backtracker's design: PCRE2's JIT saves only the slots that change, and it auto-possessifies loops. Two of the four are fixable by rewriting (below).
- **Lazy quantifiers are not the cause.**
  - wpautop 8, `(<hr\s*?/?>)`, is 8.2×, and its greedy equal 8.4×. Its 61 calls find nothing in 917 bytes each. TRegex's DFA scans at about 1.4 ns per byte, PCRE2's JIT at 0.17.
  - wpautop 13 and 24 are the same kind (1.6×, 2.2×).
  - They are small (under 40 µs of an iteration), and the per-pattern choice leaves them on PCRE2.
- **The Java flavor instead of Python** measures the same within noise (exploratory: the tag alternations 1.5–2.2× with `MaxDFASize`, kses 46 2.8×). It refuses blocks 44 (possessive quantifiers) and needs `{` escaped.
- **Compilation thresholds:** `GenerateDFAImmediately` and `AlwaysEager` don't change the steady state (exploratory). `AlwaysEager` makes several small patterns 5–30× slower.

### What fixes it, measured

TRegex/PCRE2 per pattern (`bench/gate1/results/tregex-25.4-fixes.txt`):

| Pattern | default | `MaxDFASize=32000` | + rewrites | `execBoolean` |
|---|---:|---:|---:|---:|
| kses 46 | 15.9× | 2.15× | 2.03× | 0.44× |
| wpautop 21, 18 | 10.5×, 10.2× | 0.85×, 1.27× | 0.84×, 1.26× | 0.70×, 0.59× |
| wpautop 14, 17 | 13.3×, 14.1× | 1.04×, 1.47× | 1.04×, 1.41× | 0.61×, 0.54× |
| blocks 44 | 21.7× | 22.2× | **8.3×** | 21.3× |
| wpautop 20 | 16.1× | 16.1× | **1.02×** | 16.0× |
| wpautop 8 | 8.2× | 8.3× | 8.4× | 8.1× |
| TRegex alone: kses / wpautop / blocks | 4.97 / 7.35 / 21.7 | 0.73 / 3.00 / 22.5 | 0.76 / 1.39 / 8.4 | 0.33 / 2.66 / 22.0 |
| adaptive choice: kses / wpautop (of PCRE2 time) | 0.69 / 0.97 | 0.69 / 0.97 | 0.68 / 0.93 | 0.47 / 0.78 |
| peak memory | 1718 MB | 2037 MB | 1394 MB | 1767 MB |

The rewrites (`bench/gate1/tregex-rewrites.txt`) are equal patterns; the file gives each one's argument:
- **blocks 44:** `[^}]+` → `[^}]++` inside `(?:…)*+`. An atomic loop with nothing after it in the group keeps its first, greedy path, so nothing ever backtracks into `[^}]+`. The rewrite saves a frame copy per byte of the block attributes.
- **wpautop 20:** `(?<!<br />)\s*\n` → `\s*\n` as a DFA, plus a guard. A match that `<br />` precedes is dropped, and the search goes on one byte further. `\s*\n` can't match empty, so the first match accepted is the original's.
- **wpautop 8:** `\s*?` → `\s*` (no `\s` can start `/?>`). Equal, and no faster.

In the rewrites column, small patterns that weren't rewritten (wpautop 22, 25) measure 3–5×. That is the shared timing loop, which now carries the guard's branch.

The other call modes:
- **`two_step`** (`execBoolean`, then `exec` on a hit) is slower than `exec` alone: these patterns hit on 97–100% of the calls (exploratory).
- **`group0`** (the groups made non-capturing, as for `preg_replace` without group references and for `preg_split`) is mixed (`bench/gate1/results/tregex-25.4-group0.txt`, against `MaxDFASize` with groups):
  - `wp_kses_split` 1.90× → 1.40×;
  - blocks 44 19.7× → 10.6×: without its six groups the backtracker's frames are smaller, which supports the frame-copy cause above;
  - but wpautop 21 0.83× → 1.82× and 14 1.06× → 1.36×. Without a capture group TRegex can't switch to the eager DFA (`TRegexExecNode.java:232-234`), so the backward DFA has to find the start.

### Native image

The native launcher `target/native/php-tregex` (`bench/native/build.sh` at `369e0dc`, Oracle GraalVM 25.4: 92 MB, built in 2 min 55 s, peak RSS 5.9 GB). Two interleaved rounds of 50 passes against the JVM build (native replays are slow because PCRE2 through FFM is). The table gives TRegex's time over **PCRE2's time on the JVM**, per pattern and per kernel (all covered patterns), from `bench/gate1/results/tregex-25.4-native.txt`:

| | TRegex JVM | TRegex native | native, `MaxDFASize=32000` | PCRE2 native |
|---|---:|---:|---:|---:|
| kses 46 | 11.9× | 5.1× | **0.94×** | |
| wpautop 21, 18, 14, 17 | 10.4–13.9× | 4.3–6.5× | **0.95–1.18×** | |
| blocks 44 | 16.4× | 18.3× | 15.9× | |
| wpautop 20 | 15.9× | 13.8× | 14.1× | |
| classes and literals (kses 45–53, slugs 4) | 0.21–0.60× | 0.31–0.96× | 0.28–0.85× | |
| kses, all covered | 4.62× | 2.22× | **0.65×** | about 90× |
| wpautop | 7.22× | 4.31× | 2.77× | about 47× |
| blocks | 16.4× | 18.3× | 15.9× | about 50× |
| slugs | 0.37× | 0.34× | **0.33×** | about 110× |
| escape | 0.64× | 0.72× | **0.56×** | about 28× |
| wptexturize (34% of its PCRE2 time covered) | 1.06× | 1.04× | 1.01× | about 90× |
| peak memory | 1506 MB | 517 MB | 525 MB | |

- **TRegex runs at about its JVM speed in a native image**, since its code is partially evaluated at run time there too. The small DFA patterns are up to 2× slower (kses 45: 0.21× → 0.41×), and the NFA-simulated ones faster (kses 46: 11.9× → 5.1×). PCRE2 through FFM is 28–110× slower in this image than on the JVM.
- **With `MaxDFASize`, native TRegex is at or below PCRE2-on-the-JVM speed** on kses and slugs and escape, about 1× on the tag alternations, and still behind on the backtracked patterns.
- An exploratory single round with the rewrites (6–13) put blocks 44 at 5.7× and wpautop 20 at 0.63×, for wpautop 1.21× and blocks 5.7× per kernel.

So **TRegex is a native-image route of its own** for every pattern it takes. What it can't take (11 patterns: conditionals, variable-width lookbehind, recursion, `\g`; two thirds of wptexturize's regex time) still needs PCRE2, whose FFM path in native image is the `native-regex` branch's work.

### D12, updated

- **On the JVM, the fixes help TRegex alone much more than they help the per-pattern choice.** The fixed patterns come close to PCRE2 (0.85–2.2×), but only wpautop 21 beats it.
  - The adaptive choice stays at kses 0.68–0.69, slugs 0.30, escape 0.57; wpautop goes from 0.97 to 0.93.
  - Of the kernels' 5.6 ms of PCRE2 time per iteration, the choice saves about 1.06 ms (19%) as it is, and about 1.16 ms (21%) with `MaxDFASize` and the rewrites.
  - **More patterns can move to TRegex only where preg needs no groups.** With `execBoolean`, the adaptive choice would be wpautop 0.78 and kses 0.47 (about 0.95 ms more), but the kernels' wpautop and kses calls need their groups (replacement templates, callbacks). It applies to `preg_match` without `$matches`, which the recording doesn't single out.
- **D12 stands, with three additions to the product version's scope:**
  - compile TRegex regexes with `MaxDFASize=32000`, the maximum (`Short.MAX_VALUE`). Peak memory was 2037 MB against 1718 MB in that A/B, but the default measured 2019 MB in the version A/B: with no heap limit, the spread between A/Bs is as large as the difference;
  - `execBoolean` for `preg_match` without `$matches`;
  - the translator's equal rewrites as a later, optional step: blocks' possessive class, and a negative literal lookbehind at the start as a guard.
- **For native image, TRegex is the better engine for every pattern it takes,** until the FFM path is fixed: kses' covered regex time 0.65× of PCRE2-on-the-JVM speed, against about 90× through native PCRE2.
- **Worth reporting upstream** (not filed):
  1. The lazy DFA is all or nothing at 1300 transitions. A backward DFA over the cap (wpautop's reversed tag alternation, 2026 transitions, while the forward one fits) leaves the regex on the NFA simulation for good, 10–14× slower than with the DFA. A per-executor fallback, or a higher default for byte encodings, would fix it.
  2. Without a capture group a regex can't use the eager DFA (`TRegexExecNode.java:232-234`): making wpautop 21's groups non-capturing made it 2.2× slower.
  3. The backtracker copies the whole frame at every choice point and doesn't auto-possessify; `[^}]+` → `[^}]++` alone was 2.6× on blocks 44. Its merge-exploded code for a 29-state NFA is 288 KB and takes 4.1 s to compile.
  4. A negative *literal* lookbehind forces the backtracker, although it can be checked like the positive literal ones: as a guard, wpautop 20 went from 16× to 1.0×.
  5. The eager capture-group cost cap (`TRegexMaxEagerCGDFACost`) is not an option.
