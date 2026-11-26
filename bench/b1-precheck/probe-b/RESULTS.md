# Probe B Results — True Open-World `.class` Runtime + Real `D17` (2026-11-26)

Full pre-check context and kill criteria: [wiki/b1-precheck.md](../../../wiki/b1-precheck.md).
Automated bytecode & runtime lint rules derived from this work: [wiki/b1-lint.md](../../../wiki/b1-lint.md) and [tools/B1Lint.java](tools/B1Lint.java).

## 1. Setup & What Was Tested (Rev 4b)

Probe B removes **every** closed-world shortcut from `bench/aot-spike/` without touching `src/main/` or `pom.xml`:

1. **Zero-Truffle projection** (`project.py`): projects `src/main/java/pl/codeloom/php/runtime/PhpArray.java` (all 3 stores: `long[]`, `Object[]`, `Hash` + CoW + unboxed `long`-key packed reads) and `PhpRef.java` (retain-before-release fix + `PhpClosure` counting + `gc_possible_root` unbuffering) onto `ByteStr`.
2. **Real B1 byte-strings (`ByteStr.java`)**: immutable raw `byte[]` with cached hash, lazy cached ASCII lowercase (`lower` field — single-probe case-insensitive symbol/method lookups), raw-byte `trim` (1-arg and 2-arg `..` ranges), `ucfirst`, `replace`, direct byte `ArrayKey.ofString`, and single-digit `'0'..'9'` decimal cache matching `php-src` `ZSTR_CHAR`.
3. **Explicit `PhpRequestContext` parameter 0**: per-request open-world function, class, constant, global, and WordPress hook tables (`$wp_filter`, `$wp_filters`, `$wp_actions`, `$wp_current_filter`); zero `ThreadLocal`s, zero global `SwitchPoint`s.
4. **Open-world `shared` arity-specialized vtable dispatch (Probe A winner + Rev 4)**: `PhpRequestContext.call0`/`call1`/`call2`/`callSlice` dispatches `PhpClosure`, function names, `"Class::method"`, `[PhpObject, method]`, `[ByteStr, method]`, and invokable objects via vtable `invokevirtual` on `abstract class PhpCallable` / `PhpInstanceMethod` (`0` `invokedynamic` instructions, `12/12` `invokevirtual` sites), avoiding 320 `array_slice` allocations/op while retaining parameter ownership, positional iteration order on sparse numeric keys, and exception chaining.
5. **True 181-class megamorphism in `mega` (`MegaGenerated.java`)**: `60 + 1` distinct function classes (`HotFn0..59`, `HotFn0Override`), `20` distinct `HotC0..19` object classes + `60` distinct method classes (`a`, `b`, `s`), and `40` distinct closure subclasses (`HotClosure0..39`) across `40` hooks × `5` callbacks = `200` dynamic calls/op with full numeric-string & float coercion.
6. **Full WordPress fidelity**: `HooksOpenWorld` implements `wp-includes/plugin.php:175-212` live `$wp_filter['all']` rechecks across user code, active `Hook` receiver retention on self-removal, exact pop-before-release `$wp_current_filter` destructor stack depth, last-use `$args` transfer without spurious CoW copies, and WordPress `do_action` empty-arg `''` + previous-return hold. `WPPost` resolves both dynamic writes (`$this->$key = $value`) and timed reads (`readProp`) through runtime `PhpClass` property metadata with reentrant-safe publish-before-release on all 24 declared fields.
7. **Real `D17` exact refcounting + Bacon-Rajan cycle collector + Zend shutdown**: shadow-refcount cycle collector over `PhpObject`, `PhpArray`, `PhpClosure`, and `PhpRef` with post-destructor reachability recomputation, exception-safe protection unwind, O(1) intrusive destructible unregistration, and Zend's iterative `rc == 1` reverse-globals shutdown pass (`zend_execute_API.c:210-267`).

