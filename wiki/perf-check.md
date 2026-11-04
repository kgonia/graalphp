# Performance check after the correctness batch (roadmap step 3, 2026-11-04)

## Question
Did the correctness batch merged after `c7cd6dc` (#32, #33, #34, #36, #37, #38, #39, #40, #41, #42 and #35), none of
it measured for performance, cost Gate 1, Gate 2 or WooCommerce time or memory? The changes to watch were #33's
visibility checks in dispatch, #34's PCRE /u cache, #39's type checks on every call, #42's hook and lazy-object
checks on property access.

## Short answer
On a quiet host main is slower than the base in two of the three workloads. Under the night's load (most of the
runs) the differences sank into noise.
- **WooCommerce: -11 to -13 % orders/s on main** (CPU-s per page +13-15 %), 2 quiet rounds. **The cause is #35
  undoing #41 for isset reads.** #35 split the element read into `ElementReadNode` (warns) and `ElementIfSetNode`
  (isset, `??`, a write's container). The new node kept the `@ReportPolymorphism` that #41 had removed, so
  `WP_Hook::add_filter`'s `isset($this->callbacks[$priority])` splits `add_filter` and `add_action` per caller
  again. Gate 2's split copies: 2,242 on the base, 1,042 after #41, 2,260 on main. The one-line fix (draft PR #46)
  brings WooCommerce back to the base's level: +2-13 % orders/s against main in each of 3 quiet rounds.
- **Gate 2: warm visits +7-14 % on main** (the requests' CPU +12-22 %), 2 quiet rounds; most of it is already at
  p38 (#33 and #38). One located part: `usort`'s self time is 6.7x the base's, from #38's exact `zend_sort` order,
  about 12 of the 60-70 ms per visit. The fix doesn't change Gate 2. **The cold visit is 25-30 % faster** on main
  (7.2-7.8 s against 10.3-10.9 s). **memory.peak +5-8 %.**
- **Gate 1: no step.** Base against main, 8 rounds: every kernel within 0.98-1.06, kses 1.06. A bisection over every
  merge commit (kses and hooks, 6 rounds) finds no build outside 0.92-1.08 of the base. Peak memory 1.06 / 0.98 in
  the two series. Every checksum equals PHP 8.5's.
- **Correctness on main:** Gate 2's pages 19/21 equal to PHP's. The WooCommerce walk differs from PHP 8.5 exactly
  where the base's does (the oEmbed links, the analytics import). Smoke: 181 of 185, and the 4 failures are the
  Windows host's paths and object handles (D17).
- The baseline had to be `4214f6b` (#32), because `c7cd6dc` doesn't compile.

## Setup
- **Builds.** The base is `4214f6b`: `c7cd6dc` doesn't compile (`IncludeNode.resolve` refers to `this` in a static
  method, a semantic conflict of #26 and #30) and #32 is that compile fix; #26 is in `c7cd6dc` already. Main is
  `9ae85cb` (#35 merged). For the bisection, every merge commit on main's first-parent line: p33 (`00e398d`), p38
  (`e743196`), p41 (`318c721`), p36 (`92d9de1`), p39 (`a49e134`), p34 (`770cd69`), p37 (`1a928ec`), p40
  (`722676f`), p42 (`b29eafd`, main before #35); and fix (`9ae85cb` with `ElementIfSetNode` reporting no
  polymorphism). Each built with `mvn -o compile` into `target/classes-NAME`.
- **Harness:** `bench/perf-check/runs.sh` (Gate 1 through `bench/gate1/ab.sh`, Gate 2 through
  `tests/gate2/native.sh measure`, WooCommerce through `tests/gate2/woo.sh load`), the reports
  `bench/perf-check/report.sh` (tables in `bench/perf-check/*.md`). Every timed server on CPUs 14-21 under the CPU
  lock, taken per Gate 1 hold (2 rounds), per Gate 2 run and per WooCommerce server and let go after it; the order
  reversed every round. New here: `threadcpu.py` samples the server's threads every second from outside the
  container (CPUs 0-5), so Gate 2's and WooCommerce's CPU per thread class comes from the timed runs themselves.
- **The host was loaded most of the night** (other sessions' builds and timed runs, two Vite dev servers, the IDE):
  its CPU outside 14-21 at 40-75 % before most runs, and a Gate 2 run's median host load 42-93 % (the server's own
  8 CPUs are about a third). Gate 2's warm wait moves by about 0.09 s per 10 % of host load, more than the builds
  differ, so its table below also gives the load-adjusted difference (least squares over all runs: the metric
  against the host load, one shift per build). From 07:20 to about 11:30 the host was quiet (5-25 % outside 14-21):
  WooCommerce's rounds 4-8 and Gate 2's rounds 11-13 ran then. At 12:02 Docker Desktop restarted (the box was short
  of memory, another session's 24 GB job had just started); round 13's last two runs were lost, the databases
  restarted.

## Gate 1
`runs.sh gate1 4 400 base main`: 8 rounds, 400 iterations, the steady state each run's median of its last quarter
(`bench/perf-check/gate1.md`, with the warm-up curves).

| kernel | base ms | main ms | main / base (IQR) | checksums = PHP |
|---|---|---|---|---|
| hooks | 19.73 | 19.68 | 0.98 (0.93-1.15) | yes |
| escape | 2.20 | 2.25 | 1.00 (0.94-1.08) | yes |
| slugs | 1.80 | 1.88 | 1.01 (0.91-1.02) | yes |
| wpautop | 6.96 | 7.28 | 0.99 (0.88-1.08) | yes |
| wptexturize | 4.54 | 4.73 | 1.02 (0.96-1.04) | yes |
| blocks | 6.69 | 7.25 | 1.02 (0.97-1.05) | yes |
| kses | 38.84 | 42.25 | 1.06 (1.01-1.07) | yes |
| peak memory MB | 1,603 | 1,795 | 1.06 (1.04-1.16) | |

The first series, against b29eafd (main before #35), measured wptexturize 1.10, blocks 1.10 and kses 1.13 with
IQRs of ±10 %, and peak memory 0.98; the series against main doesn't repeat those. The warm-up curves (iterations
1-400) overlap: the first iteration within 1-8 % (kses 834 → 899 ms in the first series), the plateau reached at the
same iteration. The bisection over every merge commit (`KERNELS=kses,hooks runs.sh gate1 3 300`, 6 rounds, the
host quiet for 4 of them) finds no step: kses 0.92-1.08 of base for every build (main 0.98, fix 1.07), hooks
0.89-1.20 (main 1.01), each with an IQR of ±15 %; peak memory 0.88-1.01. Gate 1 shows no regression this check can
resolve.

## Gate 2
Paced visits from a cold server, 200 visits, the warm window visits 151-200 (`bench/perf-check/gate2.md`: every
run, the medians, the CPU per thread class, the compilations by tier, memory and libgraal). 39 runs in 12 rounds;
rounds 1-10 mostly at a host load of 50-93 %, rounds 11-13 with the host quiet.

**The quiet rounds** (same host load within a round; warm = visits 151-200; CPU-s per visit; "requests" is the HTTP
threads' CPU, i.e. the PHP code itself, from `threadcpu.py`):

| round, host % | build | cold visit s / CPU-s | warm wait s | warm CPU-s | requests | Truffle compiler | memory.peak MB |
|---|---|---|---|---|---|---|---|
| 11, 42 | base | 10.46 / 58.0 | 0.426 | 0.68 | 0.40 | 0.48 | 1,357 |
| 11, 42 | main | 7.76 / 42.9 | 0.485 | 0.88 | 0.49 | 0.58 | 1,462 |
| 11, 34 | fix | 6.80 / 37.7 | 0.521 | 0.91 | 0.52 | 0.62 | 1,351 |
| 12, 49 | base | 10.33 / 58.5 | 0.443 | 0.73 | 0.42 | 0.49 | 1,428 |
| 12, 49 | main | 7.16 / 40.3 | 0.475 | 0.86 | 0.47 | 0.55 | 1,504 |
| 12, 54 | fix | 10.30 / 58.0 | 0.452 | 0.73 | 0.44 | 0.51 | 1,494 |
| 13, 43 | base | 10.88 / 59.4 | 0.456 | 0.78 | 0.43 | 0.54 | 1,532 |
| 13, 43 | p38 | 11.46 / 63.7 | 0.469 | 0.84 | 0.49 | 0.58 | 1,428 |

- **The warm visits are 7-14 % slower on main** (0.475-0.485 s against 0.426-0.443), and the requests' own CPU is
  12-22 % higher; the Truffle compiler's share hardly moves. The rest of round 13 (p41, p39, p40, p42, main) ran with
  the host at 63-77 % and a swapping box (p40's cold visit 32 s), and Docker Desktop restarted before p42: no usable
  steps beyond p38.
- **p38 (#33 and #38) already has most of it:** requests 0.49 against base's 0.43 at the same load (+14 %). Truffle's
  CPU sampler on two diagnosis servers (`bench/perf-check/sampler.md`) finds one built-in grown out of line:
  **`usort`, 6.7 times its self time on base** (220 → 1,480 ms) while the total fell to 0.69 (main's server was the
  less loaded). #38's `0afebca` sorts with php-src's `zend_sort` (insertion sort under 17 elements, the stable
  fallback on the bucket order) so the comparison callback runs in php-src's order, observable behaviour; the cost
  is ours (the comparator chain, records per bucket, the callback called from a boundary). wp-admin's `sort_menu`,
  `wp_list_sort` and the block templates' sorts call it every request. It explains about 12 ms per visit of the
  60-70 ms; the rest is not located (no profile of compiled PHP code that attributes it; candidates: #33's calling
  scope in method dispatch, #38's argument checks).
- **The cold visit is 25-30 % faster on main** (7.2-7.8 s against 10.3-10.9, 40-43 CPU-s against 58-59). Not
  bisected (fix's 10.30 s in round 12 says it is not stable either).
- **memory.peak +5-8 %** in the quiet rounds (1,462-1,504 against 1,357-1,428 MB); over all runs the median
  warm peak is 1,401 MB for base (12 runs), 1,505 for main (8).
- Under load (rounds 1-10) the comparison inverts: base's larger compile queue (2,240 split copies) loses more to a
  loaded host, so main looks as fast or faster. The least-squares fit over all runs (the metric against host load,
  a shift per build) gives main +1 % warm wait, +3 % requests CPU, +6 % memory.peak: an average over conditions, not
  the quiet number.

**Splits.** Gate 2's split copies compiled per run (TraceCompilation): base 2,232-2,248, p38 2,272-2,276, p39-p42
1,013-1,042, main 2,257-2,273, fix 1,020. #41 halved them, #35 brought them back.

## WooCommerce
`runs.sh woo`: one server per run, 300 s warm-up at 16 users half buying, then 120 s of 16 users every visitor
buying (`bench/perf-check/woo.md`). Rounds 1-3 base and main (the host loaded, 64-78 % outside 14-21), 4-6 main and
fix, 7-8 base and main (the host quiet, 5-25 %). Every run: 0 errors, the orders in the database as k6 placed them.

| round (host) | build | orders/s | p50 ms | p99 ms | CPU-s/page | memory.peak MB | libgraal largest heap MB |
|---|---|---|---|---|---|---|---|
| 1-3 (loaded) | base | 1.17 (0.74-1.29) | 1,553 | 3,592 | 0.83 | 2,700 | 1,038 |
| 1-3 (loaded) | main | 1.02 (0.88-1.33) | 1,730 | 3,466 | 0.95 | 2,531 | 909 |
| 4 | main / fix | 2.16 / 2.20 | 866 / 856 | 1,604 / 1,666 | 0.448 / 0.440 | 2,816 / 2,578 | |
| 5 | main / fix | 1.86 / 2.10 | 969 / 865 | 2,021 / 2,050 | 0.521 / 0.462 | 2,606 / 2,803 | |
| 6 | main / fix | 2.03 / 2.08 | 923 / 877 | 1,722 / 1,795 | 0.478 / 0.464 | 2,467 / 2,470 | |
| 7 | base / main | 1.98 / 1.76 | 944 / 1,063 | 1,746 / 1,985 | 0.488 / 0.552 | 2,363 / 2,763 | |
| 8 | base / main | 2.08 / 1.81 | 892 / 1,005 | 1,683 / 3,115 | 0.466 / 0.537 | 2,574 / 2,439 | |

- **On the quiet host main is 11-13 % below base** (orders/s; CPU-s per page +13-15 %), in both rounds. Under load
  (rounds 1-3) the order flipped between rounds and the medians are within noise.
- **fix recovers it:** 2-13 % more orders/s than main in each of its rounds, 2.08-2.20 orders/s, base's level
  (1.98-2.08). The likeliest reading: #35's `ElementIfSetNode` split copies are what #41 removed for WooCommerce
  (5,300 copies of `add_filter`, 97 % never compiled, "Which splits pay"), and they cost again.
- memory.peak: within the runs' spread (2.36-2.82 GB on every build; a burst of libgraal's, woocommerce.md).
- CPU per thread class over the server's life (`woo.md`): main 3,340 CPU-s against base's 3,446 in the loaded
  rounds, the compiler threads the same (666 and 670 CPU-s).

## Correctness on main
- Gate 1: every kernel's checksum equals PHP 8.5's, on every run of every build.
- Gate 2's pages: 19/21 equal to PHP 8.5's (the two oEmbed links, as before).
- The WooCommerce walk against PHP 8.5: every status the same; 12 bodies differ, 11 of them by the oEmbed `<link>`
  (no ext/simplexml, as on 2026-11-02) and `admin-orders` by the order of orders 37 and 38 in the list; the rows as on
  2026-11-02 (the analytics import of order 38, `memory_get_usage()`), and the site health transient
  (`good` 14 against 15). The base's walk (`4214f6b`) differs in the same 12 bodies and the same tables (its
  analytics rows differ by 3-4 lines instead of 1): nothing new on main.
- Smoke on the host (Windows, `tests/smoke/run.sh`): 181 of 185. Two are the host's paths (`closure-debug`,
  `closure-scope` print `__FILE__` as `C:\...` where PHP's container has `/w/...`), two are object handles
  (`lazy-objects`, `property-hooks`: `#5` where PHP prints `#4`, a freed object's handle not reused, D17).

## Proposals
1. **Merge #46 (`ifset-no-polymorphism`)**: one annotation, the owner's split policy of #41 applied to #35's
   node. WooCommerce back to the base's throughput on a quiet host; Gate 2 unchanged; `mvn test` passes.
2. **`usort` and its kin as nodes, or the comparison loop outside the boundary.** The comparisons have to stay in
   php-src's order (the callback can see it), but the comparator lambdas, a record per bucket and a call from a
   `@TruffleBoundary` into the callback per comparison are internals (D18). A built-in sort that calls the callback
   through a cached `DirectCallNode` in a compiled loop (`Comparator` chain flattened, buckets in two arrays) would
   take most of the 6.7x. Bigger than this check: a proposal.
3. **Find the rest of Gate 2's warm gap at p38.** Measure p33 (#33 alone) against base and p38 on a quiet host:
   #33 adds the calling scope to method dispatch's guards (`MethodSite.isKeyed`, `root.sameScope`), #38 argument
   checks to many built-ins (`Args.string`, `deprecateNull`). The CPU sampler can't attribute compiled PHP code
   finer than the file's top level.
4. **Gate 2's memory.peak +5-10 %**: not bisected to one PR; the warm peak rises from p39 on (1,344 MB at p38, 1,434 at
   p39 and p40, 1,565 at p42; single runs). The candidates are more code compiled per request (#36's and #38's
   built-ins, #39's type checks) and more prelude PHP (#40's and #42's reflection and SPL), not a leak (RssAnon
   after the warm visits is within 4 %).
5. **Measure on a quiet host.** This night's host load moved Gate 2's warm wait by up to 2x and the cold visit by
   2.7x (6.7 to 17.9 s for the same build); differences under 5-10 % need the box to itself.

## Files
- `bench/perf-check/runs.sh`, `report.py`, `report.sh`, `threadcpu.py`: the harness and the reports.
- `bench/perf-check/gate1.md`, `gate2.md`, `woo.md`, `hostload.md`: the tables and the host's load per run.
- `bench/perf-check/sampler.md`, `samptop.py`: Truffle's CPU sampler summed over the requests, base against main.
- `tests/gate2/woo.sh`: `CPU_LOCK_HELD=1` lets a caller hold the CPU lock (runs.sh polls it every 2 s; woo.sh polls
  once a minute and lost every race to another session's back-to-back runs).
