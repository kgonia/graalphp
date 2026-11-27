# Milestone B1 — Week 2 Days 4–5 Benchmark & Pinning Results (2026-11-27)

Official **5-round interleaved Linux Docker** benchmark of real PHP compiled through the Milestone B1 `.php → .class` compiler (`81891667`) on stock **OpenJDK 25 C2** and **OpenJDK 28-ea C2** against pinned **PHP 8.5.13 OPcache tracing JIT**, plus the empirical **JDK 25 vs. JDK 28-ea virtual-thread FFM native-pinning** verification.

- Plan & post-Week-2 roadmap: [`wiki/b1-plan.md`](../../wiki/b1-plan.md)
- Hand-written C2 control (`1.11×` 4-kernel geomean): [`wiki/b1-precheck.md`](../../wiki/b1-precheck.md) (Probe B Rev 4b)
- Harness: [`run-bench.ps1`](run-bench.ps1), [`summarize.py`](summarize.py), [`B1BenchMain.java`](../../src/main/java/pl/codeloom/php/b1/B1BenchMain.java)

---

## 1. Environment & Pinned Containers

All timing runs executed inside Linux Docker pinned to `--cpuset-cpus=14-21` with **5 interleaved rounds** (`php`, `c2-25`, `c2-28` per round). Statistics report the **median over 5 rounds** of each run's **second-half p10 and median**; speed-up is `PHP time / B1 time` (`> 1.00×` = B1 faster than PHP 8.5.13 JIT).

| Engine | Version / Build | Pinned Docker Digest | Flags |
|---|---|---|---|
| **PHP 8.5.13 JIT** | `PHP 8.5.13` (`jit_on=1 kind=5`) | `php@sha256:dbf32b33595283b79fd1a37ebc1614bdf1b83d3750daad9a57d67dbb9b96ec16` | `-d opcache.enable_cli=1 -d opcache.jit=tracing -d opcache.jit_buffer_size=64M` |
| **B1 OpenJDK 25 C2** | `Temurin-25.0.5.1+2-LTS` | `eclipse-temurin@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3` | `-XX:+UnlockExperimentalVMOptions -XX:-UseJVMCICompiler -Xmx64m -XX:+UseParallelGC -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=48m` |
| **B1 OpenJDK 28-ea C2** | `OpenJDK 28-ea+25-2095` | `openjdk@sha256:96bd7dac4b8eece1050077de4e9622169552439a48cd9f335b1a8f02ca43435d` | `-Xmx64m -XX:+UseParallelGC -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=48m` *(stock 28-ea rejects `-XX:-UseJVMCICompiler` and uses C2 by default)* |

- **Zero Truffle classes loaded:** `classload-c2-25.log` (`2,267` classes, `0` Truffle), `classload-c2-28.log` (`2,353` classes, `0` Truffle).
- **Zero `B1Lint` violations (`B1-01..B1-13`):** `38` runtime classes (`0` violations) and `445` emitted hidden classes (`max_code = 4,734 B ≤ 7,500 B`, `0` violations).
- **100% checksum & `D17` parity:** All 17 kernels + `CtxA`/`CtxB` + 2-context interleaved runs produced identical checksums across PHP 8.5.13 JIT, OpenJDK 25, and OpenJDK 28-ea across all 5 rounds (`check-c2-25.txt` & `check-c2-28.txt`: `CHECK OK`).

---

## 2. Week 2 Optimization Progression (`0.08× → 0.54× / 0.56×`)

