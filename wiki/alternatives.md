# Feasibility diagnostic and alternatives to beating PHP via JIT speed

Part of the [wiki](README.md). Assessed 2026-11-24 at `8113e1c7` (Bytecode DSL only per [D16](decisions.md), exact
`__destruct` refcounting on by default per [D17](decisions.md), Oracle GraalVM `25.4.4.1.1+1.1`).

## Question
Given the measurements in [gate-0.md](gate-0.md), [gate-1.md](gate-1.md), [woocommerce.md](woocommerce.md),
[gc.md](gc.md), [pe-round4-diagnosis.md](pe-round4-diagnosis.md) and [destructors-prototype.md](destructors-prototype.md):

1. Where does `HEAD` (`8113e1c7`) stand on the single most Graal-friendly non-regex WordPress kernel (`hooks` in
   multi-context `SHARED` mode) and on `WP_Query::get_posts`-sized functions with full `D17` refcounting active?
2. If beating PHP 8.5 JIT purely by JIT-compiling unmodified PHP opcodes ([D1](decisions.md)) is structurally uphill
   on both CPU and memory, what other architectural options can beat `php-fpm` on WordPress and WooCommerce?

## Summary
- **Pure JIT speed (`D1`):** Under [gate-0.md](gate-0.md), 2× WordPress/WooCommerce throughput requires the accelerable
  share (PHP code + Java built-ins) to run **2.4–3.0× faster than PHP 8.5 JIT** (3.2–4.8× for 2× response time). On
  `HEAD` (`8113e1c7`), `hooks` (`WP_Hook::apply_filters`, zero regex) runs at **14.93 ms** in multi-context `SHARED`
  mode (~0.48–0.76× PHP 8.5 JIT, 3.3–6.2× short of the 2×-throughput target), and full `D17` refcounting pushed the
  `WP_Query::get_posts` proxy (`large-function.php`) past HotSpot's 640 KB code-installation ceiling into permanent
  interpreter fallback.
- **Memory vs. `php-fpm`:** On WooCommerce, `php-fpm` uses ~151 MB shared OPcache + ~21 MB RssAnon per worker
  (~405 MB `memory.peak` at 16 workers), whereas Truffle PHP's single JVM carries ~1.9–2.8 GB `memory.peak` (even after
  PE round 4) with ~2.6 MB live heap (~8 MB with GC headroom) per in-flight request ([woocommerce.md](woocommerce.md),
  [gc.md](gc.md)). The memory crossover sits near **~140 concurrent active PHP workers**—well beyond where MariaDB hits
  InnoDB row-lock saturation (16 workers, ~450 lock waits/min).
- **Alternatives:** Attacking the non-opcode slices of a WordPress/WooCommerce request—**40–43% bootstrap**, **25–30%
  DB wait and InnoDB locks**, **4–5% redundant file stats**, and a handful of hot WordPress subsystems—offers
  **1.5–10×** gains without fighting PHP's full dynamic-semantics tax in a JIT.

---

## Diagnostic on `HEAD` (`8113e1c7`)

### 1. Gate 2 large-function IR wall (`CompilationTest`)
`mvn -o test -Dtest=CompilationTest#compilesWithoutFailure` (Oracle GraalVM `25.4.4.1.1+1.1`, 9 pass, **2 fail**):

| Target | Pre-D17 (`851bb6d`) | D17 prototype (`697e6bc`) | `HEAD` (`8113e1c7`, full D17) | Outcome |
|---|---|---|---|---|
| `large-function.php` (`Query::sql`, budget `52,000`) | `51,806` IR | `53,750` IR | **Tier 1 failed (4,129 ms):** `BailoutException: Code installation failed: code is too large` | Permanent fallback to interpreter |
| `query-builder.php` (`Query::sql`, 1/3 size, budget `24,000`) | `23,574` IR | `24,286` IR | **`27,248` IR after PE / `57,709` after Graal**, **`316 KB` code**, **`Inlined 0Y 25N`** | Over budget (+13.5%), zero inlining |

With full `D17` refcounting enabled, a method **one-third the size** of `WP_Query::get_posts` already consumes half of
HotSpot's 640 KB code-install limit and inlines 0 of its 25 callees; a full-sized `WP_Query::get_posts` proxy fails to
install altogether.

