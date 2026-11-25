# Plan: Closed-World PHP → JVM Bytecode → GraalVM Native Image + PGO

Part of the [wiki](README.md). Follows from [alternatives.md](alternatives.md) and branch `ceiling`
(`ceiling:wiki/ceiling.md`, `ceiling:wiki/beat-php.md`), which showed that Truffle partial evaluation hits a 19–32 MB
machine-code volume wall on WordPress renders (1.65× PHP's time kept alive, 2.12× served, ~2.4 GB RAM).

## 1. Goal

Compile **actual, unmodified `.php` files** of a WordPress/WooCommerce site ahead-of-time to plain JVM `.class`
bytecode—reusing this repo's PHP 8.5 parser and Java runtime library, with **Truffle removed from the execution
path**—and build a single native server binary with **GraalVM Native Image + PGO**.

**Acceptance bar:** Beat `php-fpm` 8.5 (OPcache + tracing JIT) on WordPress and WooCommerce in **steady-state
throughput/latency**, **startup time**, and **memory at server concurrency**, while preserving exact observable PHP
semantics ([D17](decisions.md), [D18](decisions.md)).

**Tradeoff accepted upfront:** Closed-world build model. Adding or updating a plugin triggers a store rebuild (fitting
CI-deployed / managed WordPress & WooCommerce hosting), with an optional lightweight fallback interpreter only for
`eval()` or ad-hoc scripts.

---

## 2. Architecture

### Pipeline

```text
Actual .php files (WordPress core + theme + plugins at build time)
  → PHP 8.5 parser (src/main/java/pl/codeloom/php/parser/zend, reused)
  → Whole-program analyzer (closed-world classes, functions, constants, __destruct reachability, type inference)
  → JVM bytecode emitter (.class files via JDK 25 java.lang.classfile)
  → Reused Java PHP runtime & built-ins (PhpArray, byte strings, FFM PCRE2/libcurl/libc, JDBC mysqli, FastCgiMain)
  → GraalVM Native Image + PGO (native-image --pgo-instrument → training walk → native-image --pgo)
  → Single native FastCGI binary (zero Truffle PE, zero libgraal, zero warmup)
```

### Reuse vs. replacement inventory

| Layer | Status | Details |
|---|---|---|
| **Parser (`parser/zend`, D14)** | **Reuse as-is** | Exact php-src 8.5.12 Bison/re2c parser emitting `zend_ast`. |
| **Core values (`PhpArray`, `PhpRef`, byte strings)** | **Reuse & decouple** | Keep packed/hash storage strategies and CoW holder counting; strip Truffle DSL/boundary annotations and `TruffleString` node wrappers where direct `byte[]` helpers are leaner. |
| **Built-ins & native bindings (`builtins/`, `ffi/`, JDBC)** | **Reuse & decouple** | Keep the ~570+ WordPress/WooCommerce built-ins, FFM PCRE2/libcurl/libc, JDBC `mysqli`, `serialize`/`unserialize`, `ext/filter`, `DateTime`, `SPL`. Expose direct typed static Java entry points instead of `BuiltinRootNode` + `Object[]` boxing. |
| **Server SAPI (`FastCgiMain`, `HttpMain`)** | **Reuse** | Replace Truffle `Engine`/`Context` lifecycle with a lightweight per-request `PhpRequestContext` object. |
| **Execution engine (`bytecode/`, `nodes/`, `PhpLanguage`)** | **Replace** | Replaced by the ahead-of-time whole-program analyzer + JVM bytecode emitter. |
| **`eval()` fallback** | **Optional / deferred** | Keep a minimal non-JIT interpreter only if plugins require runtime `eval()`; strict closed-world mode can reject it initially. |

### Key compilation rules (why this avoids `ceiling`'s walls)

1. **Direct static calls & devirtualization:**
   Functions declared unconditionally once in the closed world compile to plain `invokestatic` calls. Only
   conditionally declared / pluggable functions (`if (!function_exists(...))`) go through a per-request function slot
   array. Known methods compile to `invokevirtual`/`invokespecial`—eliminating `pe-round4-diagnosis.md`'s per-context
   lookup overhead and `ceiling.md`'s 26.5% `BuiltinRootNode.call` share.
2. **Direct Java fields for declared PHP properties:**
   Each PHP class compiles to a real Java class with fields for its declared properties (keeping a lazily allocated
   dynamic property map only when undeclared properties, `#[\AllowDynamicProperties]`, or `__get`/`__set` exist).
   This directly attacks `new WP_Post` (7.6× slower than PHP on `ceiling`).
3. **Closed-world `D17` destructor elision:**
   Scan every class in the closed world for `__destruct()`. Typically <2% of WordPress/WooCommerce classes define one
   (e.g., `WP_HTML_Token`). Values, locals, and typed fields proven unable to reach any destructible class skip
   refcounting completely, while objects/containers that *can* hold destructible instances keep exact `D17` refcounting
   and release cascades.
4. **Compact cold code + PGO-guided hot inlining:**
   Cold/run-once files (Jetpack classmap, `states.php`, admin setup, ~80% of WP functions) compile to small, un-inlined
   AOT methods instead of 150–500 KB Truffle PE graphs. PGO profiles a real WordPress/WooCommerce walk and inlines only
   genuinely hot paths.
5. **Pre-compiled file table for `include`/`require`:**
   Every `.php` file in the site compiles to a class registered in a build-time `Map<String, CompiledFile>` table.
   Dynamic `include $template` dispatches directly to compiled bytecode with zero parsing and zero `stat()` syscalls.
6. **Selective local-scope materialization:**
   Only functions statically detected to call `extract()`, `compact()`, `get_defined_vars()`, or `$$var` allocate a
   local variable map; all other functions use plain JVM locals (unboxed `long`/`double`/`boolean` where PHP type
   hints or whole-program inference prove the type).
7. **Per-request isolation without Truffle `Context`:**
   Each HTTP/FastCGI request passes a `PhpRequestContext` (superglobals, mutable PHP `static` variables, dynamic
   `define()` table, pluggable function slots, output buffers, DB connections, pending destructors). Compile-time
   unconditional constants (`ABSPATH`, `WPINC`, etc.) fold directly when invariant across routes.
8. **Build-time Reflection metadata:**
   Generate static descriptors for `ReflectionClass`/`ReflectionMethod`/`ReflectionFunction` (needed by WooCommerce's
   `League\Container` and REST schema) at build time rather than relying on JVM reflection in Native Image.

---

## 3. Gate-driven execution plan

Follow [D4](decisions.md): run the cheapest decisive proof **before** writing a compiler.

### Gate A0: 1-day "speed of light" hand-translated spike (zero compiler code)
- **What to build:** Hand-translate three representative WordPress kernels directly into plain `.java` calling this
  repo's existing `PhpArray`, byte strings, and `Refcount` (no Truffle):
  1. `hooks` (`WP_Hook::apply_filters` + `bench/gate1/kernels.php` callbacks — dynamic callables + CoW arrays);
  2. `_wp_array_get` (`ceiling`'s `hot.php` `aget` — nested associative array reads);
  3. `new WP_Post` (`ceiling`'s `hot.php` `post` — object allocation + property population).
- **How to measure:** Build three variants and run interleaved on pinned cores against **PHP 8.5.12** (OPcache +
  tracing JIT):
  1. Oracle GraalVM Native Image with `--pgo` and `--gc=G1` (and `--gc=serial -Xms256m` for CE baseline);
  2. Plain HotSpot JVM (no Truffle) for comparison;
  3. PHP 8.5.12 CLI.
  Record steady-state ns/op, `memory.peak`, and native binary size.
- **Go criterion:** Hand-translated AOT + PGO reaches **≥ 2.0× faster than PHP 8.5** on `hooks` and `_wp_array_get`
  (and clearly beats PHP on `new WP_Post`) with low RSS.
- **No-Go signal:** If optimal hand-written Java calling `PhpArray` lands at **~0.9–1.3× PHP 8.5**, the remaining
  floor is Java `Object` boxing + `PhpArray` CoW + tracing GC vs. Zend's C (the PeachPie floor), not Truffle PE.
  Building a full `.php` → `.class` compiler won't beat hand-written Java—rethink the value/array layout (or
  non-JVM target) before proceeding.

### Gate A1: Minimal `.php` → `.class` compiler + Gate 1 kernels (1–2 weeks)
- **Scope:** Standalone `aot` module translating `parser/zend` ASTs to `.class` files via JDK 25's
  `java.lang.classfile` API: functions, closures, classes, traits, references, exceptions, static vars, arrays,
  strings, and direct static calls to built-ins.
- **Benchmark:** Compile and run all 7 Gate 1 kernels (`hooks`, `escape`, `slugs`, `wpautop`, `wptexturize`, `blocks`,
  `kses`) + `K0` with Native Image + PGO against PHP 8.5 JIT.
- **Go criterion:** Exact checksum parity on every kernel; **≥ 1.8–2.0× PHP-code speedup** over PHP 8.5 JIT; peak
  memory within ~2× of PHP CLI.

### Gate A2: WordPress core closed-world build + `K8` render & FastCGI load (2–4 weeks)
- **Scope:** Full closed-world analyzer + emitter for WordPress 7.2-beta1 (~625k LOC, ~600 files): pluggable function
  slots, `include` file table, `extract()` frame fallback, dynamic properties, build-time `Reflection` tables, static
  `D17` destructor reachability (verified against `Refcount` census audit and `.phpt` destructor tests).
- **Measurements:**
  - Native Image build time, peak build RAM, and binary size;
  - `K8` hot front-page render time vs. PHP 8.5's `9.75 ms` and `85 MB` RAM;
  - `native.sh pages` (byte-identical parity against PHP 8.5) and `native.sh load` behind nginx (`FastCgiMain` vs.
    `php-fpm`).
- **Go criterion:** Native Image build stays practical (<10 min, ≤16 GB RAM); beats PHP 8.5 on `K8` render (`< 9.75 ms`)
  and served throughput; matches or beats `php-fpm` memory at 16–32 concurrent workers.

### Gate A3: Full WooCommerce + top plugins build & concurrent order load (4–8 weeks)
- **Scope:** Compile WordPress + WooCommerce 11.2.1 + Yoast, Elementor, Contact Form 7, Akismet (~1.5–2M LOC). Record
  PGO profile from `woo.sh walk` + a short `woo.sh load` warmup, rebuild with `--pgo`.
- **Measurements:** `woo.sh walk` parity (responses, logs, DB rows) and `woo.sh load` (1–32 concurrent buyers on 8
  cores) against `php-fpm 16`.
- **Go criterion:** Exceeds `php-fpm 16`'s **7.6 orders/s** with lower p50/p99 latency and ≤ `php-fpm` RAM at
  saturation, with zero order/stock/warning regressions.

### Gate A4: Production ergonomics (post-Gate A3)
- Hybrid fallback interpreter for `eval()` / runtime-added snippets / WP-CLI scripts;
- Reproducible container build pipeline (`wp-aot build /path/to/site → wp-server` binary + PGO profile reuse).

---

## 4. Risk register

| Risk | Why it matters | Early signal / mitigation |
|---|---|---|
| **The PeachPie floor (`Object` boxing + `PhpArray` CoW + GC)** | WordPress is weakly typed (`mixed` everywhere); untyped Java + tracing GC may only tie Zend's C `zval`/`zend_array`. | **Gate A0 catches this in 1 day** before writing any compiler. |
| **Native Image build time / RAM blowup** | 1.5–2M LOC of polymorphic generated bytecode can overwhelm `BigBang` points-to analysis (minutes, >16–32 GB RAM, 300+ MB binary). | **Gate A2** measures build scaling on WP core first; keep cold files lean, avoid monolithic megamorphic dispatch hubs, evaluate Native Image layered images. |
| **Oracle GraalVM dependency for PGO & G1 GC** | Native Image PGO and G1 GC are Oracle GraalVM features; Community Edition only has Serial GC (which stalled `native 16` in [woocommerce.md](woocommerce.md)). | Gate A0 benchmarks Oracle GraalVM (`--pgo --gc=G1`), CE (`--gc=serial -Xms`), and plain HotSpot side by side. |
| **Dynamic PHP escapes (`extract`, `$$var`, `eval`, conditional `class`/`function`)** | WordPress & WooCommerce rely on pluggable functions, dynamic `include $template`, and `extract()` in templates. | Static file table + pluggable slots + per-function `extract()` detection + optional fallback interpreter. |
| **`D17` destructor soundness under static elision** | Missing a path that can hold a destructible object causes silent late-destructor bugs (e.g., `WP_HTML_Processor`). | Conservative closed-world reachability check + verify on every build with existing `Census` audit (`-Dphp.rc.audit=true`) and `.phpt` suite. |
| **Amdahl ceiling on DB-bound routes** | Uncached WooCommerce spends 25–30% in MariaDB (~170 queries/page, [gate-0.md](gate-0.md)) and hits InnoDB row locks at 7.6 orders/s. | Pair AOT execution with pooled JDBC + prepared statements in `FastCgiMain`, and report CPU-bound vs. DB-bound routes separately. |

---

## 5. Gate A0 Status & Verdict (2026-11-24): **NO-GO**

Gate A0 was executed in `bench/aot-spike/` (implemented and adversarially reviewed with Codex `gpt-6.2-astra` via `hc`;
full tables, assumptions, and raw evidence in [bench/aot-spike/RESULTS.md](../bench/aot-spike/RESULTS.md) and
[bench/aot-spike/ASSUMPTIONS.md](../bench/aot-spike/ASSUMPTIONS.md)).

### Measured results (CPUs 14–21, 2 rounds each, all checksums exact)

| Engine | `hooks` (ms/op) | vs PHP 8.5 | `aget` (ns/op) | vs PHP 8.5 | `post` (ns/op) | vs PHP 8.5 | Peak RAM (`hooks / aget / post`, MiB) | Binary (MiB) |
|---|---:|---:|---:|---:|---:|---:|---|---:|
| **PHP 8.5.13 JIT** (Docker Linux) | **7.12** | 1.00× | **1,973** | 1.00× | **7,588** | 1.00× | `17.9 / 16.7 / 16.8` *(cgroup)* | — |
| **HotSpot + Graal JIT** (no Truffle, `-Xmx512m`) | **5.53** | **1.29×** | **1,561** | **1.26×** | **7,903** | **0.96×** | `609.6 / 126.8 / 409.5` | — |
| **HotSpot C2** (no Truffle, `-Xmx512m`) | **5.81** | **1.23×** | **1,686** | **1.17×** | **9,862** | **0.77×** | `393.4 / 57.8 / 355.7` | — |
| **Native Image PGO (`Serial`, `-march=native -O3`)** | **8.52** | **0.84×** | **3,172** | **0.62×** | **9,109** | **0.83×** | `57.7 / 7.9 / 55.6` | `5.64` |
| **Native Image PGO (`G1`, `-march=native -O3`)** | **8.80** | **0.81×** | **3,174** | **0.62×** | **9,565** | **0.79×** | `158.8 / 10.8 / 92.2` | `6.63` |

### Conclusion
- **NO-GO for Gate A1.** Oracle GraalVM Native Image + PGO (`-march=native -O3`, both `Serial` and `G1`) lost to
  PHP 8.5.13 tracing JIT on all three kernels (**0.62×–0.84×**). Even best-case hand-translated Java on HotSpot + Graal
  JIT (no Truffle) reached only **0.96×–1.29×** PHP 8.5.13—missing Gate A0's **≥ 2.0×** bar and far below the
  **2.4–3.0×** PHP-code speedup required by [gate-0.md](gate-0.md) for 2× WordPress/WooCommerce throughput.
- Three real PHP semantic bugs caught during adversarial review (`WP_Hook::$callbacks` CoW snapshot aliasing,
  `WP_Post` declared-field `PhpArray` ownership, and 2-arg `trim` mask forwarding) were fixed and verified before
  recording these numbers.
- **Next implication:** Neither Truffle PE (branch `ceiling`, [alternatives.md](alternatives.md)) nor Closed-World JVM Bytecode → GraalVM
  Native Image + PGO clears the speedup bar on WordPress's untyped PHP kernels. Beating `php-fpm` meaningfully on
  WordPress/WooCommerce requires non-JVM/non-JIT levers from [alternatives.md](alternatives.md) (post-bootstrap CoW
  `fork()` on `php-src`, surgical C/Rust WordPress subsystems inside `php-src`, in-process zero-copy object caching +
  DB write offloading, or block/fragment micro-caching).
