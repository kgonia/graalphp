# Where a WordPress visit's time goes: parse, translate, compile, execute

## Question
Could Truffle PHP start executing a file before it has read all of it ("streaming")? PHP's semantics rule that out
for a file:
- a syntax error anywhere means none of the file runs;
- top-level functions and classes are hoisted;
- compile-time errors cover the whole file.

Three variants stay legal:
1. **Parse ahead.** Parse the next included files on background threads while the current one runs. The include
   waits only if the result isn't ready, and checks that the file hasn't changed.
2. **A pre-pass.** A V8-style syntax check over function bodies that builds no tree.
3. **No parse at start.** As opcache or preloading do: WordPress core parsed into the native image's heap, or
   Oracle GraalVM's auxiliary engine caching.

Before choosing, this page measures where a visit's time goes, and bounds what each idea could gain.

## What we do already
With `php.Parser=java` each file is parsed whole, so its syntax is checked whole. Function and method bodies are
kept as ranges of the source (`SourceBody`). A body is parsed again and translated on its first call.
Translations live in the language (`PhpLanguage.file`), not in the context. Every request on the server shares
them, as php-fpm's workers share opcache. A file is translated once per process, and checked against its
modification time and size at each include.

## Method
**The counters.** `-Dphp.phases=true` turns them on (`pl.codeloom.php.runtime.Phases`, commit `a7e00eb`). The flag
is a static final, so a build without it folds them away. A native image fixes it at build time, so natively the
counters are a build of their own (`target/native/wp-http-phases`).
- Per thread, the counters keep exclusive wall times and counts:
  - an include's lookup: the path, the file's modification time and size, the cache;
  - reading a file and decoding it;
  - parsing it;
  - reading a body left unparsed;
  - translating a file's top level;
  - translating a body on its first call.
- A nested phase stops its outer phase's clock, so the times add up.
- They also count the bytes parsed and the bodies left and read, and the functions, methods and closures declared
  and translated.
- `-Dphp.nativeclock=true` adds `NativeClock`'s time in PCRE2 (matches, and now pattern compilation apart).
- `HttpMain` prints a line `phases {...}` per request, after the response is sent (`RequestPhases`). The line holds:
  - the request thread's wall and CPU time;
  - the context's setup and close;
  - the GC's pauses (the GC beans);
  - the process's CPU by thread name, from `/proc/self/task`. HotSpot keeps a name's first 15 characters, a native
    image its last 15. Both name the Truffle compiler threads (`TruffleCompiler...` and `...mpilerThread-`), and
    the JVM's own JIT and GC threads.

**The off-thread time.** `tests/gate2/phases_report.py` takes each visit's thread CPU from the end of the visit
before, the 1 s pause included, as `visits.py` takes the cgroup's. The groups are:
- Truffle compilation: the PHP code's compilations;
- the JVM's JIT: HotSpot's C1 and libgraal threads, compiling the interpreter's own Java;
- GC and VM threads.

The process's CPU by these groups equals the cgroup's CPU to 1%. A native image has a serial GC, which runs in the
request's thread, so there the GC is inside the request.

**The run.** `tests/gate2/native.sh measure 3 110 1` on the HTTP harness, 2026-11-01 06:33–09:00:
- the variants: `jvm-java`, `jvm-phases`, `native-java`, `native-phases`, `php`, `php-nojit`, `php-noopc`;
- WordPress 7.2-beta1 and its own MariaDB, our parser, the tree interpreter;
- a visit is 6 pages: home, post, login form, login, dashboard, posts list;
- each run is 1 cold visit and 110 more, 1 s apart, on a server just started and a database just reset;
- the server on CPUs 14-21 (the CPU lock held), the client on 0-5, the database on 22-23;
- the variants interleaved, the order reversed every other round.

**Rounds.** The host's median load in each run was 5-57%. Round 3 was repeated once because one run reached 57%.
The repeat ran while other sessions loaded the host to 68-77% (a cold JVM visit took 48 CPU-s instead of 21), so I
stopped it and kept round 3's first attempt. The repeat's files are in `target/vp/runs/loaded/`.

