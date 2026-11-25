# Milestone B1 Consultation & 3-Day Pre-Check Plan (2026-11-24)

Part of the [wiki](README.md). Follows from Gate A0's NO-GO verdict in [aot-plan.md](aot-plan.md) and the capped
HotSpot + Graal JIT follow-up experiment on `bench/aot-spike/`.

**Status (2026-11-25):** Probes A, A2, Option 1 (`bootstrap-freeze.md`), and **Probe B (Rev 3)** are all done.
See §3 (Probe B Result) and §5 (Final Verdict).

## 1. Context: Capped HotSpot + Graal JIT Follow-Up

After Gate A0 showed Oracle GraalVM Native Image + PGO (`-march=native -O3`, Serial & G1) losing to PHP 8.5.13
tracing JIT across all three kernels (`0.62×–0.84×`), we measured capping **HotSpot + Graal JIT**
(`-XX:+UseJVMCICompiler`, no Truffle) on the same Gate A0 spike (CPUs 14–21, Windows `PeakWorkingSet64` vs. Linux
Docker cgroup `memory.peak` for PHP 8.5.13):

| Config | `hooks` (vs PHP 7.12 ms) | `hooks` Peak WS | `post` (vs PHP 7,588 ns) | `post` Peak WS |
|---|---:|---:|---:|---:|
| Graal `-Xmx512m` G1 (uncapped) | `5.31 ms` (1.34×) | `590.5 MiB` | `8,004 ns` (0.95×) | `409.8 MiB` |
| Graal `-Xmx64m` ParallelGC + caps\* | **`5.44 ms` (1.31×)** | **`117.4 MiB`** (−80%) | **`7,736 ns` (0.98×)** | **`107.4 MiB`** (−74%) |
| Graal `-Xmx64m` G1 + caps\* | `5.38 ms` (1.32×) | `136.5 MiB` (−77%) | `7,950 ns` (0.95×) | `128.6 MiB` (−69%) |
| Graal `-Xmx32m` SerialGC + caps | `5.82 ms` (1.22×) | `114.1 MiB` | `7,572 ns` (1.00×) | `98.5 MiB` |
| C2 `-Xmx64m` SerialGC + caps | `5.79 ms` (1.23×) | `82.8 MiB` | `9,662 ns` (0.79×) | `61.0 MiB` |
| *Native Image PGO (`G1`, `-march=native -O3`)* | *`8.80 ms` (0.81×)* | *`158.8 MiB`* | *`9,565 ns` (0.79×)* | *`92.2 MiB`* |
| *PHP 8.5.13 Tracing JIT (Docker Linux)* | *`7.12 ms` (1.00×)* | *`17.9 MiB` (cgroup)* | *`7,588 ns` (1.00×)* | *`16.8 MiB` (cgroup)* |

*\*Caps: `-XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=48m -Djdk.graal.internal.MaxHeapSize=64m`.*

This motivated exploring a revised goal: **~1.0× PHP 8.5 JIT performance parity + lower memory than `php-fpm` at
≥ 25–30 concurrent requests + runtime plugin loading (`ClassLoader`) + JVM ecosystem** via **`.php` → `.class`
(`java.lang.classfile` + `invokedynamic`) on capped HotSpot + Graal JIT** (Milestone B1).

---

## 2. Three-Model Independent Consultation (`2026-11-24`)