| Stage | Commit | Highlights | 4-Kernel Geomean (JDK 25 / 28-ea) | `hooks` | `aget` | `post` | `mega` | `k0-floatloop` | `k0-mandelbrot` |
|---|---|---|---|---:|---:|---:|---:|---:|---:|
| **Baseline** (1-round `out-smoke`) | `fedc1730` | First real-PHP harness, frame-per-call, `LinkedHashMap` `B1Array` | `0.08× / 0.08×` | `0.11×` | `0.03×` | `0.09×` | `0.12×` | — | — |
| **Pass 1** (1-round `out-opt-fix`) | `b483f7d1` | Fast JVM-local `run()`, open-addressing `B1Array`, flat `B1Object`, direct builtins, O(1) GC root buffer | `0.39× / 0.38×` | `0.38×` | `0.40×` | `0.41×` | `0.37×` | `0.27×`* | `0.05×`* |
| **Pass 2** (1-round `out-p2-smoke`) | `68fdcb21` | Pooled `B1Frame`, lazy-indexed `B1Array`, fast scalar-default `B1Object`, direct `call_user_func_array`/`array_slice` | `0.48× / 0.48×` | `0.40×` | `0.57×` | `0.65×` | `0.48×` | `0.48×` | `0.04×` |
| **Pass 3 Final (5-round `out-final`)** | `81891667` | Zero-frame `runLeaf`, natural CFG fallthrough/merged ranges, lazy `LineStores`, scalar retain elision, unboxed `double`s | **`0.54× / 0.56×`** | **`0.48×`** | **`0.44×`** | **`0.81×`** | **`0.58×`** | **`1.27×`** | **`0.87×`** |
| *Hand-written C2 Control* | *Probe B Rev 4b* | Hand-written `.class` + `D17` | *`1.11×`* | *`1.40×`* | *`1.00×`* | *`1.07×`* | *`1.02×`* | — | — |

*\*Pass 1 `k0` figures from `out-opt-smoke` prior to minor Neon audit follow-ups.*

---

## 3. Official 5-Round Results (`out-final/summary.md`, All 17 Kernels)

### 3.1 Core 4 Probe B Parity Kernels

| Kernel | Metric | PHP 8.5.13 JIT | B1 JDK 25 C2 | B1 JDK 28-ea C2 | JDK 25 vs PHP | JDK 28-ea vs PHP | Probe B Control |
|---|---|---:|---:|---:|---:|---:|---:|
| `hooks` | p10 | `7.63 ms` | `16.28 ms` | `15.98 ms` | **0.47×** | **0.48×** | 1.40× |
| `hooks` | median | `7.91 ms` | `17.42 ms` | `16.96 ms` | 0.45× | 0.47× | — |
| `hooks` | `memory.peak` | `17.9 MiB` | `103.7 MiB` | `106.5 MiB` | — | — | 66.6 MiB |
| `aget` | p10 | `2,433 ns` | `5,782 ns` | `5,556 ns` | **0.42×** | **0.44×** | 1.00× |
| `aget` | median | `2,606 ns` | `6,333 ns` | `6,090 ns` | 0.41× | 0.43× | — |
| `aget` | `memory.peak` | `16.6 MiB` | `95.1 MiB` | `97.2 MiB` | — | — | 35.8 MiB |
| `post` | p10 | `8,080 ns` | `10,616 ns` | `9,988 ns` | **0.76×** | **0.81×** | 1.07× |
| `post` | median | `9,710 ns` | `11,246 ns` | `11,349 ns` | 0.86× | 0.86× | — |
| `post` | `memory.peak` | `16.9 MiB` | `92.0 MiB` | `91.2 MiB` | — | — | 40.9 MiB |
| `mega` *(181 `eval()` classes)* | p10 | `17,775 ns` | `30,585 ns` | `31,222 ns` | **0.58×** | **0.57×** | 1.02× |
| `mega` | median | `19,554 ns` | `36,152 ns` | `35,371 ns` | 0.54× | 0.55× | 1.06× |
| `mega` | `memory.peak` | `16.8 MiB` | `96.7 MiB` | `96.7 MiB` | — | — | 51.3 MiB |
| **4-Kernel Geomean (p10)** | — | **1.00×** | — | — | **0.54×** | **0.56×** | **1.11×** |

### 3.2 Additional `hot.php` Kernels