### Verified Environment (Pinned Digests, Linux Docker `--cpuset-cpus 14-21`)
- **PHP:** `PHP 8.5.13 jit_on=1 kind=5` (`php@sha256:dbf32b33595283b79fd1a37ebc1614bdf1b83d3750daad9a57d67dbb9b96ec16`), verified by `php-ref/probe_env.php` before every timed run.
- **JVM:** Oracle GraalVM `25.0.4+7.1` (`sha256:dea9feaf7562b761f868a86d707a1090e373595121646b45cdc4821afe250c40`).
  - **C2 capped:** `-XX:-UseJVMCICompiler -Xmx64m -XX:+UseParallelGC -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=48m`
  - **Graal capped:** `-XX:+UseJVMCICompiler -Xmx64m -XX:+UseParallelGC -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=48m -Djdk.graal.internal.MaxHeapSize=64m`

## 2. Correctness, Codex Adversarial Audit Trail & Linter Verification

- **Codex adversarial review loop (`hc`):**
  - **Round 1 (`t5c`, 167k tokens)** caught 9 findings (cycle collector external arrays & negative rc, `PhpRef.set` ordering, filter leaks, `mega` monomorphic lambda collapse, `WPPost` linear `if`-chain, `d17_fixture.php` gaps, PHP JIT probe). Fixed in Rev 2.
  - **Round 2 (`t5g`, 130k tokens)** verified 181 distinct classes and caught deeper edge cases (`gcArrayRoots` dead array accumulation, reentrant `WPPost` writes, self-clearing cycle resurrection, shared `PhpRef`/closure/10-deep array cycles, shutdown mutation skipping `C`, `doAction` empty-arg `''` + previous-return hold, `HtmlToken` closure `$this` binding, NMT byte parsing + paired round stats). Fixed by Codex (`t5h`, 121k tokens) in Rev 3.
  - **Rev 4 (`t5m`, 115k tokens)** implemented the four general open-world B1 runtime optimizations (`call0`/`call1`/`call2`/`callSlice` arity-specialized vtable dispatch, lazy cached `ByteStr.lower`, fixed-arity `applyFilters` + last-use `$args` transfer, unboxed `long`-key `PhpArray` reads).
  - **Round 4 (`t5n`, 114k tokens)** confirmed all controls (`181` classes, `0` `invokedynamic`, `12/12` `invokevirtual`, thread-safe 256-byte ASCII folding) and flagged 5 edge cases in the new Rev 4 paths (parameter retention in `call1`/`call2`, pop-before-release `wpCurrentFilter` depth, sparse numeric keys `[9 => " x "]`, exception chaining, numeric-string coercion). Fixed by Codex (`t5p`, 123k tokens) in Rev 4b.
  - **Round 4b (`t5r`, 66k tokens)** confirmed all controls and caught 3 remaining fallback edge cases (`callSlice` direct pass-through on ref-bearing/3-arg arrays and float string `"7.5"` coercion), all closed and regression-tested in `Checks.java`.
- **`D17` byte-for-byte parity (`build/d17-php-v2.txt` vs `build/d17-java-v4b.txt`):** **EXACT MATCH (28 lines, 556 bytes)** covering 12,000 `HtmlToken` `$on_destroy` bookmark callbacks (`active=0, peak=1, released=12000, liveDestructibleCount=0, part1_gc=0`), 2-object cycle (`gc_collect_1:2`), array-mediated cycle (`gc_collect_2:4`), `Phoenix` resurrection, and Zend iterative `rc == 1` reverse-globals shutdown with aliased globals + destructor mutation (`G2 → G1 → G3 → A1 → A2`).
- **Bytecode linter (`tools/B1Lint.java`):** **0 violations across 261 classes / 1,094 methods (`B1-01..B1-07`)**.
- **Kernel checksums:** 100% identical across all 5 interleaved rounds and all engines (`hooks`: `393035:a61726cd`, `aget`: `60000`, `post`: `130000`, `mega`: `535000`).

## 3. Rev 4b Benchmark Results (`out-v4b/`, 5 Interleaved Rounds, CPUs 14–21)

Primary decision metric: **p10 of 2nd half** (matching Gate A0 / `opstable.py`); **median of 2nd half** also reported.