We consulted four independent read-only sessions across three frontier model families via `hc`:
- **Claude Opus 5.5 (#1 & #2)** (`claude --model opus --effort high`)
- **Fable 5.2** (`claude-private --model fable --effort high`)
- **Codex Astra 6.2** (`codex --no-daemon -m gpt-6.2-astra`)

### Unanimous Verdict: **`GO_PRECHECK`** (3-day widened pre-check — do *not* jump straight to B1)

All four runs independently rejected both starting B1 immediately and running only a narrow 1-day `Hooks.java`
conversion, and flagged **six concrete traps**:

1. **The `D17` "no destructible class loaded" `SwitchPoint` is dead on arrival:**
   `wp-settings.php:287` unconditionally loads `class-wp-html-token.php` (`__destruct`), plus PHPMailer, SimplePie,
   Requests Curl transport, and WooCommerce's log handler. Under [D17](decisions.md), container counts cannot be
   reconstructed lazily ([destructors.md](destructors.md#L62)), so production always runs with full container
   counting. *(Astra also noted `gc_collect_cycles()` currently returns `0` unconditionally in
   `RuntimeFunctions.java:212`.)*
2. **Global `SwitchPoint` on per-request PHP state causes deopt storms:**
   `WP_ADMIN`, `DOING_AJAX`, `REST_REQUEST`, pluggable functions, and conditional `is_admin()` declarations differ
   across concurrent requests. A JVM-wide `SwitchPoint` ping-pongs and invalidates globally. Use `SwitchPoint` only
   for monotonic engine-wide facts (e.g., file version); pass an **explicit `PhpRequestContext` as parameter 0** (not
   `ThreadLocal` or `ScopedValue`, which add lookup overhead and break/slow platform-thread fibers).
3. **HotSpot's 8,000-byte `DontCompileHugeMethods` cliff (not just JVM's 64 KB limit):**
   HotSpot silently skips JIT compilation for methods over 8,000 bytecode bytes ([D14](decisions.md#L177)).
   `WP_Query::get_posts` (1,762 lines) with inline `D17` releases and exception handlers will exceed 8 KB, requiring
   automatic method outlining from day 1, and side-blob / `ConstantDynamic` loading for large array literals
   (`states.php`, Jetpack classmap) instead of `<clinit>` bytecode.
4. **Shared helpers vs. code volume (the `D2` / `ceiling` tradeoff):**
   HotSpot lacks Truffle call-site splitting. Shared helpers (`PhpArray.get`, `Ops.add`) become megamorphic across
   thousands of sites; per-site `invokedynamic` guard chains retain 100–200k `CallSite`/LambdaForm graphs (~30–150 MB
   permanent heap + code cache) and re-trigger `ceiling`'s code-volume wall. The runtime half (49.8% of a render on
   `ceiling`) also remains untouched unless hot built-ins improve.
5. **Memory scaling & FPM baseline caveats:**
   - The `117 MiB` figure is 3 kernels on Windows `PeakWorkingSet64` (not comparable to Linux cgroup `memory.peak`).
     At 25–30 in-flight requests (~2.6 MiB live each per [gc.md](gc.md#L209-L217)), request data alone needs 65–78 MiB
     before shared heap or GC headroom, so `-Xmx64m` and `ReservedCodeCacheSize=48m` will thrash/overflow at site
     scale.
   - `php-fpm 16` (`405 MiB` cgroup peak, [woocommerce.md](woocommerce.md#L146)) already served **32 concurrent
     buyers** at 7.56 orders/s—30 clients ≠ 30 FPM workers, and MariaDB InnoDB row locks saturate at 16 workers.
6. **Dynamic PHP class layouts, `extract()` templates, and plugin reload leaks:**
   Conditional `class A extends B` means a PHP class cannot map to a single fixed JVM class when parent chains vary
   per request; `load_template()` calls `extract()` before `require`, forcing map-backed locals on template top
   levels; plugin reload leaks whole `ClassLoader` generations unless files load via `Lookup.defineHiddenClass` with
   bounded/weak call-site caches.

**Governance note (all 4):** Switching from *"beat PHP 8.5 via JIT speed"* to *"~1.0× parity + memory"* supersedes
[D1](decisions.md) and reverses [D2](decisions.md); an explicit owner decision (**D19**) is required before funding
B1.

---

## 3. The 3-Day Widened Pre-Check Plan (Zero Compiler Code)

All measurements must run in **Linux Docker on pinned CPUs 14–21** with **cgroup `memory.peak`** and **NMT**
(`-XX:NativeMemoryTracking=summary`).

### Probe A — Full-Site Census + Synthetic 2,500-Class Scale & Reload Probe (1–1.5 days)
1. **Census (`bench/b1-precheck/probe-a/`):**
   Run this repo's `parser/zend` (`D14`) over the full WordPress + WooCommerce + plugins tree
   (`target/bootshare/wordpress`, or `target/gate2/wordpress` if bootshare is absent) and report:
   - Total `.php` files, LOC, functions/methods/closures, top-level scripts, call sites, `defined()` sites, and large
     array literals (`states.php`, classmaps).
   - Distribution of AST node counts and calibrated estimated JVM bytecode sizes per function—specifically how many
     functions exceed HotSpot's **8,000-byte** `DontCompileHugeMethods` cliff and the **65,535-byte** JVM limit.
2. **Synthetic Scale & Reload Generator (`java.lang.classfile`):**
   Generate **~2,500 `.class` files** (~40–50k methods, ~100–200k call sites, plus representative >8 KB methods and
   large literal arrays) matching the census shape. Compare two linkage strategies side by side under capped HotSpot +
   Graal JIT (with C2 as a control):
   - **Variant 1 (`indy`):** `invokedynamic` + `MutableCallSite` / per-request context guard.
   - **Variant 2 (`shared`):** direct calls through shared runtime helpers + explicit `PhpRequestContext` dispatch.
   Run:
   - **Narrow vs. wide workload:** touch ~5% hot vs. ~8,000 warm-but-not-hot methods per simulated request with
     alternating route constants (`WP_ADMIN`, `DOING_AJAX`, `REST_REQUEST`).
   - **Plugin reload stress:** replace a plugin generation **10 times** during active traffic using
     `Lookup.defineHiddenClass` / isolated loader, drain old requests, force a class-unloading GC, and count surviving
     retired loaders.
3. **Metrics:** Linux cgroup `memory.peak`, process RSS, live post-GC heap, committed Metaspace, code cache usage,
   compilation failures/bailouts, time to plateau, wide-vs-narrow slowdown ratio, and surviving retired loaders.

**Probe A Kill Criteria (STOP before B1 if any holds):**
- Warmed fixed base RSS at zero active requests exceeds **350–400 MiB** (or fails to stay well below `php-fpm`'s
  crossover budget).
- Code cache overflows / disables JIT compilation under practical server caps.
- Wide workload is **> 1.5× slower** than narrow on the same hot operations.
- More than **~5%** of executed WooCommerce-route functions exceed 8,000 estimated bytecode bytes without a clean
  outlining strategy.
- Any retired plugin loader survives after requests drain and a diagnostic class-unloading GC runs.
- Steady-state deoptimization / invalidation storm under alternating route constants.

### Probe A Result (2026-11-24)

Full evidence and tables: [bench/b1-precheck/probe-a/README.md](../bench/b1-precheck/probe-a/README.md). Linux Docker
on CPUs 14–21 ran 8 runs (Graal and C2, capped and tight, `indy` and `shared`) with 600 warm-up batches. A 60-batch
first pass ended while the JIT was still compiling, so its timings are not used.

- **`indy` linkage fails the memory criterion under Graal.** Graal capped `indy` reaches **455 MiB** warmed RSS at
  zero active requests, against the 350–400 MiB stop line. `shared` (direct calls into shared helpers with an
  explicit `PhpRequestContext`) stays at 268–321 MiB under Graal and 208–246 MiB under C2.
- `indy` also uses 2.2–2.8× the code cache, takes 10–12 ms per steady-state request against 3–4 ms, and
  needs 2.3–3× the warm-up. Its hot calls reach `shared` speed only after about 75 s.
- **Everything else passes:** no code-cache overflow (peak 79 of 96 MiB), same-JVM wide/narrow 0.83–1.05×, 0
  steady-state deoptimization traps or relinks, and 0 of 10 retired loaders surviving in every run.
- The census puts 11 of 23,836 WooCommerce callables (0.046%) over 8,000 estimated bytes. The executed-route share
  needs a trace and is still open.
- **Limit:** the corpus only does integer arithmetic. The heap never held more than 34 MiB, and the corpus has 45k
  methods against the census's 61k. These figures are the memory floor for loaded code alone. The PHP runtime,
  bootstrap state and request data come on top, so passing the line does not clear B1 on memory.

**Reading:** Probe A passes only with `shared` linkage. Probe B and any B1 compiler should assume `shared`
dispatch, not per-site `invokedynamic`. Even under Graal tight, code alone takes about 270 MiB before any PHP
data. That leaves roughly 80–130 MiB under the stop line for the runtime, a WordPress bootstrap and in-flight
requests (25–30 × ~2.6 MiB is 65–78 MiB by itself). Probe A2 below replaces the 2.6 MiB, which came from the site
without plugins.

### Probe A2 — The Data Probe A Leaves Out (2026-11-24)

Probe A's corpus does integer arithmetic only, so it measures loaded code and no PHP data. Probe A2 measures the
data on the real site. Truffle PHP's `HttpMain` serves the bootshare site with all five plugins active
(WooCommerce, Yoast SEO, Elementor, Contact Form 7, Akismet) on the plugins database. It is the only JVM runtime
that runs that site, and B1 would reuse its runtime library (`PhpArray`, byte strings, objects).
- **Warm-up:** 150 rounds of four logged-in pages: the home page, a post, the dashboard and the REST posts
  collection. All 600 requests return 200.
- **Snapshot:** a full GC, a class histogram and NMT (`tests/gate2/memsnap.sh`, `memsplit.py`).
- **Load:** 60 s each of 1, 4 and 8 clients. `-Dphp.heapprobe=250` records the live heap by requests in flight
  (`tests/gate2/heapprobe.py`).

Runner: `bench/b1-precheck/probe-a2/run.sh`. `pages.sh` lists what stops the pages. Evidence:
`bench/b1-precheck/probe-a2/build/20261124-212821-plugins-fallback-interpreter`.

**Caveat: the run had no Truffle compilation.** The `gate1-truffle` image, rebuilt after a Docker Desktop reset,
got GraalVM 25.0.4 from `bench/truffle/graalvm-linux.tar.gz`. Truffle 25.4.4.1.1 doesn't compile on an older JDK,
so it fell back to the interpreter, and `-Dpolyglot.engine.WarnInterpreterOnly=false` hid the warning. The log has
no Truffle compiler threads. The data figures don't depend on the compiler. The compiled-mode run was not repeated,
because compilation can only add memory (code cache, libgraal) and so cannot change the conclusion below.

**Before measuring, the site had to run.** Elementor and WooCommerce failed every request on Truffle PHP until four
gaps were fixed, each with a smoke test matching PHP 8.5:
- [issue 110](../issues/110-stored-callable-scope.md), PR #62: a private autoloader checked again from the
  triggering scope.
- [issue 111](../issues/111-get-called-class-namespace.md), PR #63: `get_called_class()` in a namespace.
- [issue 112](../issues/112-ob-get-status.md), PR #63: `ob_get_status()`.
- [issue 113](../issues/113-relative-date-formats.md), PR #63: relative dates such as "tomorrow 3am", plus
  timelib's wall-clock arithmetic.

| Measurement (five-plugin site) | Value |
|---|---:|
| Live heap of the idle server, after a full GC | 246 MB |
| PHP data in it (`PhpArray` and its hash) | ~1 MB |
| Code representation in it: sources (`byte[]` 89 MB), AST 47 MB, bytecode and parser 38 MB, Truffle 12 MB | ~190 MB |
| RssAnon of the idle server (heap live 246 + garbage 113, code 48, GC 57, class metadata 52, other 6) | 522 MB |
| Live heap per request in flight, median: 1 / 4 / 8 in flight | 15.0 / 13.2 / 12.8 MB |
| Live heap above idle, max: 1 / 4 / 8 in flight | 21 / 92 / 157 MB |
| PHP 8.5 on the same site, per request (`bench/bootshare`): state at `wp_loaded` / peak | 10.3–10.7 / 14–20 MB |

What it shows:
1. **The server holds no WordPress state between requests.** Each request builds its options, hooks and plugin
   objects in its own context and drops them. The idle heap is code: sources, ASTs, translators and bytecode
   kept after compilation, which is tens of MB worth reclaiming in today's Truffle PHP, B1 or not.
2. **A request's data is as large on the JVM as in PHP:** 13–15 MB live against PHP's 14–20 MB peak. Against the
   plugin-less site's 2.6 MB, the plugins multiply it by five.
3. **B1 projection at 25–30 requests in flight:** code 270–320 MiB (Probe A, `shared`, Graal) plus data 325–450
   MB live, **~600–800 MiB** before GC headroom. **`php-fpm` at the same concurrency is about the same.**
   [woocommerce.md](woocommerce.md) measured ~21 MB RssAnon per worker plus 151 MB of OPcache shmem once, so 25–30
   workers come to ~680–780 MB. (The 405 MiB `memory.peak` there is the 16-worker pool, which serves at most 16
   requests at once, so it isn't the comparison for 25–30.) The fixed-base stop line (350–400 MiB at *zero* active
   requests) is a different measure. `shared` under Graal passes it as a code-only floor.
4. **Truffle as the profiler with emitted `.class` files** (Truffle interprets, then the warmed state is emitted as
   bytecode for HotSpot) fails on memory too. The interpreter-only server, with no compiler, is already 522 MB
   RssAnon before any class is emitted, and it would keep both representations.

### Probe B — True Open-World Spike with Real `D17` (1–1.5 days, only if Probe A passes)
1. Upgrade `bench/aot-spike/` (`Hooks`, `WPPost`, plus `hot.php mega` with 40 hooks × 5 callbacks) to remove every
   closed-world shortcut:
   - Explicit `PhpRequestContext` parameter 0; two alternating/overlapping request contexts with different constants,
     pluggable functions, and `all`-hook registrations.
   - Real `D17` refcounting on every object, array, and closure store (no no-op `PhpObject.addRef/release`).
   - Real byte-string representation intended for B1.
   - Destructor correctness fixture: `WP_HTML_Processor` past 10,000 tokens (prompt bookmark release) + unreachable
     cycle with explicit `gc_collect_cycles()` + shutdown order vs. PHP 8.5.13.
2. Benchmark **interleaved** (≥ 5 rounds, same Linux container, CPUs 14–21, cgroup `memory.peak`) against PHP 8.5.13
   tracing JIT.

**Probe B Kill Criteria (STOP before B1 if any holds):**
- Open-world `hooks` **< 1.0–1.10×** PHP 8.5.13 (or > 20% regression vs. capped spike's 5.44 ms).
- Open-world `post` **< 0.85–0.90×** PHP 8.5.13.
- Open-world `mega` **< 0.90×** PHP 8.5.13.
- Two-context penalty **> 5%**, or any steady-state recompilation/invalidation storm.
- Any semantic, warning, or destructor-order mismatch vs. PHP 8.5.13.

### Probe B Result (2026-11-25)

Full evidence, Codex adversarial review history, and raw tables:
[bench/b1-precheck/probe-b/RESULTS.md](../bench/b1-precheck/probe-b/RESULTS.md).

Re-opened after Option 1 ([bootstrap-freeze.md](bootstrap-freeze.md)) removed Probe A2's per-request heap blocker
(`14.94 → 2.46 MiB/req`). Implemented as a standalone open-world `.class` runtime (`bench/b1-precheck/probe-b/`, zero
edits to `src/main/` or `pom.xml`) projecting `PhpArray` + `PhpRef` onto raw `byte[]` `ByteStr`, passing explicit
`PhpRequestContext` as parameter 0, using Probe A's `shared` vtable dispatch (`abstract class PhpCallable` /
`PhpInstanceMethod`, `0` `invokedynamic`) across **181 distinct JVM target classes** in `mega`, runtime `PhpClass`
property metadata on `WP_Post`, full `plugin.php` variadic + live `$wp_filter['all']` rechecks, and real `D17` exact
refcounting + shadow-rc Bacon-Rajan cycle collector + Zend iterative `rc == 1` shutdown. Hardened across two
adversarial Codex audits (`t5c`, `t5g`) and a Rev 3 Codex implementation pass (`t5h`).

- **Correctness & `D17` parity: PASS.** All semantic checks and Codex counterexamples pass; the 28-line `d17_fixture.php`
  transcript matches pinned `PHP 8.5.13` byte-for-byte (`12,000` `HtmlToken` `$on_destroy` bookmark callbacks at
  `peak=1`, `liveDestructibleCount=0`, `part1_gc=0`; 2-object cycle `2`; array cycle `4`; resurrection; Zend iterative
  aliased/mutated shutdown `G2 → G1 → G3 → A1 → A2`). All kernel checksums match 100% across all 5 rounds.
- **Interleaved Linux Docker benchmark (Rev 3, 5 rounds, `--cpuset-cpus 14-21`, pinned `php@sha256:dbf32b33...` verified
  `PHP 8.5.13 jit_on=1 kind=5` vs. capped HotSpot C2 & Graal `-Xmx64m -XX:+UseParallelGC -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=48m`):**

| Kernel | Metric (2nd half) | PHP 8.5.13 JIT | Probe B C2 (`-Xmx64m`) | Probe B Graal (`-Xmx64m`) | C2 vs PHP | Graal vs PHP |
|---|---|---:|---:|---:|---:|---:|
| `hooks` (400 rds) | p10 / med | `7.42` / `7.75 ms` | **`6.45` / `6.85 ms`** | `6.93` / `7.25 ms` | **1.15×** / 1.13× | 1.07× / 1.07× |
| `aget` (3000×250) | p10 / med | `2,403` / `2,534 ns` | **`2,284` / `2,330 ns`** | `2,391` / `2,494 ns` | **1.05×** / 1.09× | 1.01× / 1.02× |
| `post` (3000×250) | p10 / med | `8,347` / `9,151 ns` | **`7,684` / `9,209 ns`** | `10,213` / `10,753 ns` | **1.09×** / 0.99× | **0.82×** / 0.85× |
| `mega` (181 cls) | p10 / med | `16,901` / `18,624 ns` | `21,172` / `23,147 ns` | **`18,640` / `20,532 ns`** | **0.80×** / 0.80× | **0.91×** / 0.91× |
| **4-kernel geomean** | **p10 / med** | **1.00× / 1.00×** | **1.01× / 0.99×** | **0.95× / 0.96×** | — | — |
| Cgroup `memory.peak` | range across 4 | `16.6–18.1 MiB` | **`32.5–72.6 MiB`** | `60.9–90.1 MiB` | — | — |

- **Paired two-context penalty (`hooks-2ctx`, `mega-2ctx`, both `CtxA` & `CtxB` interleaved vs. solo controls):**
  - **C2:** `hooks` `CtxA` **`+4.77%` p10** (PASS) / **`+10.65%` med** (FAIL); `hooks` `CtxB` `+1.93%` p10; `mega` `CtxA` `-3.14%`; `mega` `CtxB` `-0.24%`.
  - **Graal:** `hooks` `CtxA` `+4.24%` p10 / **`+12.95%` med**; **`mega` `CtxA` `+8.95%` p10 / `+8.70%` med** (FAIL).
- **Steady-state deoptimization & NMT (`[STEADY_START_MS, STEADY_END_MS]`):** `0` uncommon traps on `hooks-2ctx` (both C2 & Graal) and `1` (C2) / `2` (Graal) on `mega-2ctx` — **no deopt storm**. NMT committed: `106.0–115.4 MiB` (C2, Code `3.1–4.5 MiB`) vs. `95.5–113.2 MiB` (Graal, Code `3.8–5.2 MiB`).

**Reading:**
1. **Strict kill-criteria verdict: NO-GO on unmodified thresholds — neither C2 nor Graal passes every gate simultaneously.**
   - **HotSpot C2** beats Graal on 3 of 4 kernels (`hooks` `1.15×`, `aget` `1.05×`, `post` `1.09×`), achieves **`1.01×` 4-kernel geomean parity**, uses **20–45% less cgroup peak RAM**, and passes p10 two-context (`+4.77%`), **but FAILS `mega` (`0.80×` vs. `≥ 0.90×`)** and `hooks` `CtxA` median (`+10.65%`). Switching `mega` from `invokeinterface` (`0.78×`) to vtable `invokevirtual` (`0.80×`) recovered only 2%, showing the 20% gap is open-world `HashMap` lookup + 160 `PhpArray` slice allocations/op + `D17` container bookkeeping rather than `itable` dispatch.
   - **HotSpot + Graal** clears `mega` (`0.91×`), **but FAILS `post` (`0.82×`), `hooks` regression (`6.93 ms`, `+27.4%` vs. `5.44 ms`), and `mega` paired 2-context penalty (`+8.95%`)**, while consuming more RAM.
2. **Owner decision (`D19`) for B1 (C2) + Option 1:** Plain HotSpot C2 + Option 1 still projects to beat pure PHP 8.5 end-to-end on 5-plugin WordPress/WooCommerce (~`1.4–1.6×` faster via Option 1's `1.63×` bootstrap skip at `~1.0×` raw PHP geomean, and ~`400–550 MiB` vs. `~0.9–1.25 GiB` at 30 concurrent reqs). Proceeding to fund a B1 compiler requires either (a) an explicit **D19** waiver accepting `~0.80×` on megamorphic hooks (relying on Option 1 for the net win over PHP 8.5), or (b) closing `mega` to `≥ 0.90×` on C2 first (e.g., direct resolved-callable slot caching on `WP_Hook` entries + slice fast path).

---

## 4. Refined B1 Plan & Kill Criteria (Only If Probe A + Probe B Pass and Owner Records `D19`)

- **Hard 2-week timebox.**
- **Runtime by projection, not refactor:** Extend `bench/aot-spike/compile.ps1` (49/57 built-in files and 29/35
  runtime files import Truffle—do not refactor `src/main` in week 1). Reuse `parser/zend` (`D14`) and `ffi/` (already
  Truffle-free).
- **Compiler rules from day 1:** Explicit `PhpRequestContext` param 0, per-request declared-bit checks (never global
  `SwitchPoint` for per-request state), automatic method outlining at 8,000 bytes, side-blob literals for giant
  arrays (`states.php`, classmaps), full `D17` counting, `Lookup.defineHiddenClass` per file.
- **Go Criteria (Linux Docker, CPUs 14–21, ≥ 5 interleaved rounds vs. PHP 8.5.13 JIT):**
  - 100% checksum parity on `K0`, `K1–K7`, `hot.php` (11 kernels), and `D17` destructor `.phpt` subset.
  - Geomean **≥ 1.10× PHP 8.5** in single-process round-robin multi-request mode (no kernel < 0.80–0.85×; round-robin
    ≤ 10% slower than solo; two-context penalty ≤ 5%).
  - `large-function.php` compiles and installs at top tier with full `D17`; zero methods > 8,000 bytes;
    `apply_filters` + `WP_Hook::apply_filters` ≤ **60–100 KB** machine code (vs. Truffle's ~300 KB).
  - Mixed-suite peak RSS **≤ 200 MiB** and projected 2.5M-LOC base **≤ 350–450 MiB**.
- **Immediate post-B1 gate:** `K8` full front-page render (**≤ 10–11.2 ms**, **≤ 400 MiB**), followed by served
  WooCommerce at 16 / 25 / 30 clients against the best `php-fpm` pool meeting the same throughput/p99.

If the pre-check fails, pivot to [alternatives.md](alternatives.md) §1 (post-bootstrap CoW `fork()` on `php-src`),
§3 (surgical C/Rust WordPress subsystems on `php-src`), or §5 (fragment caching)—or, if staying on JVM, freezing an
immutable post-bootstrap object graph across request threads to attack the 40% bootstrap share.

---

## 5. Pre-Check Verdict (2026-11-24): no memory win to be had

**Recommendation: don't fund B1 and skip Probe B.** Recording D19 or closing B1 is the owner's call.

B1's revised goal was ~1.0× PHP speed plus *less* memory than `php-fpm` at 25–30 concurrent requests. Probes A and
A2 settle the memory half without any compiler code. At equal concurrency the projection is a tie, not a win:
- **Fixed base: B1 passes the stop line, PHP is still smaller.** Under Graal, the census-shaped corpus with `shared`
  linkage holds 268–321 MiB warmed RSS at zero requests, under the 350–400 MiB line. It is a code-only floor, and
  455 MiB with per-site `invokedynamic`. PHP's fixed cost is the 151 MB OPcache, shared by every worker.
- **Data per request: about the same.** A request on the five-plugin site keeps 13–15 MB live on the JVM. A
  `php-fpm` worker holds ~21 MB RssAnon (whole process). PHP's own request peak is 14–20 MB. The JVM also needs GC
  headroom on top of the live figure (Probe A2's idle server carried 113 MB of garbage beside 246 MB live).
- **Sum at 25–30 requests in flight: ~600–800 MiB for B1 against ~680–780 MB for `php-fpm`** (21 MB × workers +
  151 MB). The ranges overlap, and the B1 side doesn't count GC headroom yet. Measured, at best a tie, so the
  memory win that was to pay for parity speed isn't there.

This comparison is a projection from separate measurements. A direct check would run `php-fpm` with 25–30 workers
and B1 on the same load, and B1 doesn't exist. No result short of a clear win would change the verdict, and the
components give no sign of one.

The speed half was already parity at best: 0.95–1.34× PHP 8.5 on hand-translated, closed-world kernels with no D17
([aot-plan.md](aot-plan.md)). Probe B would only refine that number, which can't rescue the goal once memory fails.

**What the data does point to.** Per request, WordPress rebuilds ~10 MB of bootstrap state (PHP's `wp_loaded`
live, `bench/bootshare`). That is the larger part of each request's data, and every concurrent request repeats it.
Sharing it is the one lever on the data side. On the JVM it means freezing an immutable post-bootstrap graph
across request threads. On `php-src` it means post-bootstrap CoW `fork()` ([alternatives.md](alternatives.md) §1).
Separately, today's Truffle PHP keeps sources, ASTs and translators after compiling them, tens of MB per server to
reclaim whatever happens to B1.

**Update (2026-11-25 — Option 1 + Probe B complete):** Option 1 ([bootstrap-freeze.md](bootstrap-freeze.md)) removed
Probe A2's per-request heap blocker (`14.94 → 2.46 MiB/req`, `-83.5%`, `1.63×` `home` speedup, `0` `D17` errors).
**Probe B (Rev 3)** then tested the open-world `.class` + `D17` speed gate on Linux Docker (`bench/b1-precheck/probe-b/RESULTS.md`):
- **Plain HotSpot C2 (`-Xmx64m`)** beat Graal JIT on 3/4 kernels and RAM (`32.5–72.6 MiB`), hitting **`1.01×` 4-kernel
  geomean parity** (`hooks` `1.15×`, `aget` `1.05×`, `post` `1.09×`, `mega` `0.80×`), exact byte-for-byte `D17` parity,
  and `0–1` steady-state traps—**failing strictly on `mega` (`0.80×` < `0.90×`) and `hooks` `CtxA` median (`+10.65%`)**.
- **HotSpot + Graal (`-Xmx64m`)** hit `0.95×` geomean (`mega` `0.91×`, `post` `0.82×` FAIL, `hooks` `+27.4%` vs `5.44 ms`
  FAIL, `mega` 2ctx `+8.95%` FAIL).
- **Bottom line:** Under strict Probe B thresholds, **neither JIT passes all gates simultaneously (strict NO-GO)**.
  Funding **B1 (plain OpenJDK C2) + Option 1** requires either an explicit owner **D19** waiver accepting `0.80×` on
  megamorphic hooks (letting Option 1's `1.63×` bootstrap skip + `-83.5%` heap supply the end-to-end win over PHP 8.5,
  paired with `get_option()`-level invalidation + async rebuilds) or first optimizing `mega` dispatch/slicing on C2 past `0.90×`.