### 2. Gate 1 `hooks` (`WP_Hook::apply_filters`): single-context vs. `SHARED`
`bench/gate1/run.php hooks 400`, interleaved 2 rounds (`--contexts=1` vs. `--contexts=2` on one shared `Engine`),
Oracle GraalVM `25.4.4.1.1+1.1`, `-Dpolyglot.php.Parser=java`, quiet Windows host. Checksum `393035:a61726cd` on every
iteration (identical to PHP 8.5; `0 ns` native/PCRE2).

| Mode | Round 1 median | Round 2 median | Average (p10–p90) | Peak Working Set |
|---|---:|---:|---|---:|
| `--contexts=1` (single-context speculation) | 13.41 ms | 13.58 ms | **13.50 ms** (13.16–14.00) | 1,385–1,403 MB |
| `--contexts=2` (`ContextPolicy.SHARED`, server mode) | 15.01 ms | 14.85 ms | **14.93 ms** (14.63–17.54) | 1,513–1,530 MB |
| PHP 8.5 JIT (recorded, [gate-1.md](gate-1.md)) | — | — | **7.1 ms** (quiet) / **11.32 ms** (loaded) | ~31 MB |

*Docker Desktop was stopped during this run, so the PHP 8.5 row quotes the repo's recorded Linux container baselines from
the same machine.*

- **Multi-context `SHARED` penalty:** **+10.6%** (`13.50 → 14.93 ms`).
- **Gap to target:** `14.93 ms` is **~0.48×** quiet-host PHP 8.5 (2.1× slower) and **~0.76×** loaded-host PHP 8.5.
  Clearing [gate-0.md](gate-0.md)'s 2×-throughput bar (2.4–3.0× PHP 8.5) requires **~2.4–4.5 ms**.

### 3. Tier 2 code size and steady-state JFR profile (`hooks --contexts=2`)
Both hot dispatchers compiled cleanly to Tier 2 in `SHARED` mode with zero failures or invalidations:
- `WP_Hook::apply_filters`: Tier 2, `Inlined 8Y 3N`, `13,462` IR after PE / `28,136` after Graal, **`146 KB`** code.
- `apply_filters`: Tier 2, `Inlined 2Y 6N`, `13,093` IR / `29,371` after Graal, **`156 KB`** code.
- `WP_Hook::do_action`: Tier 2, `Inlined 8Y 3N`, `11,447` IR / `26,588` after Graal.

Steady-state JFR (`hooks --contexts=2`, 500 iterations, 6 s warmup delay, 8 s capture, exception events disabled):

| Hotspot category | Top JFR methods | CPU share |
|---|---|---:|
| Array hash / CoW / writes | `PhpArray$Hash.find` (8.2%), `hashGetLong` (3.1%), `put`+`putSlow` (3.8%), `countIn` (1.6%), `copy` (1.5%), `append`+`toHash`+`ArraySlice` (3.6%) | ~21.8% |
| References, type checks, properties, D17 release | `PhpRef.deref` (7.8%), `CheckParameter.slow`+`checkArgument` (6.1%), `PropNode.read`+`accessible`+`IncDecProp`+`UnsetPropNested` (7.7%), `Locals$Writes.releaseOld` (1.2%) | ~22.8% |
| Dynamic call / arg marshaling | `CallableCallNode.toValues(PhpArray)` (4.9%), `BuiltinRootNode.call` (1.2%) | ~6.1% |
| Strings, interpreter, map lookups | `Str.join`+`TStringOps`+`Interpolate` (6.4%), `CachedBytecodeNode.continueAt` (3.6%), `HashMap.computeIfAbsent` (2.9%), `ReadVar.read` (2.4%) | ~15.3% |

Steady-state allocation pressure: `byte[]` **40.9%**, `Object[]` **30.7%**, `PhpArray` **12.1%**, `TruffleString`
**6.7%**, boxed `java.lang.Long` **5.3%** (`Long.valueOf` in `WP_Hook::apply_filters`'s `Locals$Writes.writeLong` and
`ElementWriteNode.intKey`), `PhpRef` **3.8%**.

**Reading the profile:** Cleaning up fixable slow paths (`CheckParameter.slow`, `CallableCallNode.toValues`,
`HashMap.computeIfAbsent`, `PropNode` visibility checks, `Long` boxing, known-non-ref `PhpRef.deref`) can plausibly
bring `hooks` near PHP 8.5 parity (~7–9 ms). Because the profile is flat and `apply_filters` + `WP_Hook::apply_filters`
already consume ~300 KB of Tier 2 code, pushing `hooks` down to 2.5–4.5 ms—while keeping `WP_Query::get_posts`-sized
functions under 640 KB with `D17` enabled—is structurally unlikely.

---

