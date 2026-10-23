# Plan: WordPress on GraalVM via a Truffle PHP

Part of the [wiki](README.md). The retro that led here is in [retro.md](retro.md); decisions are logged in [decisions.md](decisions.md).

## 2. The goal (decided 2026-10-22): Graal compiles PHP

**The point of the project is GraalVM making PHP code itself faster.** That rules out two earlier ideas:
- **Embedding libphp via FFM** (the old Track A): PHP still runs in the C engine, and Java only hosts HTTP.
- **php.wasm on GraalWasm** (the old Track B): Graal compiles the PHP *interpreter*, not the PHP program. That's two levels of interpretation, and Graal can't specialize the user's code through them. Running php-src as LLVM bitcode on Sulong has the same problem.

Only a **PHP implementation written as a Truffle language** puts PHP code under Graal's partial evaluation: inlining across calls, type specialization, escape analysis and deoptimization. A PHP-to-JVM-bytecode compiler (JPHP/Quercus style) is also optimized by Graal as the JIT. It loses Truffle's runtime type specialization and speculation, though, and every fast dynamic language on GraalVM (TruffleRuby, GraalJS, GraalPy) is a Truffle language. So: **Truffle.**

Campfire was an app port. This is a **language-runtime project** whose acceptance test is "unmodified WordPress and its plugins run, and run faster than PHP 8.4 with OPcache JIT".

### What the precedents say about the speedup on offer

| Precedent | What happened | Lesson |
|---|---|---|
| **graalphp** (ETH thesis, 2020, Truffle) | Up to 8.6× faster than PHP 7 on Benchmarks Game programs, but only ints, floats, arrays, functions and loops (~4,000 LOC) | Truffle's peak speed on tight numeric code is proven. WordPress isn't that code. |
| **HHVM** (Facebook, JIT for PHP) | Its team reported +18.7% on WordPress against PHP 7. Independent tests were mixed, and PHP 7.2 was faster in Kinsta's tests. HHVM 4.0 (2019) dropped PHP to become Hack-only. | The gap on WordPress was small, PHP 7/8 closed it, and the compatibility burden killed it. |
| **PeachPie** (PHP → .NET IL) | Runs WordPress and has been maintained for years (1.1.x has a PHP 8.4 parser), yet its README still says it's "not fully compatible" | The closest precedent for scope: the runtime library's long tail is what takes years. |
| **JPHP / Quercus** | Stuck at partial 7.x and 5.4 | Same lesson: the long tail stalls projects. |
| **TruffleRuby / GraalPy** | Both moved C extensions from Sulong to native execution with a C-API bridge | The extension story is a bridge to native code, not reimplementing it in Java or running it on Sulong. |

**Takeaway:** a Truffle PHP will win big on CPU-bound userland PHP. Whether *WordPress* gets much faster depends on how much of a request is userland PHP rather than C internals (PCRE, string functions, mysqlnd) and I/O. This has to be **measured before writing the runtime**. It is the sd.cpp "is the FLOPS near the ceiling?" lesson: know the ceiling first.

---

## 3. Architecture of a state-of-the-art Truffle PHP

