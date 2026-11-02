# Garbage collection on a WordPress visit: why it is large, and what of it goes (2026-11-01, timed 2026-11-02)

## Question
[visit-phases.md](visit-phases.md) found the GC at 25% of a native cold visit (0.50 s of 1.93) and 18% of a warm one
(0.12 s of 0.68), against G1 pauses of 0.065 s and 0.002 s on the JVM. Why, and how much of it can go? And: how much
memory does one request in flight need, against the server's fixed cost, and would virtual threads change that?

## Short answer
- **A warm visit allocates about 190 MB** on the requests' threads, on the JVM and natively alike (six pages:
  home, post, login form, login, dashboard, posts list). A request keeps only **2.6 MB** of it alive at a time.
- **The JVM doesn't suffer from it.** G1 grows its eden to ~300 MB, collects about once a visit, copies 2-5 MB, and
  pauses 2-3 ms: 0.002 s a visit warm.
- **Natively the cost is the serial GC's default policy, not the allocation.** `Adaptive2` keeps the eden at
  ~20 MB, so a visit takes **10 young collections**, and each costs 10-16 ms: 5 ms walking the runtime-compiled
  code (WordPress has a lot), 3-9 ms copying the survivors again (4-6 MB, the request in flight), 1-2 ms of dirty
  cards. A full collection (~0.3 s) comes every ~30 visits.
- **Fixed here (allocation):** seven causes of our own: 191 → 161 MB a visit on the JVM (−16%), 180 → 153 MB
  natively (−15%). Natively that is two young collections fewer a visit: GC 0.113 → 0.093 s a warm visit (−18%),
  the warm visit 0.679 → 0.642 s. On the JVM the waits don't move beyond their noise (GC was ~0 there already).
- **The policy, measured, not applied:** a minimum heap lets `Adaptive2` keep a larger eden. On this branch,
  `-Xms256m` takes a warm visit's GC to 0.048 s and the cold visit's from 0.61 to 0.34 s, for +63 MB of
  memory.peak; `-Xms512m` to 0.015 s (−87% against main), the warm visit to 0.571 s, for +183 MB. The harness's
  defaults are left as they are (the choice trades memory for time; it is the owner's).
- **Memory:** one request in flight needs ~2.6 MB of live heap (4-5 MB at its peak); 4 in flight 10 MB, 8 in flight
  22 MB. The server's own live heap is ~96 MB, beside the compiled code and the compiler. Virtual threads would
  change neither.

## Method
New tools (this branch):
- **The phases line** (`-Dphp.phases=true`, `RequestPhases`) now carries each request's allocated bytes
  (`alloc_bytes`, the thread's counter: on the JVM and natively), every thread's allocated bytes by name (the JVM
  only: a native image counts only the current thread), each collector's count and time, and each heap pool's use,
  committed size and use after its last collection.
- **`tests/gate2/gcstats.py`**: per visit, the medians of the above over the last N visits, and from a native
  `-XX:+PrintGC` log the heap's allocation between collections (which counts the threads the image doesn't).
- **`-Dphp.heapprobe=MS`** (`HeapProbe`): a full collection every MS ms, printing the live heap and the requests in
  flight; `tests/gate2/heapprobe.py` takes the live heap by requests in flight against the idle baseline;
  `tests/gate2/concurrent.py` runs N clients repeating the visit.
- **Allocation sites:** JFR's `jdk.ObjectAllocationSample` (`allocation-profiling=maximum`, ~14,000 samples over 30
  warm visits) on the warm JVM server, started by `jcmd` after 100 visits. The samples' weights are unusable here
  (a pool thread waking after the 1 s pause carried hundreds of MB), so each sample counts once: a sample is taken
  at a TLAB refill, so the counts are proportional to bytes. They are scaled to the visit's measured allocation.
- **Array copies:** a diagnostic counter in `PhpArray.copy` (not committed) that recorded each copy's Java caller
  and the three innermost PHP functions, over 20 warm visits.
- **Setup:** `tests/gate2/native.sh` with a database of this session's own (`WPN_NAME=gc`), the phases builds
  (`jvm-phases`, `native-phases`), CPUs 6-13 for the analysis runs. Those runs aren't timed: the box was shared, and
  only counts (bytes, collections) are read from them. The timed runs are below.

## Where a visit's 190 MB come from (JVM, before the fixes)
Allocation-sample counts by what allocated (rules in order, the first match wins; one sample ≈ 0.4 MB):