A cell is the median of the three rounds (their range in parentheses):
- cold: the first visit;
- early: the median of visits 2-5;
- warm: the median of the last 20 visits.

**PHP 8.5.12.** The harness's PHP runs OPcache with the tracing JIT, and `opcache.validate_timestamps=0`, so it
never checks a file. I checked each variant on a page that prints `opcache_get_status()`:
- `php`: OPcache on, with the JIT; the home page left 536 scripts cached;
- `php-nojit`: OPcache on, the JIT off (`-d opcache.jit=disable`);
- `php-noopc`: OPcache off (`-d opcache.enable=0`): each request compiles every file it includes.

## What a visit loads
All the visit's code is loaded in the first visit: no visit after it parses a file or translates a function.

| per process (the same in every run) | |
|---|---|
| files parsed | 596, 12.9 MB |
| bodies left unparsed by the file parse | 7.84 MB, 61% of the text |
| bodies read again (first calls) | 2,646, 3.27 MB: 42% of what was left |
| functions declared / translated | 3,931 / 1,406 (36%) |
| methods declared / translated | 3,817 / 1,140 (30%) |
| closures declared / translated | 73 / 33 (45%) |
| includes per visit | 3,285: about 550 per page |

**What the laziness saves.** Two thirds of the declared functions and methods are never translated. If reading and
translating cost the same per function, translating all of them would add about 0.9 s to the JVM's cold visit, about
a fifth of it. This is an estimate from the counters, not a run.

## The phases
On the requests' thread (the critical path), in seconds per visit, with the counters on:

| s per visit | JVM cold | JVM early (2–5) | JVM warm | native cold | native early | native warm |
|---|---|---|---|---|---|---|
| **wait (client)** | **4.30** (3.67–4.59) | **0.86** (0.76–0.93) | **0.484** (0.482–0.489) | **1.93** (1.91–2.00) | **0.83** (0.82–0.84) | **0.682** (0.675–0.796) |
| include lookups | 0.046 | 0.015 | 0.010 | 0.014 | 0.013 | 0.012 |
| (a) file read | 0.100 | 0 | 0 | 0.035 | 0 | 0 |
| (a) file parse | 0.455 (0.435–0.601) | 0 | 0 | 0.231 (0.217–0.280) | 0 | 0 |
| (b) body read (first calls) | 0.232 | 0 | 0 | 0.229 | 0 | 0 |
| (c) file translate | 0.085 | 0 | 0 | 0.019 | 0 | 0 |
| (c) body translate | 0.292 | 0 | 0 | 0.139 | 0 | 0 |
| (d) native: PCRE2 matches | 0.019 | 0.005 | 0.004 | 0.009 | 0.007 | 0.006 |
| (d) native: pattern compiles | 0.035 | 0 | 0 | 0.007 | 0 | 0 |
| context setup and close | 0.047 | 0.009 | 0.005 | 0.004 | 0.004 | 0.004 |
| execution (the rest) | 2.98 (2.52–3.03) | 0.82 | 0.459 | 1.25 (1.20–1.29) | 0.80 | 0.652 |
| GC pauses, inside the lines above | 0.065 | 0.004 | 0.002 | **0.497** | 0.039 | **0.122** |
| the requests' thread CPU | 4.02 | 0.81 | 0.441 | 1.71 | 0.77 | 0.631 |

Off the requests' thread, CPU-s per visit (the pause before the visit included):

| CPU-s per visit | JVM cold | JVM early | JVM warm | native cold | native early | native warm |
|---|---|---|---|---|---|---|
| Truffle compilation | 9.37 | 3.89 | 1.06 | 4.24 | 3.94 | 3.81 |
| the JVM's JIT (the interpreter's Java) | 7.09 | 0.48 | 0.04 | – | – | – |
| GC and VM threads | 0.30 | 0.02 | 0.005 | – (in the request) | – | – |
| other and ended threads | 3.35 (0.20–3.74) | 0.90 | 0.005 | 0.07 | 0.005 | 0 |
| **the cgroup's CPU** | **23.8** (20.0–24.7) | **6.17** | **1.57** | **6.00** | **4.83** | **4.45** |