| Layer | Recommendation | Why / reference |
|---|---|---|
| **Parser** | Port php-src's own grammar (`Zend/zend_language_parser.y` and the re2c scanner) to a Java parser. Differential-test its AST against `nikic/php-ast` output on a corpus: WordPress core plus the top 50 plugins. | The grammar is the spec. php-ast gives golden vectors for free. |
| **Interpreter** | Truffle **Bytecode DSL** (check its current status), not a plain AST interpreter | WordPress has a flat profile: thousands of functions run a few times per request. Interpreter speed and warmup decide the result as much as peak JIT. GraalPy moved to the Bytecode DSL for this reason. |
| **Strings** | `TruffleString` in a byte encoding. PHP strings are byte strings, so never use `java.lang.String` for PHP values. | JPHP's UTF-16 strings were a known compatibility bug. |
| **Arrays** | Ordered hash maps with **storage strategies** (packed int / long / double / object vectors, falling back to a hash) and **copy-on-write** value semantics via a shared flag, not refcounts | Storage strategies follow TruffleRuby and GraalPy. Arrays are WordPress's main data structure (hooks, options, query vars). |
| **Refcount semantics** | Emulate copy-on-write. Run `__destruct` at scope exit or `unset` where it can be determined, and otherwise at request end. List the difference as a deliberate difference. | The JVM has no refcounts. WordPress rarely depends on destructor timing. Measure this with the plugin matrix. |
| **Request model** | One Truffle `Context` per request (PHP's shared-nothing semantics) on **one shared `Engine`**, so compiled code survives across requests. Pool or reset contexts if creating them is too slow. | This is the structural advantage over php-fpm: the JIT never resets. The AST must be **context-independent** (no context values baked into nodes) from day one, or code sharing is impossible later. |
| **include/require** | Parse and compile once per (path, mtime) per Engine | The OPcache analogue |
| **Calls/hooks** | Polymorphic inline caches on dynamic calls (`call_user_func_array`, string and array callables, `__call`) | `apply_filters`/`do_action` dispatch is WordPress's hottest dynamic pattern, and the place where Truffle should beat PHP's JIT |
| **Standard library** | Java for core and simple functions. Where the semantics are a C library's, bind that library via FFM: **PCRE2** (`preg_*`; `java.util.regex` is not compatible), **libxml2** (dom/xml), libgd/imagick, **ICU4J** for intl. Use JDK libraries where they match: hash, openssl→JCA, json (custom, for PHP's exact output), curl→`java.net.http`, date→`java.time` + tzdb. | Campfire rule: use ready libraries unless compatibility forces your own code. Here compatibility often *is* the forcing case, so bind the very library PHP uses. Check whether GraalVM's TRegex can get a PCRE flavor: it would make `preg_*` JIT-able. |
| **mysqli / PDO** | mysqli and PDO-MySQL on top of the MySQL JDBC driver (Agroal pool) | Campfire's jOOQ lesson: measure the per-query overhead early. |
| **Native C extensions (long tail)** | Defer. Later, a Zend-API bridge like GraalPy's C-API, running native code | Extensions use HashTable/zval internals through macros, so this is harder than CPython's API. Only build it if the plugin matrix shows it's needed. |
| **Borrow before writing** | Mine JPHP's Java runtime library and PeachPie's C# runtime library (`Pchp.Library`) for function semantics and edge cases (check both licenses), graalphp for the Truffle skeleton, and TruffleRuby/GraalPy for strategies and COW | sd.cpp lesson: cherry-picking from existing work beat solo engineering every time. |
| **Distribution** | Truffle standalone: a native-image launcher with the Truffle runtime (fast startup, JIT for PHP at run time). Also a JVM build for profiling. | Campfire measured both variants. Here the native standalone is the normal way to ship a Truffle language. |

---

### Size estimate (measured 2026-10-22, non-blank lines from shallow clones)

**What gets reimplemented:**
- **php-src's C code:** 1.86M lines, of which:
  - about 1.05M are bundled libraries: lexbor 607k, libgd 145k, timelib 104k, pcre2 98k, libmbfl 59k, libmagic 15k, uriparser 13k;
  - 110k are the generated VM (`zend_vm_execute.h`);
  - **about 700k are PHP's own code**, including about 130k for the Zend engine.
- **Built-in functions:** 1,923 in php-src's stubs.
- **What WordPress core calls:** 625k lines of PHP make 31.8k calls to **677 distinct built-ins**. 107 of those are `sodium_*`, which WordPress ships a pure-PHP polyfill for, so **about 570 are needed**, 334 of them from ext/standard. Classes are on top of that: DOM, DateTime, SPL, Reflection and mysqli objects.
- **The spec:** 23,640 `.phpt` tests (Zend 5,707; ext/standard 4,046).

**Comparable implementations:**

