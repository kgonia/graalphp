# Gate 1: does Graal beat PHP 8.4 JIT on real WordPress code?

Started 2026-10-22. Decisions D4–D8 in [decisions.md](decisions.md) apply.

## Question
Does a Truffle PHP, run on GraalVM, execute WordPress's own hot PHP functions clearly faster than PHP 8.4 with OPcache's tracing JIT, once warm? Does it get warm fast enough for a server?

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
- **Correctness:** checksums must equal PHP 8.4's on every iteration. A mismatch fails the kernel, whatever its speed.
- **Reference:** `php:8.4-cli` + the `ast` extension, with `opcache.enable_cli=1`, `opcache.jit=tracing` and `opcache.jit_buffer_size=128M`. Also measured with JIT off.
- **Truffle PHP:** Oracle GraalVM 25.0.4.1.1 (JVM mode first; the native standalone later).
- **Runs:**
  - Linux Docker containers on the same cpuset, interleaved rounds (PHP, Truffle, PHP, Truffle…), 3 rounds.
  - Log the governor and power mode.
  - Publishable numbers come from a quiet Linux host with pinned cores.
- **Reported per kernel:**
  - steady state: the median of the last 50% of iterations, std within a run and between rounds;
  - the **warmup curve**: time per iteration vs iteration, plus time to reach 90% of peak;
  - CPU time including Graal's compiler threads.

## Go / no-go
- **Go** if the geometric mean of the steady-state speedup over K1–K7 against PHP 8.4 tracing JIT is **≥ 2×** (the owner may adjust the target), checksums are identical, and every kernel reaches 90% of its peak within seconds, not minutes.
- **Rethink** if the speedup is under about 1.3×, or if it only shows after minutes of warmup.
- **In between:** analyse where the time goes (e.g. regex-bound kernels cap the gain, since D7 binds the same PCRE2) before deciding.

## Milestones
1. **M1, reference and harness:** the PHP reference image, the php-ast JSON exporter, the WordPress fetch at a pinned commit, the corpus, and the driver producing PHP 8.4 baselines and checksums.
2. **M2, Truffle skeleton:** the Maven project, `PhpLanguage`, a launcher, the zend_ast JSON loader, functions, ints, floats, strings, echo; K0 runs.
3. **M3, value model:**
   - byte strings (`TruffleString`);
   - `PhpArray`: an ordered hash with packed strategies, copy-on-write and an internal pointer;
   - references, closures, classes (properties, methods, static, `$this`, `self`/`static`/`parent`, interfaces), static variables, globals, include.
4. **M4, built-ins:** the set the kernels reach (from a static scan), plus `preg_*` via PCRE2 FFM.
5. **M5:** all kernels match the checksums. Measure and write the Gate 1 report.

## Status log
- **2026-10-22:** spec written. Pinned WordPress commit: `d86e2079edbf869a0393ee99a91da19351420461` (WordPress/WordPress trunk, 2026-10-22).