- **Parsing and translating happen once per process.** They are 1.16 s of the JVM's cold visit (27%) and 0.65 s of
  the native one (34%). From the second visit on they are zero: the engine's shared translations already do what
  OPcache does for PHP.
- **What is left warm is execution.** It is 0.46 s on the JVM and 0.65 s natively, against PHP's whole warm visit of
  0.27 s. The include lookups (3,285 file checks) are 2% of a warm visit, and native calls about 1%.
- **The JVM's cold visit is mostly execution in cold code:** 2.98 s, while HotSpot is still compiling the
  interpreter's own Java (7 CPU-s). Natively that code is compiled ahead of time, and execution takes 1.25 s.
- **Natively, the GC is a quarter of the cold visit** (0.50 s of 1.93) and 18% of a warm one (0.12 s of 0.68). The
  serial GC stops the request's thread; on the JVM, G1's pauses are 0.065 s and 0.002 s.
- **Native compilation doesn't settle:** 3.8 CPU-s per visit after 100 visits, against 1.06 on the JVM
  (partial-evaluation.md, "What is left open").

## PHP with and without OPcache

| | cold visit | visit 2 | warm visit | warm CPU-s | memory.peak warm |
|---|---|---|---|---|---|
| php (OPcache, tracing JIT) | 1.00 s (0.89–1.13) | 0.33 | 0.274 (0.269–0.286) | 0.27 | 114 MB |
| php-nojit (OPcache, no JIT) | 0.71 s (0.70–0.79) | 0.29 | 0.277 (0.275–0.452) | 0.26 | 95 MB |
| php-noopc (no OPcache) | 1.07 s (1.04–1.39) | 0.95 | 0.855 (0.847–0.966) | 0.84 | 211 MB |
| JVM, our parser | 3.82 s (3.73–4.02) | 0.92 | 0.494 (0.472–0.503) | 1.76 | 1705 MB |
| native, our parser | 2.11 s (1.91–2.32) | 1.09 | 0.739 (0.684–0.774) | 4.80 | 1361 MB |

- **What compiling costs PHP:** without OPcache, every warm visit takes 0.58 s more (0.855 against 0.277), 68% of
  the visit. That is the parse and compile of the visit's 596 files, six times over, plus what OPcache keeps
  besides (interned strings, immutable classes and arrays).
- **The JIT gains nothing on these pages** (0.274 against 0.277). It costs 0.3 s in the cold visit.
- **Our warm visits already pay no parse**, as PHP's with OPcache don't. We pay it once per process: 1.16 s on the
  JVM and 0.65 s natively. PHP without OPcache pays 0.58 s every visit.

## What each idea could gain at most
Amdahl: a part f of the time made free gives at most 1 / (1 − f). Each f is a part of the visit's wait, taken per
round and then the median.

**1. Parse ahead on background threads.** At most, all of (a) leaves the critical path.
- JVM cold: read and parse are 14.2% of the visit, **1.17×** at most; with the files' top-level translation too,
  16.5%, 1.20×.
- Native cold: 13.8%, **1.16×**; with the top-level translation, 14.8%, 1.17×.
- Early and warm visits: **nothing.** No visit after the first parses a file.
- Over a process's first 111 visits (67 s of waits on the JVM, 80 s natively) that is 0.8% and 0.3% of the time.
- **Caveats:**
  - The threads have to know the next files before the include: WordPress's are mostly fixed `require`s in
    `wp-settings.php`, but some are computed.
  - The cold visit already keeps 5.5 of the JVM's 8 CPUs busy, mostly with compilation.
  - Natively, a background parse allocates in the same heap, and the serial GC stops every thread for it.