| Implementation | Lines | Notes |
|---|---:|---|
| PeachPie (C#) | 231k | compiler 82k, runtime 36k, library 76k, xml/graphics/net/mysql/pdo ~25k; runs WordPress |
| JPHP (Java) | 174k | runtime 62k, zend-ext 46k, core 21k; partial PHP 7.x |
| graalphp (Java/Truffle) | 6.7k language + 61k parser | the parser comes from Eclipse PDT; ints, floats, arrays and functions only |
| TruffleRuby (Java) | 129k Java + 32k Ruby core + C-ext support | a mature Truffle language after more than a decade with a team |

**Estimate for a Truffle PHP that runs WordPress and the top plugins: about 150–250k lines of Java.**

| Part | Lines |
|---|---:|
| Parser | 10–20k |
| Truffle core: nodes, values, COW arrays, classes/traits/enums, references, generators, exceptions, include/eval, type juggling | 50–80k |
| Runtime library: ~570 functions plus the built-in classes | 70–120k |
| Native bindings (PCRE2, libxml2, gd), HTTP SAPI, sessions and uploads | 15–25k |
| Test and benchmark harness | 10–20k |

**By gate:**
- **Gate 1** needs about 15–25k lines.
- **Gate 2** (WordPress boots and renders) needs about 80–120k.
- **Plugins** take it to the full range.

The hard part is semantics, not line count: `sprintf`, comparison and type juggling, and array-function edge cases are all pinned down by the `.phpt` tests.

## 4. Plan with go/no-go gates

The HHVM lesson is that compatibility work can swallow a project whose speed advantage then disappears. So each phase ends in a **measured gate**, and the cheapest decisive experiments come first.

### Gate 0: know the ceiling (1–2 days, no PHP runtime code)
- Oracle: `wordpress:php8.4-fpm` + nginx + MariaDB, seeded like a real site, with the top plugins active.
- Profile representative routes (home, single post, archive, search, wp-admin dashboard, REST posts, WooCommerce product and cart) with `perf` and SPX/excimer. Split each request's time into:
  1. userland PHP opcodes;
  2. internal C functions (PCRE, string/array builtins, mysqlnd, json);
  3. I/O and DB wait;
  4. startup (autoload, include, `wp-settings.php` bootstrap).
- Also measure PHP 8.4 with **OPcache JIT on vs off** on the same routes.
- **Output:** an Amdahl ceiling per route. If userland is, say, 30% of a request, even an infinitely fast Graal gives at most 1.43× on that route.
- **Go if** userland plus the builtins Graal could inline (string and array functions written in Java) make up enough of the time for the target, e.g. ≥2× on rendered pages. Agree the target number now.

### Gate 1: does Graal beat PHP 8.4 JIT on real WordPress code? (about 1–2 weeks)
- Build only the core: strings, COW arrays with strategies, functions, closures, basic classes and static calls, references, and include.
- Extract **pure-PHP WordPress hot functions** with stubs: `apply_filters`/`do_action` chains, `wp_kses`, `esc_html`/`esc_attr`, `sanitize_title`, `wpautop`, `parse_blocks`/`do_blocks`, `wp_parse_args`, `shortcode_parse_atts`, plus a `WP_Hook` dispatch micro-benchmark.
- Run them against PHP 8.4 (JIT off and tracing JIT) with interleaved runs, pinned cores, std, and warmup curves. Report steady state *and* time-to-steady-state.
- **Go if** steady state is well above PHP 8.4 JIT (for example ≥2×) *and* warmup is acceptable for a server (seconds, not minutes). **Stop or rethink** if not. That would be found after weeks, not years.

### Gate 2: WordPress boots and renders (weeks)
- The scorecard runs from the first day, pinned to commit and build like sd.cpp's `bench.py`:
  - **php-src `.phpt` pass %**, per directory (Zend/, ext/standard, ext/pcre, …), next to the oracle's own pass rate;
  - **parser AST parity** against php-ast on the corpus;
  - **differential fuzzing**: generated PHP programs run on both engines, with the output compared.
- Milestones: `wp-load.php` in CLI mode → the installer → the home page over HTTP (a FastCGI SAPI behind nginx, as php-fpm; D11) → wp-admin login.
- Database: mysqli on JDBC first; SQLite integration as a fallback for early boots.

### Gate 3: drop-in parity (the campfire harness)
- WordPress PHPUnit (`wordpress-develop`) pass %.
- Page parity: a semantic DOM diff against php-fpm, with nonces and timestamps masked.
- Write parity: identical forms and REST calls against both, with the DB rows diffed.
- Browser flows (Playwright): install, post with media, plugin install from a zip and from wordpress.org, cron.
- Plugin matrix: the top 20 → 50 plugins. A **fatal-error log scan** is part of the pass criterion.

### Gate 4: performance and publication
- Profile with Truffle tools (IGV graphs, `--engine.TraceCompilation`, `--cpusampler`) plus JFR/async-profiler for the Java runtime.
- Watch deopt loops, megamorphic call sites in hooks, and boundary copies at FFM (PCRE, libxml).
- **No perf loss** per change, interleaved against the previous image, with one tagged image per merge.
- Variants: JVM and native standalone. Report steady state, warmup curve, memory and the compile-thread CPU share. Compare against php-fpm 8.4 with JIT on and off, and FrankenPHP.
- README: performance second, then "where we fall behind" (memory, warmup, failing plugins), the `.phpt` %, the plugin table and the deliberate differences. A cloud run (c7a/c8a, x86).

### Working with agents (from the retro)
- A runtime with tens of thousands of `.phpt` tests is the ideal agent workload: the tests are the spec. Partition forks by **directory of tests** (Zend/ core, ext/standard strings, arrays, pcre, json, date, mysqli, dom).
  - Each fork gets its own worktree, a turn budget, "commit before running out", and "port from JPHP/PeachPie/php-src C before inventing".
  - The main agent merges, reruns the whole scorecard, and tags an image.
- Track the scorecard per commit like `bench.py`, so regressions in `.phpt` % show up as clearly as speed regressions.
- Cross-model review (Codex CLI + Opus) of the core design decisions before Gate 1 code: value model, COW, context sharing, and the Bytecode DSL choice.
- Build php-src (the oracle and the PHP side of differential tests) only in pinned Linux containers. Keep scripts in git.

---

## 5. Risk register

| Risk | Why it matters | Mitigation / early signal |
|---|---|---|
| **The Amdahl ceiling**: WordPress time sits in C builtins and I/O | Caps the speedup however good Graal is | Gate 0 measures it before any runtime code |
| **Warmup and a flat profile** | Thousands of functions run once per request; Truffle needs hot code | Bytecode DSL, a shared Engine, warmup curves at Gate 1. Check whether Truffle's persisted-profile or AOT-for-languages features apply |
| **Memory** | Campfire's JVM used 400–650 MB against Rust's ~20–130 MB; Truffle adds AST and compiled code | Report it honestly; test heap caps early |
| **Semantic long tail** | References, copy-on-write, destructors, `$$var`, `extract`, `compact`, `eval`, `create_function` remnants, `__get`/`__set`, `ArrayAccess`, generators, `static` vars, error/warning text and order | `.phpt` scorecard plus differential fuzzing; deliberate-differences list |
| **PCRE semantics** | `preg_*` is everywhere in WordPress (kses, shortcodes, blocks) | PCRE2 via FFM first; a TRegex PCRE flavor as a later speed project |
| **Plugins with native extensions** | imagick, redis, memcached and others | Defer to a Zend-API bridge; measure demand with the plugin matrix |
| **Context-per-request cost** | Must stay ≪ 1 ms or it eats the win | Pool and reset contexts; measure at Gate 2 |
| **The HHVM ending**: PHP keeps getting faster | PHP's JIT improves each release | Benchmark against the newest PHP release every time; publish the deltas honestly |
| **Benchmark noise / wrong "done"** | Both happened in the earlier projects | Quiet host, pinning, governor, build pinning; never accept "200 OK" without checking content, DB rows and logs |

---

## 6. First concrete steps
1. Gate 0: the oracle Compose stack with seeded content and the top plugins; `perf` + SPX profiles per route; JIT on/off numbers; the Amdahl table → **agree the speedup target**.
2. Repo skeleton: Truffle language project (Maven, GraalVM SDK), `issues/`, `docs/decisions.md`, scorecard runner (`.phpt` + php-ast parity + benchmark), all pinned to commit and build.
3. Gate 1 spike: the minimal core, the extracted WordPress hot functions, and an interleaved benchmark against PHP 8.4 JIT → **go/no-go**.

## Sources
- [graalphp](https://github.com/abertschi/graalphp); [Phoronix on GraalPHP](https://www.phoronix.com/news/GraalPHP-GraalVM-PHP)
- [WP Tavern: HHVM 18.7% faster than PHP 7 on WordPress](https://wptavern.com/hhvm-demonstrated-to-be-18-7-faster-than-php-7-on-a-wordpress-workload); [Kinsta on HHVM](https://kinsta.com/blog/hhvm-wordpress/); [Phoronix: HHVM 4.0 drops PHP](https://www.phoronix.com/news/HHVM-4.0-Released); [Upsun: HHVM into the sunset](https://developer.upsun.com/posts/releases/hhvm-deploys-off-into-the-sunset)
- [PeachPie](https://github.com/peachpiecompiler/peachpie/releases); [PeachPie blog](https://peachpie.io/blog/category/version-update/)
- [TruffleRuby releases (C extensions native since 24.0)](https://github.com/oracle/truffleruby/releases); [TruffleRuby cexts doc](https://github.com/oracle/truffleruby/blob/master/doc/contributor/cexts.md); [What's new in GraalVM languages (GraalPy native C API)](https://medium.com/graalvm/161527df3d76)
- [GraalWasm docs](https://www.graalvm.org/webassembly/docs/); [Playground: compiling PHP](https://developer.wordpress.org/playground/developers/architecture/wasm-php-compiling/)
- Sources cited in the original claude.ai conversation (JPHP, Quercus, WasmLabs, WP hosting handbook)