| Kernel | Metric | PHP 8.5.13 JIT | B1 JDK 25 C2 | B1 JDK 28-ea C2 | JDK 25 vs PHP | JDK 28-ea vs PHP |
|---|---|---:|---:|---:|---:|---:|
| `none` | p10 / med (`peak`) | `1,778` / `1,817 ns` (`16.9 MiB`) | `3,217` / `3,497 ns` (`89.0 MiB`) | `3,252` / `3,656 ns` (`96.4 MiB`) | **0.55×** / 0.52× | **0.55×** / 0.50× |
| `one` | p10 / med (`peak`) | `10,234` / `10,580 ns` (`16.9 MiB`) | `28,077` / `31,132 ns` (`97.1 MiB`) | `27,744` / `33,528 ns` (`95.3 MiB`) | **0.36×** / 0.34× | **0.37×** / 0.32× |
| `hasf` | p10 / med (`peak`) | `7,205` / `8,099 ns` (`16.6 MiB`) | `13,604` / `15,170 ns` (`91.3 MiB`) | `12,708` / `14,930 ns` (`94.2 MiB`) | **0.53×** / 0.53× | **0.57×** / 0.54× |

### 3.3 Unmodified `bench/k0/kernels.php` (All 10 Arithmetic & Allocation Kernels)

| Kernel | PHP 8.5.13 JIT (p10 / med) | B1 JDK 25 C2 (p10 / med) | B1 JDK 28-ea C2 (p10 / med) | JDK 25 vs PHP (p10) | JDK 28-ea vs PHP (p10) | Peak RAM (25 / 28) |
|---|---:|---:|---:|---:|---:|---:|
| `k0-floatloop` | `9.03` / `9.26 ms` | `8.59` / `9.51 ms` | `7.10` / `7.96 ms` | **1.05×** | **1.27×** *(med 1.16×)* | `76.4` / `76.3 MiB` |
| `k0-intloop` | `16.27` / `16.96 ms` | `18.02` / `19.19 ms` | `16.60` / `17.47 ms` | **0.90×** | **0.98×** | `76.0` / `81.9 MiB` |
| `k0-mandelbrot` | `7.74` / `8.04 ms` | `10.29` / `10.59 ms` | `8.94` / `9.34 ms` | **0.75×** | **0.87×** | `74.2` / `76.8 MiB` |
| `k0-spectralnorm` | `15.84` / `16.33 ms` | `28.17` / `28.97 ms` | `24.91` / `25.78 ms` | **0.56×** | **0.64×** | `78.2` / `79.3 MiB` |
| `k0-bubblesort` | `18.19` / `18.27 ms` | `31.47` / `32.40 ms` | `30.53` / `31.26 ms` | **0.58×** | **0.60×** | `78.7` / `79.0 MiB` |
| `k0-sieve` | `6.41` / `6.56 ms` | `13.40` / `13.92 ms` | `11.24` / `13.33 ms` | **0.48×** | **0.57×** | `116.2` / `100.1 MiB` |
| `k0-binarytrees` | `11.59` / `11.74 ms` | `22.39` / `23.63 ms` | `21.98` / `23.18 ms` | **0.52×** | **0.53×** | `82.3` / `82.1 MiB` |
| `k0-nbody` | `47.92` / `49.62 ms` | `129.39` / `131.88 ms` | `124.85` / `127.67 ms` | **0.37×** | **0.38×** | `89.4` / `91.5 MiB` |
| `k0-fib` | `2.09` / `2.10 ms` | `6.88` / `6.93 ms` | `6.46` / `6.60 ms` | **0.30×** | **0.32×** | `76.0` / `70.3 MiB` |
| `k0-fannkuch` | `17.37` / `18.06 ms` | `150.15` / `151.75 ms` | `153.88` / `172.68 ms` | **0.12×** | **0.11×** | `85.5` / `82.1 MiB` |
| **Full 17-Kernel Geomean (p10)** | **1.00×** | — | — | **0.50×** | **0.53×** | `70.3–116.2 MiB` |

---

## 4. Paired Two-Context Isolation, Steady-State Compilation & Machine Code

