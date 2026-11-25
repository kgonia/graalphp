# Probe B — True Open-World `.class` Runtime + Real `D17`

Standalone open-world pre-check for Milestone B1 ([wiki/b1-precheck.md](../../../wiki/b1-precheck.md)), testing
whether `.php → .class` (`shared` linkage from Probe A) with zero closed-world shortcuts and exact `D17` reference
counting beats or matches PHP 8.5.13 tracing JIT on Linux Docker (`--cpuset-cpus 14-21`).

Full Rev 3 benchmark tables, Codex adversarial review history, and kill-criteria verdict: [RESULTS.md](RESULTS.md).

## Layout

- `project.py` — projects `src/main/java/pl/codeloom/php/runtime/PhpArray.java` and `PhpRef.java` onto `ByteStr`
  (zero Truffle, without modifying `src/main/` or `pom.xml`), emits byte-direct `ArrayKey.java`, and generates
  `MegaGenerated.java` with **181 nested classes** (`60 + 1` functions, `20` `HotC` object classes + `60` method
  classes, `40` closure subclasses; `0` `invokedynamic`).
- `src/pl/codeloom/php/runtime/` — open-world runtime & kernels:
  - `ByteStr.java` — immutable raw `byte[]` string with cached hash, byte-level `trim`/`ucfirst`/`replace`, and
    single-digit `'0'..'9'` cache matching `php-src` `ZSTR_CHAR`.
  - `PhpCore.java` — `PhpClass` runtime metadata (methods & declared property slots), `PhpObject`, `PhpClosure`,
    and vtable `abstract class PhpCallable` / `PhpInstanceMethod`.
  - `Refcount.java` — real `D17` exact refcounting, shadow-refcount Bacon-Rajan cycle collector (`gc_collect_cycles()`),
    and Zend iterative `rc == 1` reverse-globals shutdown (`zend_execute_API.c:210-267`).
  - `PhpRequestContext.java` — explicit parameter 0 holding all per-request functions, classes, constants, globals,
    WordPress hook tables, GC roots, and O(1) intrusive destructible list.
  - `HooksOpenWorld.java` — `WP_Hook`, `apply_filters`, `do_action`, `add_filter`, `remove_all_filters` matching
    `wp-includes/plugin.php:175-212` (variadic prepend + live `$wp_filter['all']` rechecks + receiver retention).
  - `WPPostOpenWorld.java` — `StdClass` and `WPPost` resolving variable writes and reads through runtime `PhpClass`
    property metadata with reentrant-safe publish-before-release on all 24 declared fields.
  - `HotOpenWorld.java` — `_wp_array_get`, `aget`, `post`, and 181-class `mega`.
  - `Corpus.java`, `Checks.java`, `ProbeBMain.java` — Gate 1 corpus loader, `D17` & Codex regression gate suite, CLI.
- `php-ref/` — pinned PHP 8.5 reference scripts (`hot.php`, `hot-fns.php`, `d17_fixture.php`, `probe_env.php`).
- `run-bench.ps1` / `summarize.py` — 5-round interleaved Linux Docker benchmark runner (`--cpuset-cpus 14-21`,
  cgroup `memory.peak`, paired solo + `CtxB` + `2ctx` timing, `[STEADY_START_MS, STEADY_END_MS]` `-XX:+LogCompilation`
  deopt windowing, and `-XX:NativeMemoryTracking=summary`).

## Reproduce

```powershell
# 1. Project & compile inside Linux Docker (probe-graal:25.4)
python bench/b1-precheck/probe-b/project.py
docker run --rm -v "${PWD}:/w" -w /w probe-graal:25.4 sh -c '
  rm -rf bench/b1-precheck/probe-b/build/classes && mkdir -p bench/b1-precheck/probe-b/build/classes &&
  javac --release 25 -encoding UTF-8 -g -d bench/b1-precheck/probe-b/build/classes \
    bench/b1-precheck/probe-b/build/generated/pl/codeloom/php/runtime/*.java \
    bench/b1-precheck/probe-b/src/pl/codeloom/php/runtime/*.java &&
  java -cp bench/b1-precheck/probe-b/build/classes pl.codeloom.php.runtime.ProbeBMain check bench/gate1/corpus.php &&
  java -cp bench/b1-precheck/probe-b/build/classes pl.codeloom.php.runtime.ProbeBMain d17 > bench/b1-precheck/probe-b/build/d17-java.txt
'

# 2. Diff D17 destructor output byte-for-byte against pinned PHP 8.5.13
docker run --rm -v "${PWD}:/w" -w /w php@sha256:dbf32b33595283b79fd1a37ebc1614bdf1b83d3750daad9a57d67dbb9b96ec16 sh -c '
  php bench/b1-precheck/probe-b/php-ref/d17_fixture.php > bench/b1-precheck/probe-b/build/d17-php.txt &&
  diff -u bench/b1-precheck/probe-b/build/d17-php.txt bench/b1-precheck/probe-b/build/d17-java.txt
'

# 3. Run 5-round interleaved Linux Docker benchmark suite (CPUs 14-21)
powershell -NoProfile -ExecutionPolicy Bypass -File bench/b1-precheck/probe-b/run-bench.ps1 -Rounds 5 -Cpuset 14-21
```
