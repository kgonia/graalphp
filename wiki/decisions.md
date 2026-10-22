# Decisions

Newest at the bottom. Each entry gives the decision, the date, and why.

## D1: the goal is Graal making PHP code faster (2026-10-22)
The owner said: "Graal speeding up PHP is the point." Success means unmodified WordPress and plugins running faster than PHP 8.4 with OPcache JIT, not just hosting PHP on the JVM.

## D2: a Truffle language, not libphp+FFM or php.wasm (2026-10-22)
- **libphp embedded via FFM:** PHP still runs in the C engine, and Java only hosts HTTP. Every plugin works, but nothing gets faster.
- **php.wasm on GraalWasm:** Graal compiles the *PHP interpreter* compiled to Wasm. The PHP script is data in linear memory, so Graal can't specialize it: no inlining of PHP calls and no type speculation. OPcache's JIT can't run inside Wasm either, so it is expected to be *slower* than php-fpm. It's still useful for sandboxing, but not for this goal. Running php-src on Sulong has the same problem.
- **JPHP and Quercus:** dead, and stuck at PHP 7.x and 5.4.
- **PHP → JVM bytecode** (JPHP style) is optimized by Graal as a JIT, but without Truffle's runtime type specialization and deoptimization. Every fast dynamic language on GraalVM is a Truffle language.

## D3: start fresh, not from a graalphp fork (2026-10-22)
See [graalphp-assessment.md](graalphp-assessment.md). graalphp is on Truffle 20.2 (2020), is 6.7k lines, and has no strings, objects, hash arrays or copy-on-write. Its benchmarks and storage-strategy idea are reused; its code isn't.

## D4: gates with measured go/no-go criteria (2026-10-22)
HHVM's lesson is that compatibility work can swallow a project whose speed lead then disappears. The cheapest decisive experiment comes first: Gate 1 measures Truffle against PHP 8.4 JIT on real WordPress functions before any long-tail runtime work.

## D5: Gate 1 parses with php-src itself (2026-10-22)
The PHP source is parsed by real PHP 8.4 with the `ast` extension (nikic/php-ast) and exported as JSON. The Truffle side builds nodes from **zend_ast kinds**.
- **Why:** zero parser work in Gate 1, and the parse is exactly php-src's.
- **Later:** a Java parser ported from `zend_language_parser.y` replaces the front end in Gate 2 and emits the same zend_ast kinds, so the node builder stays.

## D6: Gate 1 uses a Truffle AST interpreter (Truffle DSL), not the Bytecode DSL (2026-10-22)
Gate 1 answers a peak-performance question, and AST and bytecode interpreters reach the same compiled code. The Bytecode DSL mainly improves interpreter speed, memory and warmup. Gate 1 records warmup curves. If warmup is the problem, moving to the Bytecode DSL is a Gate 2 decision.

## D7: `preg_*` binds the real PCRE2 via FFM from the start (2026-10-22)
- **Semantics:** they are PHP's exactly, because PHP bundles PCRE2. `java.util.regex` differs.
- **Fairness:** regex time is the same on both sides, so Gate 1 measures what Graal does to the PHP code around the regexes.
- **Later:** a PCRE flavor for GraalVM's TRegex, which would make regexes compilable, is a separate later project.

## D8: toolchain (2026-10-22, revised the same day)
- **Oracle GraalVM 25.0.4** with Truffle **25.0.4** from Maven Central, on both platforms (`~/.jdks/graalvm-jdk-25.0.4+7.1` on Windows; the Linux build in the `gate1-truffle` image).
  - First choice was 25.4.4.1.1, but Oracle's newest Linux download is 25.0.4, and Truffle refuses to compile on a JDK older than itself ("Version check failed"; it falls back to the interpreter).
- Maven (IntelliJ's bundled 3.x locally).
- Package `pl.codeloom.php`.
- The reference PHP runs in Docker (`php:8.5-cli` plus `ast`, OPcache tracing JIT on; 8.4 too, see D9).
- **Truffle runs in Docker too:** `bench/truffle/Dockerfile` builds `gate1-truffle` on top of the PHP image (same Debian and CPU), adding Linux GraalVM and PHP's own bundled PCRE2 built as a shared library (`build-pcre2.sh`). `bench/gate1/truffle.sh` is the counterpart of `php.sh`.
- Published Gate 1 numbers come from Linux with pinned cores, never from Windows. On Windows, Git's libpcre2 scans about 20× slower than PHP's, which makes regex kernels incomparable there.

## D9: Gate 1 reference is PHP 8.5, and Go is judged on the PHP-code speedup (2026-10-22)
- **PHP 8.5:** it is the newest stable release (8.5.12; 8.6 is at RC4), and the retro says to compare against the newest PHP. 8.4.27 is measured too.
- **PHP-code speedup:** both engines run the same PCRE2, so regex time caps the end-to-end speedup regardless of Graal (side agent note, 2026-10-22). Gate 1 times native calls on the Truffle side and judges (T_php - T_native) / (T_truffle - T_native), with end-to-end reported next to it. Details in [gate-1.md](gate-1.md).