### 4.1 Paired Two-Context Penalty (5-Round Median of Per-Round `(2ctx − solo) / solo`)

| Workload / Context | Engine | Solo p10 → 2ctx p10 (Δ) | Solo med → 2ctx med (Δ) | Checksum |
|---|---|---:|---:|---|
| `hooks` CtxA | JDK 25 | `16.28 → 17.05 ms` (`+5.6%`) | `17.42 → 19.50 ms` (`+12.0%`) | `393035:a61726cd` |
| `hooks` CtxB | JDK 25 | `22.10 → 24.19 ms` (`+9.4%`) | `24.22 → 27.31 ms` (`+7.3%`) | `416555:9e619366` |
| `hooks` CtxA | JDK 28-ea | `15.98 → 16.63 ms` (`+8.0%`) | `16.96 → 18.10 ms` (`+9.1%`) | `393035:a61726cd` |
| `hooks` CtxB | JDK 28-ea | `23.06 → 23.65 ms` (`+2.0%`) | `24.75 → 25.69 ms` (`+1.8%`) | `416555:9e619366` |
| `mega` CtxA | JDK 25 | `30,585 → 33,425 ns` (`+12.8%`) | `36,152 → 38,998 ns` (`+7.9%`) | `535000` |
| `mega` CtxB | JDK 25 | `41,736 → 44,328 ns` (`+0.6%`) | `47,874 → 50,076 ns` (`+2.9%`) | `560000` |
| `mega` CtxA | JDK 28-ea | `31,222 → 35,129 ns` (`+13.2%`) | `35,371 → 39,898 ns` (`+15.1%`) | `535000` |
| `mega` CtxB | JDK 28-ea | `47,056 → 45,152 ns` (`-7.1%`) | `53,178 → 52,875 ns` (`-2.7%`) | `560000` |

### 4.2 Steady-State `[STEADY_START_MS, STEADY_END_MS]` Compilation, Deoptimization, NMT & nmethod Size

| Workload | Engine | Steady Window | Steady Uncommon Traps | NMT Committed (Code) | `WP_Hook::apply_filters` nmethod (bytecode) | `apply_filters` nmethod (bytecode) | Combined C2 Code |
|---|---|---|---:|---:|---:|---:|---:|
| `hooks-2ctx` | JDK 25 | `11.60s..21.03s` | **0** *(120 total)* | `140.1 MiB` (`13.8 MiB`) | `27,664 B` (`2,035 B`) | `30,072 B` (`1,884 B`) | **`57.7 KB`** |
| `hooks-2ctx` | JDK 28-ea | `13.04s..22.72s` | **0** *(101 total)* | `130.9 MiB` (`11.1 MiB`) | `28,728 B` (`2,035 B`) | `29,368 B` (`1,884 B`) | **`58.1 KB`** |
| `mega-2ctx` | JDK 25 | `36.71s..71.58s` | **1** *(124 total)* | `140.3 MiB` (`16.8 MiB`) | `25,248 B` (`2,035 B`) | `21,000 B` (`1,888 B`) | **`46.2 KB`** |
| `mega-2ctx` | JDK 28-ea | `37.07s..71.98s` | **0** *(133 total)* | `94.0 MiB` (`14.3 MiB`) | `26,056 B` (`2,035 B`) | `21,632 B` (`1,888 B`) | **`47.7 KB`** |

*(Trampolines `invoke2`/`invoke3` are `448–456 B` nmethods, `13–14 B` bytecode, 1 C2 install each.)*

---

## 5. Week 2 Days 4–5 Go Criteria Scorecard & Quantitative Gap Analysis

