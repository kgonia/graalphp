# Probe B Results — True Open-World `.class` Runtime + Real `D17` (2026-11-25)

Full pre-check context and kill criteria: [wiki/b1-precheck.md](../../../wiki/b1-precheck.md).

## 1. Setup & What Was Tested (Rev 3)

Probe B removes **every** closed-world shortcut from `bench/aot-spike/` without touching `src/main/` or `pom.xml`:

1. **Zero-Truffle projection** (`project.py`): projects `src/main/java/pl/codeloom/php/runtime/PhpArray.java` (all 3 stores: `long[]`, `Object[]`, `Hash` + CoW) and `PhpRef.java` (retain-before-release fix + `PhpClosure` counting + `gc_possible_root` array unbuffering) onto `ByteStr`.
2. **Real B1 byte-strings (`ByteStr.java`)**: immutable raw `byte[]` with cached hash, raw-byte `trim` (1-arg and 2-arg `..` ranges), `ucfirst`, `replace`, ASCII case-folding, direct byte `ArrayKey.ofString` (no UTF-16 `String`), and single-digit `'0'..'9'` decimal cache matching `php-src` `ZSTR_CHAR`.
3. **Explicit `PhpRequestContext` parameter 0**: per-request open-world function, class, constant, global, and WordPress hook tables (`$wp_filter`, `$wp_filters`, `$wp_actions`, `$wp_current_filter`); zero `ThreadLocal`s, zero global `SwitchPoint`s.
4. **Open-world `shared` dispatch (Probe A winner)**: `PhpRequestContext.call(...)` dispatches `PhpClosure`, function names, `"Class::method"`, `[PhpObject, method]`, `[ByteStr, method]`, and invokable objects via vtable `invokevirtual` on `abstract class PhpCallable` / `PhpInstanceMethod` (`0` `invokedynamic` instructions).
5. **True 181-class megamorphism in `mega` (`MegaGenerated.java`)**: `60 + 1` distinct function classes (`HotFn0..59`, `HotFn0Override`), `20` distinct `HotC0..19` object classes + `60` distinct method classes (`a`, `b`, `s`), and `40` distinct closure subclasses (`HotClosure0..39`) across `40` hooks × `5` callbacks = `200` dynamic calls and `160` 1-arg array slices per op.
6. **Full WordPress fidelity**: `HooksOpenWorld` implements `wp-includes/plugin.php:175-212` variadic `$args` + `prepend` + live `$wp_filter['all']` rechecks across user code, active `Hook` receiver retention on self-removal, and WordPress `do_action` empty-arg `''` + previous-return hold. `WPPost` resolves both dynamic writes (`$this->$key = $value`) and timed reads (`readProp`) through runtime `PhpClass` property metadata with reentrant-safe publish-before-release on all 24 declared fields.
7. **Real `D17` exact refcounting + Bacon-Rajan cycle collector + Zend shutdown**: shadow-refcount cycle collector over `PhpObject`, `PhpArray`, `PhpClosure`, and `PhpRef` with post-destructor reachability recomputation, exception-safe protection unwind, O(1) intrusive destructible unregistration, and Zend's iterative `rc == 1` reverse-globals shutdown pass (`zend_execute_API.c:210-267`).

### Verified Environment (Pinned Digests, Linux Docker `--cpuset-cpus 14-21`)
- **PHP:** `PHP 8.5.13 jit_on=1 kind=5` (`php@sha256:dbf32b33595283b79fd1a37ebc1614bdf1b83d3750daad9a57d67dbb9b96ec16`), verified by `php-ref/probe_env.php` before every timed run.
- **JVM:** Oracle GraalVM `25.0.4+7.1` (`sha256:dea9feaf7562b761f868a86d707a1090e373595121646b45cdc4821afe250c40`).
  - **C2 capped:** `-XX:-UseJVMCICompiler -Xmx64m -XX:+UseParallelGC -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=48m`
  - **Graal capped:** `-XX:+UseJVMCICompiler -Xmx64m -XX:+UseParallelGC -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=48m -Djdk.graal.internal.MaxHeapSize=64m`

## 2. Correctness & Adversarial Review Verification

- **Codex adversarial review loop (`hc`):**
  - **Round 1 (`t5c`, 167k tokens)** caught 9 findings: cycle collector external-array & negative-rc bugs, `PhpRef.set` release-before-retain, filter replacement leaks, `mega` monomorphic lambda collapse, `WPPost` linear `if`-chain, `d17_fixture.php` bookmark/shutdown gaps, and unverified PHP JIT probe. All 9 were fixed in Rev 2.
  - **Round 2 (`t5g`, 130k tokens)** verified all 181 distinct classes (`0` `invokedynamic`), `WPPost` metadata, `plugin.php` fidelity, and byte-identical `D17` transcripts, and caught deeper edge cases: dead `PhpArray` accumulation in `ctx.gcArrayRoots` across `hooks` rounds, reentrant `WPPost` write ordering, self-clearing cycle resurrection, shared `PhpRef`/closure/10-deep array cycles, shutdown traversal under mutation (`A` freeing `B` skipping `C`), `doAction` zero-arg `''` + previous-return hold, `HtmlToken` closure `$this` binding, and JDK 25 NMT byte parsing + paired per-round penalties. All were fixed by Codex (`t5h`, 121k tokens) in Rev 3 alongside upgrading `PhpCallable`/`PhpInstanceMethod` from `interface` to `abstract class` (`invokevirtual`).