| category | MB/visit | share | PHP would allocate it? |
|---|---|---|---|
| strings PHP builds (concatenation, interpolation, implode results) | 17.6 | 9.2% | yes |
| array copies (copy-on-write separations) | 16.9 | 8.9% | yes: the hot copies are ones php-src makes too (below) |
| new arrays (literals, built-ins' results, element writes) | 13.6 | 7.1% | yes |
| array growth (hash doubling) | 10.4 | 5.4% | mostly yes; array_merge not presized: ours, fixed |
| file_get_contents (theme.json, block.json) | 10.4 | 5.4% | ~1/5: we copied each file about five times, fixed |
| strtr / str_replace / other string built-ins | 9.4 | 4.9% | mostly yes |
| serialize / unserialize | 9.1 | 4.8% | mostly yes |
| (object) casts: a property per key | 8.0 | 4.2% | no: php-src shares the array as the property table; ours (key conversion fixed, the object model open) |
| include lookups (paths, two stats per include) | 7.5 | 3.9% | no with OPcache's `validate_timestamps=0` (the harness's); open |
| declarations per request (function and class tables) | 7.3 | 3.8% | partly (php-src adds each function to the request's table too) |
| join's wrappers (an `InternalByteArray` per part, ints as strings) | 6.5 | 3.4% | no: ours, fixed |
| preg_* (`$matches`, subjects) | 6.3 | 3.3% | mostly yes |
| packed → hash conversions | 6.0 | 3.2% | partly (php-src converts too); the Object[] step ours, fixed |
| measurement (`RequestPhases`, phases builds only) | 5.8 | 3.0% | – |
| string conversions (`Str.java`, `Str.key`, bytes copies) | 5.6 | 3.0% | no: ours, partly fixed |
| argument arrays (`Object[]` per call that isn't inlined) | 5.6 | 2.9% | no: Truffle's calling convention; open |
| callable resolution (a name decoded per `call_user_func`) | 4.8 | 2.5% | no: ours, fixed |
| array functions (array_diff's HashMap, ...) | 4.0 | 2.1% | partly |
| `Object[]` at method dispatch (frames, arguments) | 3.2 | 1.7% | no: open |
| trim's character masks | 3.2 | 1.7% | no: ours, fixed |
| mysqli (rows, connect buffers) | 2.9 | 1.5% | mostly yes |
| int arrays boxed (boxLongs) | 1.7 | 0.9% | no: ours, partly fixed |
| frames and locals | 1.6 | 0.9% | no |
| control-flow exceptions (`ControlFlow.Return` in code not compiled together) | 1.3 | 0.7% | no |
| boxed ints and floats | 1.1 | 0.6% | no |
| object shapes and transitions | 0.9 | 0.5% | no |
| the HTTP server and context setup | 0.5 | 0.2% | – |
| other (spread thin) | 19.4 | 10.2% | – |

**Array copies are PHP's own.** The copy counter's top sites per visit:
- `WP_Scripts::get_highest_fetchpriority_with_dependents`: 8,400 copies of its by-value `$checked` (2.8 elements
  on average), which it writes after recursing; php-src copies it too (the caller still holds it).
- `WP_Theme_JSON::sanitize`: 7,600 copies of a nested element (12.7 elements) that is also held by a variable
  (`$schema_styles_blocks[$block] = $styles_non_top_level;` then a write into it).
- `WP_Hook::apply_filters`: `$args[0] = $value` and `next($this->iterations[$level])` copy once per call, as
  php-src's `SEPARATE_ARRAY` does (both arrays have a second holder).
- `user_has_cap` filters (`wp_maybe_grant_*_caps`): a 64-element `$allcaps` copied per filter, held by
  `apply_filters`' `$args` and `$value`.
- 6,800 copies of empty arrays in `WP_Theme_JSON::get_block_element_selectors`: php-src allocates a new array on
  the first write to the immutable empty array too.

So holder counting matches php-src's refcounts on the hot paths; it isn't the source of extra copies here.

## The native serial GC
The image's runtime compilation runs in isolates of its own (`-XX:+CompileInIsolates`, on by default), so the
compiler's garbage isn't in the requests' heap. The heap's allocation between collections is 196 MB a visit against
the requests' 180 MB: everything else (compiler, HTTP server) is ~16 MB.

**Before (`Adaptive2`, the default)**, 30 warm visits (`-XX:+PrintGC`):

| | per visit |
|---|---|
| requests' allocation | 180 MB |
| young collections | 10 (each frees ~19 MB: eden 19.5 MB, survivors 4.5-10 MB) |
| young collections' time | 0.185 s (CPUs 6-13, shared) |
| full collections | one every ~30 visits, 0.28 s |
| old generation after a collection | 87-92 MB |

One young collection, with `-XX:+VerboseGC -XX:+PrintGCTimes` (10-16 ms):

| phase | ms |
|---|---|
| `walkRuntimeCodeCache`: the runtime-compiled code's object references | 4.5-5.7 |
| `scanGreyObjects`: copying the survivors (4.5-6.5 MB) | 2.5-8.7 |
| `blackenDirtyCardRoots`: the old generation's dirty cards | 1.0-1.6 |
| `blackenImageHeapRoots` | 0.5 |
| stacks, thread locals, references | 0.1-0.3 |

Two thirds of each collection don't depend on how much was allocated: the code cache walk and the cards cost the
same at 20 MB of eden as at 200. The survivors are the request in flight, copied again at every collection while it
runs. A bigger eden pays both less often.

**What sizes the eden** (the base image, 40 visits each, the last 15; counts only, the cores were shared):

| run-time option | young per visit | young ms per visit | eden at most | RSS (docker) |
|---|---|---|---|---|
| none (`Adaptive2`) | 10 | 150-185 | 19 MB | 350-420 MB |
| `-XX:MaxNewSize=256m`, `-Xmn256m` | 10 | 135-140 | 19 MB | 350-420 MB |
| `-XX:MaximumYoungGenerationSizePercent=30` | 10 | 151 | 19 MB | 350 MB |
| `-Xms1g` | 1 | 25 | 191 MB | 574 MB |
| `-XX:InitialCollectionPolicy=BySpaceAndTime` | 2 | 48 | 92 MB (no survivors: old grows, full every few visits) | 520 MB |
| `-XX:InitialCollectionPolicy=Adaptive` (the former default) | 1 | 21 | 253 MB | 714 MB |

The maxima don't move `Adaptive2`: it sizes the young generation from the heap's current size, which stays at
~120 MB. Only a larger minimum heap or another policy gives it a larger eden.

**Before and after the fixes, and the minimum heap** (main's and this branch's images, 40 visits each, the last 15;
CPUs 6-13, shared, so the times are counts' worth only):

| image, option | requests' MB/visit | heap MB/visit (GC log) | young per visit | young ms per visit | eden at most | RSS (docker) |
|---|---|---|---|---|---|---|
| main | 179.8 | 196 | 10 | 156 | 19 MB | 389 MB |
| this branch | 152.8 (−15%) | 157 | 8 | 131 | 19 MB | 383 MB |
| this branch, `-Xms256m` | 152.8 | 165 | 2 | 39 | 79 MB | 426 MB |
| this branch, `-Xms512m` | 152.7 | 192 | 1 | 24 | 189 MB | 526 MB |

The fixes cut the native allocation by the same 27 MB a visit as on the JVM, and two of the ten collections. A
minimum heap of 256 MB lets `Adaptive2` keep an eden of ~80 MB: two collections a visit instead of eight, for ~40 MB
more resident memory.

## The fixes
One commit per cause; allocation per warm visit on the JVM (requests' threads, median of visits 41-60 of a run, its
range; CPUs 6-13; the counts are deterministic to ~1%):

| commit | cause | before → after MB/visit |
|---|---|---|
| trim with a short list | a `boolean[256]` mask per trim/rtrim/ltrim call with a character list | 191.1 → 187.4 (−3.8) |
| `Str.join` | an `InternalByteArray` per part, an array of them and a string per int, per interpolation/implode | 187.4 → 177.7 (−9.7) |
| whole-file reads | `file_get_contents` copied each file ~5 times (buffer, doubling arrays, trim); php-src sizes from fstat | 177.7 → 172.9 (−4.8) |
| array_merge | the result grown from empty, not presized; the lone array copied where php-src returns it | 172.9 → 170.3 (−2.5) |
| packed → hash | ints boxed into an `Object[]` first (an `Object[8]` for nothing when empty) | 170.3 → 168.9 (−1.4) |
| names converted once | `Str.key` (dynamic properties, (object) casts) and callable names decoded per use | 168.9 → 161.0 (−7.9) |
| `PhpArray.copy` | a throwaway `Object[1]` per copy | 161.0 → 161.1 (within noise; ~0.2 MB expected) |
| **all** | | **191.1 → 161.1 (−30 MB, −16%)** |

Correctness found on the way, fixed in the same commits: php_charmask's `..`-range warnings (trim, ucwords,
addcslashes), array_merge's identity and internal pointer for a lone array, and `Args.array`'s "int given".

## Timed: main against this branch
`tests/gate2/native.sh measure` (one variant at a time, interleaved, the order reversed every other round), 100
visits 1 s apart on a server just started and a database just reset, the server on CPUs 14-21 under the CPU lock,
the phases builds (`jvm-phases`, `native-phases`). Before: main (f7e634a) with the measurement commit only
(`classes-base2`, `wp-http-phases-base2`); after: this branch merged with main. Three rounds (2026-11-02
03:45-05:30); a first round, run while the host was at 64-90%, was dropped. The host's median load during the runs
was 25-80% (other sessions), so the ranges are wide. A cell is the median of the three rounds (range); "warm" is
the last 20 visits.

| | cold: wait | cold: GC | warm: wait | warm: GC | warm: CPU-s/visit | memory.peak warm |
|---|---|---|---|---|---|---|
| **native, main** | 2.40 s (2.00–2.63) | 0.624 s (0.529–0.688) | 0.679 s (0.657–0.899) | 0.113 s (0.112–0.140) | 1.64 (1.54–2.47) | 846 MB (814–847) |
| **native, this branch** | 2.16 s (2.06–2.40) | 0.614 s (0.558–0.666) | 0.642 s (0.640–0.733) | 0.093 s (0.091–0.109) | 1.89 (1.82–2.17) | 844 MB (807–875) |
| native, this branch, `-Xms256m` | 2.13 s (1.86–2.35) | 0.339 s (0.331–0.485) | 0.621 s (0.560–0.720) | 0.048 s (0.036–0.052) | 2.13 (1.74–2.58) | 907 MB (879–963) |
| native, this branch, `-Xms512m` | 2.20 s (1.90–2.50) | 0.338 s (0.307–0.456) | 0.571 s (0.558–0.715) | 0.015 s (0.015–0.015) | 2.29 (1.65–2.48) | 1027 MB (1020–1042) |
| **JVM, main** | 4.70 s (4.54–6.85) | 0.075 s (0.067–0.087) | 0.495 s (0.494–0.566) | 0.003 s (0.002–0.003) | 1.30 (1.25–1.77) | 1635 MB (1562–1728) |
| **JVM, this branch** | 5.24 s (4.76–5.46) | 0.070 s (0.068–0.079) | 0.533 s (0.486–0.553) | 0.000 s (0.000–0.002) | 1.62 (1.25–1.87) | 1569 MB (1566–1580) |

"GC" is the GC pauses inside the requests' wall time (`phases_report.py`); natively the serial GC runs in the
requests' thread, so that is all of it. On the JVM G1's own threads add 0.29-0.33 CPU-s to the cold visit and
~0.005 CPU-s to a warm one.

- **Native, the fixes:** warm GC −18%, warm wait −5%, cold wait −10%; memory equal. The CPU per warm visit is mostly
  the runtime compiler, which hasn't settled by visit 100 natively (visit-phases.md); its ranges overlap.
- **Native, the minimum heap:** the GC goes where the eden sizing said it would: −48% warm at 256 MB, −84% at 512 MB
  against this branch, and the cold visit's GC halves. The warm wait follows by 0.02 and 0.07 s, within the ranges.
- **JVM:** no change beyond the noise in wait or CPU (the ranges overlap); GC was ~0 warm before. memory.peak −4%.

Gate 1 (`bench/gate1/ab.sh all 400 6 base2 after`, CPUs 14-21, CPU lock; host shared): after/before hooks 0.91
(IQR 0.86-0.95), escape 0.90 (0.86-0.95), slugs 0.73 (0.68-0.77), wpautop 0.92 (0.85-0.97), wptexturize 0.83
(0.79-1.05), blocks 1.00 (0.90-1.09), kses 0.89 (0.83-1.07); peak memory 0.91 (0.81-0.95). The checksums equal
PHP 8.5's. (The kernels interpolate, implode and trim in their loops: the join and trim fixes are on their paths.)

## One request's memory against the server's
`-Dphp.heapprobe=250` (a full collection every 250 ms), 8 request threads, 100 warm visits, then 60 s each of 1, 4
and 8 clients with 20 s idle between; `tests/gate2/heapprobe.py`. The JVM on main's build, native on this branch's:

| requests in flight | JVM: live heap above idle, median (max) | per request | native: above idle, median (max) | per request |
|---|---|---|---|---|
| 0: the server's own live heap | 96-98 MB | | 91-95 MB | |
| 1 | 2.6 MB (4.0-4.6) | 2.6 MB | 1.5 MB (4.0) | 1.5 MB |
| 4 | 10.2 MB (15.9) | 2.6 MB | 10.5 MB (14.0) | 2.6 MB |
| 8 | 21.6 MB (31.8) | 2.7 MB | 21.5 MB (27.0) | 2.7 MB |

- **A request in flight keeps 2-3 MB alive, 4-5 MB at its peak**. Its 25-30 MB of allocation per page is
  short-lived garbage.
- **Its stack is smaller still.** On the warm JVM server (NMT, `-XX:NativeMemoryTracking=summary`), all 39 threads'
  stacks together have 2.8 MB committed (43 MB reserved): the request threads' deepest WordPress stacks are a few
  hundred KB each.
- **The server's fixed cost is what is large:** ~95 MB of live heap of its own (the shared translations, the
  classes and constants every request declares from, caches), and beside the heap the compiled code (145 MB
  committed in the JVM's code heap here, its peak ~300 MB with the `SERVER_OPTS` code cache), the JIT compiler's
  arenas (peak 116 MB in NMT, libgraal's own heap beside), and the runtime. Per request, the heap a request needs
  is ~1/40 of the heap the server keeps, and ~1/300 of the process's memory.peak.

Note: concurrent clients log in as the same admin, and WordPress's `session_tokens` read-modify-write loses some of
their tokens, so some wp-admin pages answered 302 (lighter pages). That is WordPress's race (PHP has it too).

## Would virtual threads help?
`HttpMain` serves requests on a fixed pool of platform threads (`--threads`, 4 in the harness); each request builds a
context on the shared engine and runs on its thread to the end. What virtual threads would and wouldn't change:

- **The heap a request needs: unchanged.** Its live heap (2.6 MB, 4-5 MB at peak) and its allocation (160-190 MB a
  visit) are the PHP program's objects, whatever thread runs it.
- **The stack: moved into the heap, not saved.** The platform threads' stacks are small (2.8 MB committed for all
  39 threads), so there is little to save there. A virtual thread's frames are copied into heap
  chunks whenever it unmounts (a database read, a socket); Truffle's interpreter and compiled frames are deep, so
  each unmount and remount copies them, and the young collections then see them as survivors.
- **The server's fixed cost: unchanged.** The compiled code, the parsed and translated functions, the compiler and
  the runtime are per process, not per thread.
- **Throughput: little to gain.** A warm request is CPU-bound: on the JVM the requests' thread CPU is 0.44 s of a
  0.48 s visit (visit-phases.md), so more requests in flight than cores only queue. What virtual threads would buy is
  waiting cheaply on slow I/O (a remote database, HTTP calls), which these visits barely do.
- **Pinning.** A virtual thread in a native frame can't unmount, so FFM downcalls pin their carrier for the call:
  PCRE2's matches (microseconds) don't matter; libcurl's `curl_easy_perform` and `curl_multi_wait` block in native
  code for a whole transfer, so a request doing HTTP would hold a carrier thread throughout (curl would need
  `curl_multi_socket_action` driven from Java to avoid it). The MariaDB connector is plain Java sockets and would
  unmount. Since JDK 24 `synchronized` no longer pins (GraalVM 25 is JDK 25), so `PhpFunction.createCallTarget`'s
  monitor is no issue.
- **Contexts.** Not tested here: whether Truffle contexts entered on virtual threads keep their compiled code's
  thread-local fast paths, and how the native image's Loom support interacts with runtime compilation. That would
  need a prototype (an executor of virtual threads in `HttpMain`) and a run.

## What remains
- **The native policy.** `-Xms256m` or `-Xms512m` for the native server (`native.sh`'s options, or baked into the
  image with `-R:MinHeapSize=`) is a measured trade of 60-180 MB for 50-85% of the GC; not applied here.
- **Each young collection walks the runtime-compiled code** (5 ms of 10-16 natively). Less compiled code
  (partial-evaluation.md) makes every collection cheaper too.
- **Allocation of our own that is left** (JVM, the shares before the fixes): (object) casts building an object
  property by property where php-src shares the array (8 MB a visit); include lookups, two stats and a path
  normalization per include (7.5 MB); the `Object[]` arguments of calls not inlined (5.6 MB); the per-request
  function and class tables (7.3 MB, partly php-src's too); `InternalByteArray` wrappers in the other
  `Str.readBytes` users; unserialize parsing each int through a String.
- **Copies:** none found that php-src doesn't make; the holder-count overcounts in tla-cow.md (F4, the generators'
  foreach) didn't show among the hot sites.
- **Deviations seen on the way, not fixed here:** several built-ins name a given value by `gettype()`'s names in
  their errors ("integer", "NULL") where php-src uses `zend_zval_value_name` ("int", "null"): the users of
  `Convert.typeName` in ArrayFunctions, CoreFunctions, PasswordFunctions, RuntimeFunctions, UrlFunctions,
  BuiltinNodes, ObjectVarsNode, PropNode, MethodCallNode and ArrayKey.
- **Virtual threads:** a prototype, if waiting on slow I/O ever matters.
