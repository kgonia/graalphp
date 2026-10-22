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