| Criterion (`wiki/b1-plan.md` §4) | Target | Observed (`out-final`, 5 rounds) | Status |
|---|---|---|---|
| Checksum & `D17` parity | 100% identical to PHP 8.5.13 | 100% across all 17 kernels, `CtxA`/`CtxB`, 2ctx, all 5 rounds; `48/48` standalone + `142/142` `.phpt` | **PASS** |
| Zero Truffle loads & `B1Lint` (`B1-01..B1-13`) | `0` Truffle classes, `0` violations | `0` Truffle loads (JDK 25 & 28-ea), `0` violations across `38` runtime + `445` hidden classes | **PASS** |
| Max emitted method bytecode (`B1-01`) | `0` methods `> 7,500 B` | Max emitted `4,734 B` (`large-function.php` outlined `6,109–6,435 B`, C2 Tier 4) | **PASS** |
| Steady-state uncommon traps | `≤ 2` | **`0–1`** (`0` on `hooks-2ctx` 25/28 & `mega-2ctx` 28; `1` on `mega-2ctx` 25) | **PASS** |
| Combined `apply_filters` + `WP_Hook::apply_filters` C2 code | `≤ 60–100 KB` | **`46.2–58.1 KB`** | **PASS** |
| Peak cgroup RSS across all 17 kernels | `≤ 200 MiB` | **`70.3–116.2 MiB`** (`hooks` `103.7–106.5 MiB`) | **PASS** |
| Real-PHP geomean vs PHP 8.5.13 JIT | `≥ 1.10×` (no kernel `< 0.85×`) | **4-kernel `0.54×` (JDK 25) / `0.56×` (JDK 28-ea)**; 17-kernel `0.50× / 0.53×` *(beat PHP on `k0-floatloop` `1.05×/1.27×`; near parity on `k0-intloop` `0.98×`, `k0-mandelbrot` `0.87×`, `post` `0.81×` p10 / `0.86×` med)* | **MISS (Logged → W3-5)** |
| Paired 2-context penalty | `≤ +5%` | `hooks` CtxA `+5.6% / +8.0%`, CtxB `+9.4% / +2.0%`; `mega` CtxA `+12.8% / +13.2%`, CtxB `+0.6% / -7.1%` | **MISS (Logged → W3-5)** |

### Why B1 Is at `0.56×` Today vs. Probe B's `1.11×` Control (and Why `≥ 1.10×` Is Reachable)

Pass 3's unboxed `double` pass proved that once B1 removes unnecessary boxing and per-call scaffolding, **real-PHP-compiled B1 beats PHP 8.5.13 JIT directly**: `k0-floatloop` jumped to **`1.27×`** (JDK 28-ea) / `1.05×` (JDK 25), `k0-mandelbrot` jumped ~20× from `0.04× → 0.87×`, `k0-intloop` reached `0.98×`, and `post` reached `0.81×` p10 (`0.86×` median).

The remaining `~2×` gap to Probe B's `1.11×` control and the CtxA `+5.6%..+13.2%` 2-context penalty come from **three concrete, profiled causes** scheduled in **`W3-5`** (`wiki/b1-plan.md` §6.2):
1. **Inline cold branches exhausting C2's inlining budget (`DesiredMethodLimit`):** `WP_Hook::apply_filters` (`2,035 B` bytecode) and `apply_filters` (`1,884 B`) keep cold error/warning, `'all'`-hook, CoW, and `D17` teardown branches inline. Outlining those cold paths into private static `cold*` helpers shrinks hot bytecode to ~400–600 B so C2 inlines the hot loop cleanly **and** prevents CtxB's taken `'all'`-hook branch from polluting CtxA's shared hot-path profile.
2. **Unboxed `long` locals/expressions + packed primitive `B1Array` (`long[]`/`double[]`):** Pass 3 only unboxed `double`s. Mirroring that pass for `long` + packed primitive arrays directly targets `k0-fannkuch` (`0.12×`), `k0-fib` (`0.32×`), `k0-nbody` (`0.38×`), `k0-sieve` (`0.57×`), and `aget` (`0.44×`).
3. **Variadic forwarding & `$this->iterations` copy overhead in `apply_filters`:** Real PHP `apply_filters($hook_name, $value, ...$args)` currently builds/shifts a fresh `B1Array` (`...$args` + `array_unshift`) and copies `$this->iterations` on every call; lean arity-specialized variadic forwarding eliminates this allocation on the hot path.