**2. A pre-pass over bodies.** The file parse builds php-src's tree for the bodies too, then drops it (the
`SourceBody` keeps only the range). A pre-pass would still scan every token and run the grammar; it would only skip
building nodes.
- The bodies are 61% of the text. Taking all their share of the file parse as the upper bound:
  - JVM cold: 7.2% of the visit, **1.08×** at most;
  - native cold: 7.3%, **1.08×**.
- Scanning alone takes 13% of a cold parse and 41% of a warm one. I timed this on 1,422 files of `wp-includes` and
  `wp-admin`, 21.3 MB, 20 rounds, on CPUs 14-21 (`target/vp/scan-share.txt`). The pre-pass can't go below scanning,
  so the bound falls to 6.2% and 6.3%: 1.07×.
- Early and warm visits: nothing.
- It doesn't change (b): a body that is called is still parsed again on its first call.

**3. No parse at start (the image heap, or the auxiliary engine cache).**
- **Parse and translation only,** (a) to (c):
  - JVM cold: 27.5%, **1.38×**;
  - native cold: 32.7%, **1.49×**;
  - with the include lookups too: 1.40× and 1.50×.
  - Early and warm: only the lookups are left to save, 1.8% and 1.7% on a native warm visit: **1.02×**. That would
    need a "don't check files" setting, like the harness's `opcache.validate_timestamps=0`. It is not an image's
    gain.
- **With the compiled code cached too,** at best a cold visit becomes a warm one:
  - native cold: 1.93 → 0.68 s, **2.8×**;
  - native visits 2–5: 0.83 → 0.68 s, **1.2×**;
  - and the 4.2 CPU-s of compilation of a cold visit, if the cache holds what the warm server compiles.
  - On the JVM the bound would be 8.8×, but the auxiliary engine cache exists only for native images.
- **Only the native image's auxiliary engine cache can hold compiled code.** Parsed WordPress in the image heap
  would cover the parse and translation only.

## The auxiliary engine cache: what a language needs
I took this from the GraalVM documentation only, and implemented nothing:
- [Auxiliary Engine Caching](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/AuxiliaryEngineCachingEnterprise/);
- [Truffle AOT Overview](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/AOTOverview/).

**What it does.** It persists "an engine with ASTs and optimized machine code to disk" through the native image's
auxiliary image feature, and loads it at start. The docs call the load "typically almost instantaneous". It stores
"code only and no application context-specific data".
- `--engine.CacheStore`, `--engine.CacheLoad` or `--engine.Cache` store and load it.
- `--engine.CacheCompile` chooses what is compiled before storing: `none`, `compiled`, `hot` (default), `aot` or
  `executed`.

**Where it runs.** Only in Oracle GraalVM, and only in a native image built with `-H:+AuxiliaryEngineCache`. "The
persisted auxiliary engine image can only be used with the same SVM native image that it was created with." Our
build image is Oracle GraalVM 25.4.4.1.1 (its `release` lists `graal-enterprise` and `substratevm-enterprise`),
so the feature is available to us.

**What a language must do, against what we have:**

| requirement (the docs) | Truffle PHP |
|---|---|
| a shared context policy ("If the language does not support it, then no data will be persisted") | yes: `ContextPolicy.SHARED` (Gate 0.5, step 4) |
| no context-dependent data in nodes; assumptions and shapes on the language, not the context; no speculation on value identity with multiple contexts | yes: context-independent ASTs, class caches on language-level keys, and the `singleContext` assumption invalidated in multi-context mode (`initializeMultipleContexts`), which storing the cache turns on |
| sources loaded and parsed through `Env.parse`, whose cache is per language instance | **no:** an include goes through our own map, `PhpLanguage.files`. Whether that map is persisted with the language instance, the docs don't say. A trial with `--engine.TraceCache` would show it |
| `RootNode.prepareForAOT` to compile roots never executed (`CacheCompile=aot`) | no. The default, `hot`, compiles what ran, so the cache would be stored after a warm-up |
| `TruffleLanguage.patchContext` for context pre-initialization (`CachePreinitializeContext`, default true) | no. Without it, a context isn't pre-initialized; the code is cached all the same |
| no native memory in what is persisted (the FFM page bars native `MemorySegment`s from the image heap) | **no:** the language-level PCRE2 pattern cache (`PhpLanguage.pcrePatterns`) holds pointers to compiled patterns and their JIT code. It would have to be emptied before storing, or rebuilt at load |