## Alternatives to beating PHP via JIT execution speed

On a plugin-heavy WordPress/WooCommerce site, the repo's measurements show where wall time and server bottlenecks
actually sit: **~39–43% bootstrap** ([bootstrap-share.md](bootstrap-share.md)), **~25–30% MySQL wait and InnoDB row
locks** ([gate-0.md](gate-0.md), [woocommerce.md](woocommerce.md)), **~4–5% redundant file stats** (`~1,300` calls/page),
and concentrated hotspots in a small set of WordPress subsystems.

### 1. Post-bootstrap state snapshotting (CoW `fork()` or heap snapshot)
- **Target:** The 39–43% bootstrap share (42–59 ms on plugin-heavy pages; up to **1.6–2.3×** ceiling per
  [bootstrap-share.md](bootstrap-share.md)).
- **Mechanism:** Boot WordPress + plugins once through `wp_loaded` (and `rest_api_init` for REST), snapshot the live
  state per request class (frontend, wp-admin, REST), and start each request from that pristine snapshot.
- **Why it works without a new VM:** On Linux, a custom SAPI or extension around `php-src` can do copy-on-write `fork()`
  (or memory-arena snapshot/restore) directly on PHP 8.5—keeping Zend's native speed, ~20 MB/worker memory, and 100% C
  extension compatibility while skipping ~40% of every request.
- **Main risk:** Correctness—taint-tracking the ~1–3 ms of visitor-dependent reads (`$_COOKIE`, `$_GET`, current user)
  and invalidating snapshots on option/plugin mutations.

### 2. Attack the database and serialization wall (in-process zero-copy cache + write offloading)
- **Target:** The 25–30% DB wait (~167 queries/page), repeated `unserialize()`, and InnoDB lock contention (~450
  row-lock waits/min at 7.6 orders/s in [woocommerce.md](woocommerce.md)).
- **Mechanism:**
  - **In-process shared-memory object cache with pre-deserialized values:** Unlike Redis (which still pays TCP
    round-trips and `unserialize()` on every `get_option()`/`get_post_meta()`), storing immutable PHP arrays/objects in
    shared memory (via a `php-src` C/Rust extension, FrankenPHP, or JVM) drops cache reads to nanoseconds with zero
    deserialization.
  - **Connection pooling, prepared-statement caching, and query coalescing (singleflight)** at the driver/runtime layer.
  - **Write-behind for non-critical WooCommerce checkout writes:** Decoupling analytics lookup tables, session writes,
    order emails, and Action Scheduler work from the synchronous checkout transaction to relieve InnoDB lock contention.
- **Expected gain:** **1.5–2×** latency on uncached pages, and **2–5×** checkout throughput once database locks dominate.

### 3. Surgical native acceleration inside `php-src` (replace hot WordPress subsystems, not PHP)
- **Target:** The ~300,000 tiny built-in/PHP calls per page and fat WordPress functions measured in
  [gate-0.md](gate-0.md) and [gate-1.md](gate-1.md).
- **Mechanism:** Keep PHP 8.5 + OPcache and ship a native C/Rust PHP extension (plus drop-in shims) replacing only
  WordPress's hottest pure-PHP subsystems:
  - Native **`WP_Hook`** (`apply_filters`/`do_action` without PHP array CoW copies and `call_user_func_array`).
  - Native **`WP_Theme_JSON`**, **`WP_Block_Parser`**, **`WP_HTML_Tag_Processor`**, **`wp_kses`**, **`wpautop`**.
  - **Shared immutable read-only maps** for Composer/Jetpack classmaps (avoiding rebuilding 26k-entry PHP arrays per
    request) and parsed `theme.json`.
  - **Virtual stat cache** removing ~1,300 `file_exists`/`is_readable` syscalls per page (4–5% wall time).
- **Expected gain:** **1.5–2.5×** on CPU-heavy pages with zero memory regression and 100% PHP plugin compatibility.

### 4. Closed-world / store-specific AOT compilation (the KPHP / Hack path)
- **Target:** The exact-semantics tax ([D17](decisions.md), [D18](decisions.md)) responsible for `Query::sql`'s IR
  explosion and `WP_Hook`'s runtime guards.
- **Mechanism:** Treat a production WooCommerce site (WordPress core + its fixed plugin set) as a closed-world build
  artifact. Whole-program static analysis resolves classes, constants, `include` graphs, which classes actually define
  `__destruct`, and which variables can be aliased by `&$x`, then compiles ahead-of-time (to C++/native or statically
  specialized JVM bytecode → GraalVM Native Image + PGO, see [aot-plan.md](aot-plan.md)) with refcount/CoW checks elided where provably unneeded and hook chains pre-wired.