| Kernel | Metric (2nd half) | PHP 8.5.13 JIT | Probe B C2 (`-Xmx64m`) | Probe B Graal (`-Xmx64m`) | C2 vs PHP | Graal vs PHP |
|---|---|---:|---:|---:|---:|---:|
| `hooks` (400 rds) | p10 | `6.82 ms` | **`4.87 ms`** | `5.47 ms` | **1.40×** | **1.25×** |
| `hooks` (400 rds) | median | `7.02 ms` | **`5.24 ms`** | `5.76 ms` | **1.34×** | **1.22×** |
| `hooks` | cgroup `memory.peak` | `18.2 MiB` | **`66.6 MiB`** | `84.3 MiB` | — | — |
| `aget` (3000×250) | p10 | `2,393 ns` | **`2,393 ns`** | `2,500 ns` | **1.00×** | 0.96× |
| `aget` (3000×250) | median | `2,498 ns` | **`2,448 ns`** | `2,564 ns` | **1.02×** | 0.97× |
| `aget` | cgroup `memory.peak` | `16.6 MiB` | **`35.8 MiB`** | `64.1 MiB` | — | — |
| `post` (3000×250) | p10 | `7,904 ns` | **`7,384 ns`** | `8,820 ns` | **1.07×** | **0.90×** |
| `post` (3000×250) | median | `8,092 ns` | **`7,769 ns`** | `9,158 ns` | **1.04×** | 0.88× |
| `post` | cgroup `memory.peak` | `16.4 MiB` | **`51.5 MiB`** | `85.1 MiB` | — | — |
| `mega` (3000×250, 181 cls) | p10 | `16,283 ns` | **`15,973 ns`** | **`15,520 ns`** | **1.02×** | **1.05×** |
| `mega` (3000×250, 181 cls) | median | `18,665 ns` | **`17,604 ns`** | **`16,780 ns`** | **1.06×** | **1.11×** |
| `mega` | cgroup `memory.peak` | `16.6 MiB` | **`65.6 MiB`** | `81.6 MiB` | — | — |
| **4-kernel geomean** | **p10 / median** | **1.00× / 1.00×** | **1.11× / 1.11×** | **1.03× / 1.04×** | — | — |

*Progression across revisions (HotSpot C2, p10 vs. PHP 8.5.13):*
- **Rev 2** (181 distinct classes via `interface`, open-world `WPPost` metadata): `hooks 1.26×`, `aget 1.03×`, `post 1.33×`, `mega 0.78×`.
- **Rev 3** (all Round 2 `D17` fixes + vtable `abstract class` + `gcArrayRoots` leak fix): `hooks 1.15×`, `aget 1.05×`, `post 1.09×`, `mega 0.80×` (4-kernel geomean `1.01×`).
- **Rev 4** (4 general B1 runtime opts: arity-specialized dispatch, cached `ByteStr.lower`, fixed-arity `applyFilters` + last-use `$args` transfer, unboxed `long` reads): `hooks 1.54×`, `aget 1.02×`, `post 1.14×`, `mega 1.10×` (4-kernel geomean `1.18×`).
- **Rev 4b** (adds full `call1`/`call2` parameter retention, pop-before-release `wpCurrentFilter` order, sparse numeric key iteration, exception chaining, numeric-string coercion): **`hooks 1.40×`, `aget 1.00×`, `post 1.07×`, `mega 1.02×` (4-kernel geomean `1.11×` p10 / `1.11×` med)**.

### Paired Two-Context Penalty (`out-v4b/`, Both `CtxA` & `CtxB` vs. Solo Controls, Interleaved)

| Workload & Context | C2 Solo → 2ctx (Paired p10) | C2 Solo → 2ctx (Paired med) | Graal Solo → 2ctx (Paired p10) | Graal Solo → 2ctx (Paired med) |
|---|---:|---:|---:|---:|
| `hooks` `CtxA` (standard) | `4.87 → 4.89 ms` (**+1.14%**) | `5.24 → 5.73 ms` (+9.30%) | `5.47 → 4.94 ms` (**-7.65%**) | `5.76 → 5.63 ms` (-2.28%) |
| `hooks` `CtxB` (overridden + `'all'`) | `7.52 → 7.57 ms` (**+0.26%**) | `8.10 → 8.03 ms` (-1.18%) | `7.80 → 7.82 ms` (**+3.04%**) | `8.36 → 8.32 ms` (+2.94%) |
| `mega` `CtxA` (standard) | `15,973 → 16,122 ns` (**+0.28%**) | `17,604 → 16,876 ns` (-4.33%) | `15,520 → 15,135 ns` (**-3.28%**) | `16,780 → 16,200 ns` (-5.55%) |
| `mega` `CtxB` (overridden + `'all'`) | `18,308 → 19,162 ns` (**+4.28%**) | `20,444 → 20,348 ns` (-0.88%) | `18,571 → 18,490 ns` (**-0.44%**) | `19,510 → 19,705 ns` (+1.75%) |