- **A file changed after the cache was stored** is handled already. Our translations are keyed on the file's
  modification time and size, and checked at each include, as `opcache.validate_timestamps=1` does.
- **Feasible, with two changes:** keep native state out of the language's caches, and make sure the include cache
  is in what gets persisted (or move includes onto `Env.parse`). Then a trial: build with
  `-H:+AuxiliaryEngineCache`, have `HttpMain` pass the engine options, store after a warm-up, load at start, and
  check correctness and the cold visit.

## Conclusion
- **Streaming in any of its legal forms speeds up only a process's first visit.** From the second visit on,
  nothing is parsed or translated: the engine's shared translations do what OPcache does for PHP.
- **Idea 1 (parse ahead): not worth doing.** At most 1.17× of the first visit, under 1% of a process's first 111
  visits. It would need threads, include prediction and change checks.
- **Idea 2 (a pre-pass): not worth doing.** At most 1.08× of the first visit.
- **Idea 3 (no parse at start) is the only one with a real bound, and only with compiled code:**
  - parse and translation alone: 1.38× (JVM) and 1.49× (native) of the first visit;
  - with the auxiliary engine cache's compiled code: up to 2.8× of a native first visit, and 1.2× of visits 2–5.
  - It matters only where servers start often (scale to zero, many short-lived workers). For a long-running
    server, a first visit is one in thousands.
  - If cold start becomes a goal, the next step is the auxiliary-cache trial above, not a parser change.
- **Where the time is, warm:** execution, which is 0.46 s on the JVM and 0.65 s natively against PHP's 0.27 s
  visit. Natively there are also the GC (0.12 s a visit, the serial collector) and compilation that never settles
  (3.8 CPU-s a visit).
- **The counters cost nothing measurable** with them on (`*-phases`) against off (`*-java`):
  - warm wait 0.98× on the JVM (0.484 against 0.494 s) and 0.92× natively (0.682 against 0.739);
  - cold 1.13× on the JVM (4.30 against 3.82 s, the ranges overlap: 3.67–4.59 and 3.73–4.02) and 0.92× natively;
  - memory.peak warm 1636 against 1705 MB on the JVM and 1363 against 1361 MB natively;
  - the differences go both ways, within run-to-run noise.

## Reproduce
```
# the classes, then the two native HTTP launchers (about 6 GB each; take a memory-lock reservation first)
mvn package -DskipTests
NI_MAIN=pl.codeloom.php.launcher.HttpMain NI_NAME=wp-http SKIP_AGENT=1 bash bench/native/build.sh
NI_MAIN=pl.codeloom.php.launcher.HttpMain NI_NAME=wp-http-phases SKIP_AGENT=1 \
  NI_OPTS="-Dphp.phases=true -Dphp.nativeclock=true" bash bench/native/build.sh
# a session's own prefix; WPN_DB_DUMP seeds the database from a dump when gate2-mariadb isn't running
export WPN_NAME=vp WPN_NET=172.31.9 WPN_DB_PORT=3329
tests/gate2/native.sh db && tests/gate2/native.sh volume
# with the CPU lock held
CPU_LOCK_HELD=1 tests/gate2/native.sh measure 3 110 1 jvm-java jvm-phases native-java native-phases php php-nojit php-noopc
python tests/gate2/visits_report.py target/vp/runs php php jvm-java jvm-phases native-java native-phases php-nojit php-noopc --window=20
python tests/gate2/phases_report.py target/vp/runs jvm-phases native-phases
```