- **Tradeoff:** Installing or updating plugins requires a rebuild step (fits CI-deployed / managed WooCommerce hosting,
  not ad-hoc shared hosting).

### 5. Dependency-tracked block and fragment micro-caching
- **Target:** Rendered HTML/JSON generation (blocks, menus, `theme.json` CSS, product cards, category grids—often 50%+
  of PHP execution time).
- **Mechanism:** Even on logged-in or active-cart WooCommerce requests where full-page caching is bypassed, 80%+ of the
  rendered fragments are identical across visitors. Cache at the block, template-part, `WP_Query`, or REST-field
  boundary in memory with automatic tag invalidation on DB writes (`wp_insert_post`, `update_option`, stock changes).
- **Expected gain:** **3–10×** on real WooCommerce shop/product/category traffic with active carts.

### 6. Non-JIT angles specific to this repo's Truffle/JVM runtime
If continuing on the Truffle/JVM runtime rather than pivoting to `php-src` + C/Rust:
- **Opt-in closed-world specialization:** Relax `D17`/`D18` for known store builds (eliding refcounting on classes
  without destructors, folding store constants, specializing fixed plugin call sites) so large methods stay compilable.
- **Multi-tenant density (many small sites in one `Engine`):** While 1 site per JVM loses badly on RAM vs. `php-fpm`,
  sharing one ~1–2 GB compiled WordPress core `Engine` across **50–100 low-traffic tenants** in isolated polyglot
  `Context`s flips the memory math in Truffle's favor.
- **Single-binary JVM platform:** Native HikariCP JDBC pooling, Caffeine in-process L1 object cache, background job
  queues, and JFR/Micrometer observability in one process—competing on operational architecture and DB/cache efficiency
  even if pure PHP opcode speed targets ~1× parity.

---

### Comparison

| Approach | Primary bottleneck targeted | Realistic WooCommerce gain | Memory vs. `php-fpm` | Compatibility risk |
|---|---|---:|---|---|
| **Post-bootstrap CoW `fork()` on `php-src`** | 40% bootstrap | **1.5–2.0×** | Equal or lower | Medium (visitor-state leaks) |
| **In-process zero-copy cache + DB/write opt** | 30% DB + InnoDB locks | **1.5–5×** (under load) | Slightly higher | Low–Medium (invalidation) |
| **Surgical C/Rust WP extension on `php-src`** | Hot WP functions + stats | **1.5–2.5×** | Equal | Very low (100% PHP) |
| **Closed-world AOT store compiler** | Dynamic PHP + IR bloat | **2–4×** | Much lower | High (build step required) |
| **Dependency-tracked block/fragment cache** | Rendering PHP | **3–10×** | Low–Medium | Medium (dynamic plugins) |
| **Pure JIT speed on unmodified PHP (`D1`)** | PHP opcodes | **~0.8–1.3×** (after heavy tuning) | **3–6× worse** | Very high (`.phpt` long tail) |

---

## Reproduce (`HEAD` diagnostic)
```powershell
$env:JAVA_HOME = "$HOME\.jdks\graalvm-25.4.4.1.1+1.1"
mvn -o test-compile
mvn -o test "-Dtest=CompilationTest#compilesWithoutFailure"

$java = "$HOME\.jdks\graalvm-25.4.4.1.1+1.1\bin\java.exe"
$opts = @("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow",
          "-Dpolyglot.php.Debug=true", "-Dpolyglot.php.Parser=java", "-Djava.net.preferIPv6Addresses=system")

# Single-context vs. multi-context SHARED (run interleaved)
& $java @opts -cp "target\classes;target\lib\*" pl.codeloom.php.launcher.PhpMain --contexts=1 bench/gate1/run.php hooks 400
& $java @opts -cp "target\classes;target\lib\*" pl.codeloom.php.launcher.PhpMain --contexts=2 bench/gate1/run.php hooks 400

# Steady-state JFR CPU & allocation profile (exceptions off)
& $java @opts "-XX:StartFlightRecording=filename=hooks-shared.jfr,delay=6s,duration=8s,jdk.ExecutionSample#period=1ms,jdk.JavaExceptionThrow#enabled=false,jdk.JavaErrorThrow#enabled=false" `
  -cp "target\classes;target\lib\*" pl.codeloom.php.launcher.PhpMain --contexts=2 bench/gate1/run.php hooks 500
```