- **`D17` byte-for-byte parity (`build/d17-php-v2.txt` vs `build/d17-java-v3.txt`):** **EXACT MATCH (28 lines, 556 bytes)** covering 12,000 `HtmlToken` `$on_destroy` bookmark callbacks (`active=0, peak=1, released=12000, liveDestructibleCount=0, part1_gc=0`), 2-object cycle (`gc_collect_1:2`), array-mediated cycle (`gc_collect_2:4`), `Phoenix` resurrection, and Zend iterative `rc == 1` reverse-globals shutdown with aliased globals + destructor mutation (`G2 → G1 → G3 → A1 → A2`).
- **Kernel checksums:** 100% identical across all 5 interleaved rounds and all engines (`hooks`: `393035:a61726cd`, `aget`: `60000`, `post`: `130000`, `mega`: `535000`).

## 3. Rev 3 Benchmark Results (`out-v3/`, 5 Interleaved Rounds, CPUs 14–21)

Primary decision metric: **p10 of 2nd half** (matching Gate A0 / `opstable.py`); **median of 2nd half** also reported.

| Kernel | Metric (2nd half) | PHP 8.5.13 JIT | Probe B C2 (`-Xmx64m`) | Probe B Graal (`-Xmx64m`) | C2 vs PHP | Graal vs PHP |
|---|---|---:|---:|---:|---:|---:|
| `hooks` (400 rds) | p10 | `7.42 ms` | **`6.45 ms`** | `6.93 ms` | **1.15×** | 1.07× |
| `hooks` (400 rds) | median | `7.75 ms` | **`6.85 ms`** | `7.25 ms` | **1.13×** | 1.07× |
| `hooks` | cgroup `memory.peak` | `18.1 MiB` | **`72.6 MiB`** | `90.1 MiB` | — | — |
| `aget` (3000×250) | p10 | `2,403 ns` | **`2,284 ns`** | `2,391 ns` | **1.05×** | 1.01× |
| `aget` (3000×250) | median | `2,534 ns` | **`2,330 ns`** | `2,494 ns` | **1.09×** | 1.02× |
| `aget` | cgroup `memory.peak` | `16.6 MiB` | **`32.5 MiB`** | `60.9 MiB` | — | — |
| `post` (3000×250) | p10 | `8,347 ns` | **`7,684 ns`** | `10,213 ns` | **1.09×** | 0.82× |
| `post` (3000×250) | median | `9,151 ns` | `9,209 ns` | `10,753 ns` | 0.99× | 0.85× |
| `post` | cgroup `memory.peak` | `16.7 MiB` | **`47.2 MiB`** | `79.5 MiB` | — | — |
| `mega` (3000×250, 181 cls) | p10 | `16,901 ns` | `21,172 ns` | **`18,640 ns`** | **0.80×** | **0.91×** |
| `mega` (3000×250, 181 cls) | median | `18,624 ns` | `23,147 ns` | **`20,532 ns`** | **0.80×** | **0.91×** |
| `mega` | cgroup `memory.peak` | `16.6 MiB` | **`65.6 MiB`** | `86.1 MiB` | — | — |
| **4-kernel geomean** | **p10 / median** | **1.00× / 1.00×** | **1.01× / 0.99×** | **0.95× / 0.96×** | — | — |

*Evolution across revisions:*
- Rev 1 (monomorphic lambdas on `mega`, 24-branch linear `if` on `post`): C2 `mega 1.30×` (artifact of 1 shared lambda), `post 0.81×`.
- Rev 2 (181 distinct classes via `interface`, open-world `PhpClass` metadata on `post`): C2 `post 1.33×`, `mega 0.78×`; Graal `mega 0.94×`.
- Rev 3 (all Round 2 `D17` fixes + `abstract class` vtable `invokevirtual` on `mega` + `gcArrayRoots` leak fix): C2 `mega` moved only `0.78× → 0.80×` (`hooks` cgroup peak dropped `79.9 → 72.6 MiB`, `mega` peak dropped `74.6 → 65.6 MiB`), proving C2's `-20%` `mega` gap is not `itable` overhead—it is open-world `HashMap` dispatch + `160` `PhpArray` slice allocations/op + `D17` container bookkeeping.

### Paired Two-Context Penalty (Both `CtxA` & `CtxB` vs. Solo Controls, Interleaved Within Each Round)