---

## 6. Empirical JDK 25 vs. JDK 28-ea Virtual-Thread FFM Native-Pinning Check

Tested in Linux Docker on the exact pinned containers (`Temurin-25.0.5.1+2-LTS` and `OpenJDK 28-ea+25-2095`) using a 3-part JFR + FFM `libc qsort` upcall probe (`-Djdk.virtualThreadScheduler.parallelism=2 -Djdk.virtualThreadScheduler.maxPoolSize=2`):

| Scenario | OpenJDK 25.0.5.1-LTS | OpenJDK 28-ea+25-2095 | Conclusion |
|---|---|---|---|
| **1. `synchronized` monitor blocking (JEP 491)** | `pinnedEvents = 0` | `pinnedEvents = 0` | JEP 491 unpins Java monitors on both JDKs. |
| **2. FFM `libc qsort` native upcall blocking on `Semaphore`** | **`pinnedEvents = 1`** (`jdk.VirtualThreadPinned`, `60.8 ms`) | **`pinnedEvents = 1`** (`jdk.VirtualThreadPinned`, `60.4 ms`) | **FFM native frames STILL pin carriers on JDK 28-ea+25.** |
| **3. Carrier starvation (2 native-suspended VTs + 1 resumer VT, `maxPoolSize=2`)** | **DEADLOCK / STARVED** (`STARVED_TIMEOUT_SLOT_0/1` + HotSpot fatal `Unrecoverable uncaught exception` unwinding FFM upcall) | **DEADLOCK / STARVED** (`STARVED_TIMEOUT_SLOT_0/1` + HotSpot fatal `Unrecoverable uncaught exception` unwinding FFM upcall) | Virtual threads still deadlock when Fibers suspend inside native callbacks (`libcurl`, `qsort`). |
| **4. Platform-thread `B1FiberThread` (`B1-13` model)** | `completed = 2/2`, `starved = 0` | `completed = 2/2`, `starved = 0` | **`B1-13` platform-thread Fibers remain mandatory on JDK 28.** |

---

## 7. Cross-Model Audit & Test Suite Verification (`81891667`)

Every implementation and optimization pass in Week 2 Days 4–5 was implemented with `claude-private --model opus` and passed a read-only adversarial audit by `agy` Gemini 4 Neon (`gemini-4-neon-high`):

- **Step 1 (`fedc1730`):** 10 P1 + 7 P2 fixed → Neon **PASS**.
- **Pass 1 (`b483f7d1`):** 6 P0 + 3 P1 + 3 P2 + 3 follow-ups fixed → Neon **PASS**.
- **Pass 2 (`68fdcb21`):** 1 P0 (frozen unindexed `B1Array` race) + 1 P1 (`B1Frame.retire()`) + 3 P2 fixed → Neon **PASS**.
- **Pass 3 (`81891667`):** 3 P0 (unboxed `double` `.=`/`%=` self-check, `Binary.deferredLeft()`, closure capture reads) + 3 P2 fixed + 33/33 `-Xverify:all` adversarial probes → Neon **PASS**.
- **Full Regression Suite on `81891667`:**
  - `mvn test` (GraalVM `25.4.4.1.1`): **`178/178`** passing (`18/18` `CompilationTest`, `8/8` `ZendParserCorpusTest`).
  - `verify-b1-standalone.ps1` (stock Temurin OpenJDK 25.0.4, **0** Truffle JARs): **`48/48`** PHP 8.5.13 byte-exact matches + 5 expected fatals, **`0`** Truffle loads, **`0`** `B1Lint` violations (`38` runtime classes), C2 Tier 4 on `large-function.php`.
  - Linux Docker `B1BatchMain` `.phpt`: **`142/142`** (`100.0%`, `0` diff / `0` error / `0` timeout) across `Zend/tests/{gc, exceptions, generators, fibers}`.
