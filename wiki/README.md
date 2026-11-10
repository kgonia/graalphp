# Wiki: WordPress on GraalVM (Truffle PHP)

**Goal:** GraalVM makes PHP code itself faster. WordPress and its plugins run unmodified on a PHP implementation written as a Truffle language, and beat PHP 8.4 with OPcache JIT. See [decisions.md](decisions.md), D1.

## Pages

| Page | What's in it |
|---|---|
| [retro.md](retro.md) | Lessons from once-campfire-java and stable-diffusion.cpp, and the standing rules for this project |
| [plan.md](plan.md) | Goal, precedents (graalphp, HHVM, PeachPie, TruffleRuby), architecture, size estimate, gates 0–4, risks |
| [checks.md](checks.md) | One entry point for a build's checks (bench/check): levels L0-L2, builds by commit, the host's preflight, one results store, the report against the base |
| [decisions.md](decisions.md) | Numbered decisions with the reason for each |
| [graalphp-assessment.md](graalphp-assessment.md) | Why graalphp is a reference, not a base to fork |
| [gate-1.md](gate-1.md) | Gate 1 spec, method, go/no-go criteria and status log |
| [gate-2.md](gate-2.md) | Gate 2 probe: syntax and built-in gaps of WordPress core |
| [woocommerce.md](woocommerce.md) | WooCommerce on Truffle PHP against PHP 8.5: what broke and its fixes, a load test of concurrent orders (one JVM, the native image, php-fpm), and where the JVM's memory goes under load |
| [beat-php.md](beat-php.md) | The overnight proof of 2026-11-10: can the engine beat native PHP on WordPress? No: a hot render of the front page is 1.94× PHP's in the engine's best case (one context kept alive, 2 GB heap) and 2.1× as served (2.4–2.5× when the measured requests end in `exit`: issue 103, found by it). The K8 experiment (the render loop inside a request and from the command line), the warm-up, the machine's two speeds, the collector, where a render's time goes, memory, and what beating PHP would take |
| [ceiling.md](ceiling.md) | The ceiling experiment of 2026-11-10: is the gap to PHP ours or the price of PHP's semantics? The kernels reach PHP with the semantics exact (`_wp_array_get` 1.94× → 1.13×, 47 elementary operations at a geometric mean of 0.66×); the page does not follow (1.89× → 1.86×): a render is half compiled code and half runtime calls with no part above 5.3%, and its time follows the amount of last-tier machine code (huge pages and a quarter of the inlining budget: 1.65×; G1 in place of ParallelGC: 2.08×). Verdict: parity not shown in either direction. The method, what was tried and dropped, and the tools (`ops.php`, `hot.php`, `bench/code-size`, `k8_compcmp.py`, `k8-jfr.sh`, `JfrSplit.java`) |
| [bootstrap-share.md](bootstrap-share.md) | How much of a WordPress request on PHP 8.5 is bootstrap, what of it depends on the request, and what a post-bootstrap snapshot could gain |
| [tla-cow.md](tla-cow.md) | TLA+ model of copy-on-write arrays and PHP references against php-src, with its findings |
| [tla-curl.md](tla-curl.md) | TLA+ model of the curl handles' lifecycle (Cleaner, multi, share, threads), with its findings |
| [tla-engine.md](tla-engine.md) | Per-request state in the shared engine: inventory, TLA+ model, findings |
| [native-calls.md](native-calls.md) | Native calls (PCRE2, libcurl, libc) through one FFM helper, on the JVM and in a native image: the contract for adding a binding, and why it is fast on both |
| [named-arguments.md](named-arguments.md) | Named arguments (issue 017, option 1): php-src's rules exactly (binding, holes and their fake frame, variadic extras, unpacking, callables, built-ins, the order of errors), what GraalPy's keyword binding teaches, and the design: binding per call site and callee, holes filled by a lazily made defaults root, the fast path unchanged |
| [partial-evaluation.md](partial-evaluation.md) | What Truffle compiles of our interpreter: the partial-evaluation rules mapped onto our code, the diagnosis (`tests/gate2/compile-diag.sh`, `CompilationTest` in `mvn test`) and how to read it, the failures and explosions found on WordPress and their fixes |
| [pe-round4-diagnosis.md](pe-round4-diagnosis.md) | Partial evaluation round 4's diagnosis on WooCommerce: what the largest compilations are made of, why compilation never settles (the queue, tier 2, splitting and the nodes that ask for it), libgraal's heap per isolate and a cap on it, what #19 (round 3) and #20 did to WooCommerce, and round 4's targets ranked |
| [pe-round4.md](pe-round4.md) | Partial evaluation round 4: run-once code not compiled, method dispatch per method found, `defined()` compiled in place as php-src's `ZEND_DEFINED`; their correctness evidence, and their effect on Gate 1, Gate 2 and WooCommerce (splits, failed compilations, libgraal, cold and warm A/Bs); the bytecode interpreter's state for each |
| [perf-check.md](perf-check.md) | Roadmap step 3: the one performance check of main after the correctness batch (#32-#42, #35) against the main before it, on Gate 1, Gate 2 and WooCommerce with memory.peak; #35 undoing #41's split policy for isset reads (fix #46), Gate 2's warm gap at #33/#38 (`usort`) |
| [visit-phases.md](visit-phases.md) | Where a WordPress visit's time goes (include, parse, body reads, translation, native calls, compilation, GC, execution; cold, early and warm, JVM and native, PHP with and without OPcache), and what parsing ahead, a pre-pass or a parse-free start could gain at most |
| [gc.md](gc.md) | Why the GC is a large part of a native visit (the serial GC's 20 MB eden, ten collections a visit, each walking the compiled code), where a visit's 190 MB of allocation come from (required by PHP or ours), the fixes, one request's live heap (2-3 MB) against the server's fixed cost, and what virtual threads would change |
| [roadmap.md](roadmap.md) | The order of work after the 2026-11-03 correctness round: merges, bytecode only (D16), one performance check, performance off the store paths, destructors (D17), store-path tuning, native image |
| [graalvm-25-5.md](graalvm-25-5.md) | What GraalVM 25.5 brings for Truffle PHP: the upgrade (GR-79787 released, the stock DSL processor may suffice), the auxiliary engine cache for the cold visit on native image, native-only calling and dispatch items, `unwindExceptions` for generator destruction; the order to try them |
| [destructors.md](destructors.md) | Destructors exactly as php-src: what must be reproduced (probed on PHP 8.5), why no scheme cheaper than full reference counting is exact, the recommended design, expected cost, prototype thresholds, open questions |
| [destructors-prototype.md](destructors-prototype.md) | Roadmap step 5.1: the counting-only refcount prototype, what it counts and approximates, its checks and its A/B against main (Gate 1, IR after PE, Gate 2) |
| [implicit-state-machines.md](implicit-state-machines.md) | The audit of implicit state machines (flags, phases, protocols, reentrancy guards) on 2026-11-08: 71 machines, the confirmed defects with their issues (055-071) and smoke tests, the likely ones (072), the benign ones |
| [first-principles.md](first-principles.md) | What graalphp solved and how; the int array strategy and the "cold path = deopt" rule; K0 arithmetic kernels; php-src conformance baseline |
| [../issues/README.md](../issues/README.md) | One file per finding or problem |

## Build
GraalVM JDK 25.4.4.1.1 and Maven. Once per machine, `bash truffle-dsl-processor/build.sh`: it installs the Truffle DSL
processor with two Bytecode DSL fixes not yet released, which `pom.xml` requires
([truffle-dsl-processor/README.md](../truffle-dsl-processor/README.md)). Then `mvn clean package`.

## Checks before merging a change to the interpreter
| Check | Command | Passes when |
|---|---|---|
| unit tests, with the compilation check | `mvn test` | all pass; `CompilationTest` fails on any failed compilation or performance warning |
| smoke | `tests/smoke/run.sh` | every test's output equals PHP 8.5's |
| php-src `.phpt` | `tests/phpt/run.sh` | no test that passed fails |
| Gate 1 | `bench/gate1/compare.sh` (or `run.php all N`) | every kernel's checksum equals PHP's |
| WordPress pages | `tests/gate2/native.sh pages` and `compare` against `php` | 19/21 equal (the two others: the oEmbed link) |
| compilation diagnosis | `tests/gate2/compile-diag.sh run`, then `compile_report.py target/compile-diag --check` | every run within its thresholds ([partial-evaluation.md](partial-evaluation.md)) |

## Status

| Gate | State |
|---|---|
| Gate 0: Amdahl ceiling of WordPress on php-fpm | not started (can run in parallel with Gate 1) |
| **Gate 0.5: idiomatic Truffle structure ([D10](decisions.md))** | **done: steps 1–4 landed (per-context state; hot built-ins as DSL nodes; core nodes on the DSL; context-independent ASTs and `ContextPolicy.SHARED`, with class caches on language-level keys and frozen shared arrays). Step 4 cost 5–10% in a single context (hooks, wptexturize), spread over its commits at 1–3% each; server mode (2 contexts) is within 0–20% of single** |
| **Gate 1: Truffle core vs PHP 8.5 JIT on extracted WordPress hot functions** | **in progress: all 7 kernels correct; steady state 0.84× of PHP 8.5 JIT end to end, 0.81× on PHP code (2026-10-27; peak memory 1.5 GB against 32 MB, no heap limit set) (go needs 2×; exact regex time from a C replay); plateau after 250–300 iterations (3–7 s); K0 arithmetic kernels 1.96×; arrays in strategy stores, hashes built at size, int-key hash reads inline (against 2026-10-25: kses about 0.9×, blocks about 0.85×, hooks about 0.9×, escape and slugs about 0.85×); TRegex beside PCRE2, chosen per pattern ([D12](decisions.md), planned), would take kses's regex time to 0.68× and slugs' to 0.31× (simulated); next: computed string keys, copy-on-write copies, memory at a set heap limit**. Since 2026-11-03 the interpreter is the Bytecode DSL only ([D16](decisions.md)); roadmap step 4's steady-state work (#48, on `bytecode-only`, merged to main with #51 on 2026-11-06) measured Truffle PHP's time at 0.77–1.65 of PHP 8.5's per kernel on a loaded host, to be repeated ([roadmap.md](roadmap.md)) |
| Gate 2: WordPress boots and renders ([gate-2.md](gate-2.md)) | in progress: `wp-load.php` boots in CLI; over HTTP 7 of 9 pages byte-identical to PHP (the other two miss only the oEmbed link, `SimpleXMLElement`); code review fixed; PHP's error model, so WordPress's fatal error page matches too; its idiomaticity review fixed (php-src `.phpt` 39.9% strict); the installer's pages and database identical to PHP's; the wp-admin login and 5 of 7 wp-admin pages identical (the others: GD); curl and client sockets ([D13](decisions.md)), Closure, arithmetic TypeErrors, return by reference done (php-src `.phpt` 42.2%); TLA+ models of copy-on-write, curl handles and per-request state found 11 real problems, 6 fixed ([tla-cow.md](tla-cow.md), [tla-curl.md](tla-curl.md), [tla-engine.md](tla-engine.md)); open: no `__destruct` and no object refcounts ([destructors.md](destructors.md), D17). Since: 19 of 21 pages identical (the two others: the oEmbed link); WooCommerce runs as on PHP 8.5 except the analytics import, and under load takes about a quarter of php-fpm's orders ([woocommerce.md](woocommerce.md)); php-src `.phpt` 5,492 passing after #44. Since 2026-11-09: `__destruct` with php-src's object refcounts (#57), on by default (#59; garbage cycles not collected yet: 005, 036); the implicit state machines audited and their crash and fatal cases fixed (#58, issues 055-072); `Fiber`, on a platform thread per live fiber (#60, issue 031: `Zend/tests/fibers` 68 of 103); the WordPress PHPUnit suite runs by test name (#56, issues 038-051) |
| Gate 3: drop-in parity | — |
| Gate 4: performance and publication | — |

## Key numbers (2026-10-22)
- **WordPress core:** 625k lines of PHP, calling **677 distinct built-ins** (about 570 needed, since `sodium_*` has a PHP polyfill) in 31.8k call sites.
- **php-src:** about 700k lines of its own C (1.86M with bundled libraries and the generated VM), 1,923 built-in functions and 23,640 `.phpt` tests.
- **Estimated Truffle PHP for WordPress and the top plugins:** 150–250k lines of Java; Gate 1 needs about 15–25k.