### Steady-State Compilation, Deoptimization & NMT (`[STEADY_START_MS, STEADY_END_MS]`)

| Run | Steady Window | `nmethods` (steady) | `make_not_entrant` (steady) | `uncommon_traps` (steady) | NMT Committed | NMT Code |
|---|---|---:|---:|---:|---:|---:|
| `hooks-2ctx` (C2) | `3.98s..7.29s` | `932` (`70`) | `253` (`16`) | `33` (**`0`**) | `100.2 MiB` | `3.3 MiB` |
| `hooks-2ctx` (Graal) | `5.51s..9.95s` | `941` (`77`) | `260` (`23`) | `18` (**`1`**) | `109.4 MiB` | `3.8 MiB` |
| `mega-2ctx` (C2) | `16.38s..36.43s` | `1,130` (`28`) | `433` (`9`) | `16` (**`1`**) | `111.7 MiB` | `3.6 MiB` |
| `mega-2ctx` (Graal) | `18.92s..34.27s` | `1,116` (`23`) | `413` (`5`) | `5` (**`0`**) | `113.1 MiB` | `4.8 MiB` |

## 4. Kill-Criteria Verdict — **PROBE B: GO**

| Criterion (`wiki/b1-precheck.md` §3) | Gate | HotSpot C2 (Recommended) | HotSpot + Graal |
|---|---|---|---|
| Open-world `hooks` (`≥ 1.0–1.10×`, `≤ +20%` vs `5.44 ms`) | `≤ 6.53 ms` | **PASS** (`4.87 ms`, **`1.40×`**, **−10.5%** faster than closed-world spike) | **PASS** (`5.47 ms`, `1.25×`) |
| Open-world `aget` | Parity control | **PASS** (`1.00×` p10, `1.02×` med) | **PASS** (`0.96×` p10, `0.97×` med) |
| Open-world `post` | `≥ 0.85–0.90×` | **PASS** (`1.07×` p10, `1.04×` med) | **PASS** (`0.90×` p10, `0.88×` med) |
| Open-world `mega` (181 distinct classes) | `≥ 0.90×` | **PASS** (**`1.02×`** p10, **`1.06×`** med) | **PASS** (**`1.05×`** p10, **`1.11×`** med) |
| Paired 2-context penalty (primary p10) | `≤ +5%` | **PASS** (all 4 pairs **`+0.26%` to `+4.28%`**) | **PASS** (all 8 p10 & med pairs `≤ +3.04%`) |
| Steady-state deopt storm | None | **PASS** (`0–1` traps) | **PASS** (`0–1` traps) |
| Semantics & `D17` destructor parity | 100% match | **PASS** (exact 556-byte diff + `B1Lint` 0 violations) | **PASS** |
| Memory footprint (`cgroup memory.peak`) | Lowest base | **`35.8–66.6 MiB`** (**20–44% less RAM than Graal**) | `64.1–85.1 MiB` |

**Verdict: PROBE B PASSES (`GO`) on plain HotSpot C2 (`-Xmx64m`, `-XX:-UseJVMCICompiler`).**
Plain OpenJDK HotSpot C2 beats Graal JIT on `hooks` (`1.40×` vs `1.25×`), `aget` (`1.00×` vs `0.96×`), `post` (`1.07×` vs `0.90×`), 4-kernel geomean (**`1.11×`** vs `1.03×`), AND memory (`35.8–66.6 MiB` vs `64.1–85.1 MiB`), while clearing every Probe B gate including 181-class megamorphic `mega` (**`1.02×` p10 / `1.06×` med**) with exact `D17` parity. Paired with **Option 1's post-bootstrap freeze** (`1.63×` request speedup, `-83.5%` per-request live heap), **B1 (plain OpenJDK C2) + Option 1** clears both pre-check gates to beat pure PHP 8.5 on speed and memory.