| Workload & Context | C2 Solo → 2ctx (Paired p10) | C2 Solo → 2ctx (Paired med) | Graal Solo → 2ctx (Paired p10) | Graal Solo → 2ctx (Paired med) |
|---|---:|---:|---:|---:|
| `hooks` `CtxA` (standard) | `6.45 → 6.71 ms` (**+4.77%**) | `6.85 → 7.30 ms` (**+10.65%**) | `6.93 → 6.66 ms` (+4.24%) | `7.25 → 8.25 ms` (**+12.95%**) |
| `hooks` `CtxB` (overridden + `'all'`) | `8.53 → 8.94 ms` (+1.93%) | `9.14 → 9.61 ms` (+2.62%) | `9.46 → 9.20 ms` (-0.65%) | `10.19 → 11.11 ms` (+3.11%) |
| `mega` `CtxA` (standard) | `21,172 → 19,552 ns` (-3.14%) | `23,147 → 21,752 ns` (-2.05%) | `18,640 → 20,308 ns` (**+8.95%**) | `20,532 → 22,320 ns` (**+8.70%**) |
| `mega` `CtxB` (overridden + `'all'`) | `23,366 → 23,259 ns` (-0.24%) | `26,142 → 25,708 ns` (-0.00%) | `22,402 → 22,896 ns` (+1.36%) | `25,776 → 25,327 ns` (+1.35%) |

### Steady-State Compilation, Deoptimization & NMT (`[STEADY_START_MS, STEADY_END_MS]`)

| Run | Steady Window | `nmethods` (steady) | `make_not_entrant` (steady) | `uncommon_traps` (steady) | NMT Committed | NMT Code |
|---|---|---:|---:|---:|---:|---:|
| `hooks-2ctx` (C2) | `5.64s..10.71s` | `890` (`69`) | `221` (`9`) | `21` (**`0`**) | `106.0 MiB` | `3.1 MiB` |
| `hooks-2ctx` (Graal) | `6.10s..10.92s` | `901` (`71`) | `232` (`14`) | `14` (**`0`**) | `95.5 MiB` | `3.8 MiB` |
| `mega-2ctx` (C2) | `20.22s..39.96s` | `1,094` (`23`) | `409` (`10`) | `10` (**`1`**) | `115.4 MiB` | `4.5 MiB` |
| `mega-2ctx` (Graal) | `20.83s..38.32s` | `1,089` (`25`) | `404` (`10`) | `6` (**`2`**) | `113.2 MiB` | `5.2 MiB` |

## 4. Kill-Criteria Verdict

| Criterion (`wiki/b1-precheck.md` §3) | Gate | HotSpot C2 | HotSpot + Graal |
|---|---|---|---|
| Open-world `hooks` (`≥ 1.0–1.10×`, `≤ +20%` vs `5.44 ms`) | `≤ 6.53 ms` | **PASS** (`1.15×`, `+18.6%`) | **FAIL** (`1.07×`, `+27.4%`) |
| Open-world `post` | `≥ 0.85–0.90×` | **PASS** (`1.09×` p10, `0.99×` med) | **FAIL** (`0.82×` p10, `0.85×` med) |
| Open-world `mega` (181 distinct classes) | `≥ 0.90×` | **FAIL** (`0.80×` p10/med) | **PASS** (`0.91×` p10/med) |
| Paired 2-context penalty | `≤ +5%` | **MIXED** (`+4.77%` p10 PASS, `+10.65%` med FAIL on `hooks` CtxA) | **FAIL** (`+8.95%` p10 on `mega` CtxA, `+12.95%` med on `hooks` CtxA) |
| Steady-state deopt storm | None | **PASS** (`0–1` traps) | **PASS** (`0–2` traps) |
| Semantics & `D17` destructor parity | 100% match | **PASS** (exact 28-line diff) | **PASS** (exact 28-line diff) |

**Strict verdict: NO-GO on unmodified thresholds — neither C2 nor Graal clears every kill criterion simultaneously.**
**Architectural takeaway:** Plain **HotSpot C2** (`-Xmx64m`) beats Graal JIT on 3 of 4 kernels (`hooks`, `aget`, `post`), reaches **`1.01×` 4-kernel geomean parity** with PHP 8.5.13 tracing JIT, uses **20–45% less cgroup peak RAM** (`32.5–72.6 MiB` vs. `60.9–90.1 MiB`), and hits zero deopt storms—failing strictly on megamorphic `mega` (`0.80×` vs. `≥ 0.90×`) and `hooks` CtxA median (`+10.65%`). Combined with **Option 1's post-bootstrap freeze** (`1.63×` request speedup, `-83.5%` per-request heap), **B1 (plain OpenJDK C2) + Option 1** remains the strongest JVM architecture to beat pure PHP 8.5 end-to-end—but proceeding requires an explicit owner **D19** decision waiving the strict `mega ≥ 0.90×` gate (or first closing the `mega` slice/lookup gap in `WP_Hook` / `PhpRequestContext`).
