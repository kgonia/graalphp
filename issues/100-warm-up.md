# 100: Warm-up: one context needs 2.5–3.5 minutes and 9 CPU-minutes of compilation to reach its level; a server at eight users compiles through all of its first twelve

**State:** Open. Found 2026-11-10 during [wiki/beat-php.md](../wiki/beat-php.md) (the K8 loop: one context, the
front page rendered N times in a row by one thread, CPUs 14-21). PHP with OPcache and its JIT is at its level
within the first hundred renders (first decile 9.3 ms over renders 0–500, 9.4 over 500–1000).

**What happens, one context** (`trace-long`: the servers' JVM options and
`-Dpolyglot.engine.TraceCompilation=true`, 8000 renders in 293 s; `tests/gate2/k8-cli.sh one trace-long 8000 /`):

- **The curve.** First decile of the render time per 10 s of the loop: 229 ms, 60, 48, 39, 37, 35, 30, 31, 28 (at
  80 s), 27, then 29–34 from 100 to 160 s, **20.9 at 170 s**, 20.2–21.8 after, 27.7 at 220 s, 20.3 from 230 s to
  the end. The first hundred renders: median 85 ms (75–104 in the night's other runs). Within 5% of the final
  level from render 3600 (168 s). PHP renders 17,000 pages in that time.
- **What is compiled.** 2632 compilations: 1662 first-tier (143 s of compiler time) and 970 last-tier (398 s, 0.41 s
  each; 960 targets, 10 of them twice); 47 MB of installed code. **541 s of compiler time for one page.**
- **The bound is the compiler's throughput.** The first tier is done by 90 s (56–363 compilations per 10 s until
  then). The last tier compiles 52–100 targets per 10 s from 70 s to 180 s, with 88, 86 and 78 s of compiler time
  per 30 s of wall time between 90 and 180 s: its queue is saturated at three threads' worth for a minute and a
  half. The level is reached when that queue drains, at 166–168 s in both traced runs.
- **A lower threshold makes it later.** `-Dpolyglot.engine.LastTierCompilationThreshold=1000` (default 10,000)
  reached the same level at 196–201 s: more targets enter the queue, the hot ones wait behind them.
- **A second burst.** At renders 5000–5300 (212–240 s) 139 functions that run once or twice per render reach the
  last tier's threshold together (`do_blocks`, `get_the_term_list`, `locate_block_template`,
  `render_block_core_post_template`, `add_action`, `get_user_meta`, the K8 plugin's closure): 48 s of compiler time
  in 30 s. During it no render takes less than 24–28 ms for 10–20 s (level: 20.2). The same in the run with
  `-XX:-UseCodeCacheFlushing` (26.2–28.1 ms). Why the level itself rises while the compiler threads work, before
  the step and here: see "Open".
- **More compiler threads bring the level earlier** (`par-ct6`: the best case's options, ParallelGC with a 2 GB
  heap and the threshold of 1000, and `-Dpolyglot.engine.CompilerThreads=6`): within 5% of the level from render
  2550 (123 s) against render 5450–5650 (201–208 s) in three runs without. The level is the same (first decile
  18.2 ms over renders 3000–4000, 18.59 over the last thousand against 18.07–18.28). Before the level it is
  slower (first decile 32.6 ms over renders 1000–2000 against 26.7–27.3): seven busy threads on eight CPUs, see
  "The machine" below. 7000 renders take the same 238–240 s either way; the peak is 2728 MB against 2339–2404.
- **`WP_Query::get_posts` never compiles.** Its first-tier compilation fails after 9.4 s: "Code installation
  failed: code is too large" (HotSpot's `JVMCINMethodSizeLimit`, [094](094-l0-red-baseline.md)). It runs in the
  interpreter in every render of every run. With the limit raised (`par-big`: the best case's options and
  `-XX:+UnlockExperimentalVMOptions -XX:JVMCINMethodSizeLimit=4194304`) it compiles: 10.0 s in the first tier
  (512 KB of code), 9.4 s in the last (520 KB), nothing fails. The level moves by 1%, inside the spread between
  runs: 18.07 / 19.24 / 22.50 ms (first decile / median / mean of the last thousand) against 18.26–18.28 /
  19.40–19.53 / 22.53–22.91; the warm-up is the same (render 5450, 205 s), the peak 2389 MB.

  **Corrected 2026-11-10** ([wiki/ceiling.md](../wiki/ceiling.md), "The page: where a render's time is"): the
  failure is G1's. With G1 (`trace-one`, `trace-long`, `trace-noflush`, the servers) the function is 765 KB of
  machine code, over the limit; with ParallelGC (the `par-*` runs, the best case) it is 512–520 KB at that commit
  and installs without the flag, so `par-big`'s 1% compared two runs that both had it compiled. Measured under G1
  on the build of `3eb6dacf` (the best case's other options, two runs a side, 8,000 renders): 20.23 and 20.22 ms
  without the flag (2416–2512 MB), 19.30 and 19.58 ms with it (2553–2614 MB): 3.9%. ParallelGC beside them:
  18.15 ms (2316–2558 MB), so G1 costs this page 11.5% as the servers run it and 7.1% with the limit raised.
- **Profile resets ("opt reprof" in the trace).** 39 of them: HotSpot's code-cache sweeper unloads a compiled
  method as cold, Truffle finds the target's profile reset when it next prepares a compilation
  (`HotSpotOptimizedCallTarget.prepareForCompilation`), and the target starts again from the interpreter and from
  zero counts. 25 fall between 115 and 154 s (the first 6 s after G1's
  `Pause Young (Concurrent Start) (CodeCache GC Threshold)`), 7 at 222–230 s. They hit hot functions:
  `WP_Object_Cache::get` (last tier at 49 s, reset at 117 s, first tier again at 120 s, last tier at 142 s),
  `WP_Hook::do_action`, `apply_filters`, `WP_Post::__construct`; `WP_Hook::add_filter` and
  `WP_Theme_JSON::get_raw_data` are reset at 226 s and not back in the last tier at the end of the run.
  **They are not what delays the level here:** with `-XX:-UseCodeCacheFlushing` there are none, and the curve is
  the same (the step at the same second, the same second burst, the last thousand renders 20.44 / 21.98 / 25.67 ms
  by first decile / median / mean against 20.21 / 21.54 / 24.83).
- **G1 marks without a pause between cycles.** With the servers' options (`-Xmx1g`, `MinHeapFreeRatio=10`,
  `MaxHeapFreeRatio=30`, `G1PeriodicGCInterval=10000`) the log shows 30–47 concurrent cycles per 10 s
  (Concurrent Start, Remark, Cleanup, Prepare Mixed, Mixed) from before 140 s of uptime. In `trace-long` they stop
  at 215 s and the median render falls from 25–37 ms to 21.7–22.2 with the first decile where it was (20.0–20.4);
  in the run without flushing they go on past 260 s (median 32–38 ms there). Likely because the heap is kept
  nearly full on purpose, so every young collection ends above the marking threshold. ParallelGC with a 1.2 GB
  nursery has no such phase (101 pauses, 462 ms in a whole run) and a level 9% lower, for 1 GB more memory
  ([wiki/beat-php.md](../wiki/beat-php.md), section 5).
- **One run stayed at twice the level for four minutes.** With JFR and the trace both on (`jfr-long`) the first
  decile was 47–49 ms over renders 7000–11000, then 22 ms to the end: no compilation during the four minutes, 62
  profile resets before them, 241 last-tier compilations at their end. Seen once, only with JFR on; the run
  without flushing was not repeated under JFR, so the resets are a suspect and no more.
- **The machine.** Its cores slow down as more of them are busy ([101](101-machine-two-speeds.md)): 20 ms of
  work takes 1.00–1.02× its fast time by the first decile with up to five busy cores, 1.12–1.15× with six, 1.30×
  with seven, 1.40× with eight. One context that is compiling keeps four to six busy (the render thread, three
  compiler threads at 2.6–2.9 threads' worth, G1's concurrent threads): 1–15% of its first decile. The rise seen
  while the last tier's queue is busy is 30–45%, so at most a third of it is the cores' frequency; the engine's
  part and the cores' shared cache were not separated. With six compiler threads seven cores are busy, and
  `par-ct6`'s 21% before its level is inside that row's 1.30×.

**What happens, later contexts on a shared engine** (the server's kind: one engine, a context per request):

| | boot (to `wp`) | first render | first hundred renders, median | at the level from |
|---|---|---|---|---|
| the first context | 3.8–4.5 s | 2.2–3.0 s | 75–104 ms | render 3600–5000 (170–200 s) |
| the second, after the first's 6000 renders (`new-shared`) | 603 ms | 516 ms | 88.8 ms | render 550–600 (27–29 s) |
| the sixth, after five of 1200 renders each (`new-ctx6`) | 78 ms | 93 ms | 31.4 ms (renders 2–50: first decile 28.7) | render 100 (3 s) |
| php-cli, a cold process (no file compiled yet) | 0.41–0.55 s (0.31 s without its JIT) | 146–214 ms (34 ms without its JIT) | 10.3–13.2 ms | the first hundred |

The compiled code carries over, more of it with every context. A context's first render stays 4× its level even
in the sixth (93 ms against 21): caches that are the context's own are filled again. A served request is exactly
that render.

**What happens in the server** (nginx → FastCGI, eight users; `jvm-fcgi.trace`: the compilation trace on through
720 s of warm-up, ≈ 28,000 requests at 39.2 pages a second; `tests/gate2/k8_complog.py` on the server's log):

- **8322 compilations** against one context's 2632: each request is a new context that runs the boot and one
  first render, so the boot's functions are hot too. Eight request threads and three compiler threads share eight
  CPUs.
- **The last tier's queue never drains.** The first tier is done after four minutes (47–56 s of compiler time per
  30 s until then). From 240 s to the end of the warm-up the last tier takes 65–89 s of compiler time per 30 s,
  96–237 compilations: saturated, as in one context, for eight minutes instead of one and a half.
- **A third of the server's CPU is the compiler.** ≈ 2014 s of compiler time in the 720 s (≈ 1500 s last tier,
  ≈ 514 s first tier): 2.8 of its 7.5 busy cores, ≈ 71 of its 193 CPU-ms per page. The "pages a second" of a
  twelve-minute run (39–42) is an average over that; the steady state was not reached and not measured.
- **The measurement after the warm-up starts with a compile burst of its own.** The first measured page's hot
  render falls from 45–49 ms to 26–29 ms within its own K8 block in every served run: the K8 path (a page rendered
  again inside one request) is new code to a server warmed by plain requests, and its first block takes 143
  CPU-seconds in 45 s of wall time. The night's runs added a defect to that: their K8 requests ended in `exit`,
  which invalidates the compiled code of every function on the stack ([103](103-exit-deopt-cycle.md): 183–185
  CPU-seconds in 49–50 s, and the block ends at 25 ms where it ends at 22.6 without the `exit`; 40.2 → 22.9 ms by
  the block's quarters).

**Why it matters:** [beat-php.md](../wiki/beat-php.md) compares the engines at their levels; this is the time
before the level. A restart or a deploy costs minutes at 2–4× the level and 9 CPU-minutes per page type, against
none for php-fpm. Short benchmarks of this engine measure the warm-up: every "steady" number taken before render
5000 of a context, or before many minutes of a server, is still moving.

**Open:**

- The server's steady state: how long at eight users until its last-tier queue drains, and its throughput then.
  Twelve minutes are not enough. More compiler threads bring one context's level earlier (above); in the server
  they would take the requests' cores. A warm-up at a lower load was not tried.
- Why the first decile is 30–45% above the level while the last tier's queue is busy (150–165 s and 220–230 s),
  with the first-tier code of every function still installed: at most a third is the machine (above). The rest
  is open.
- `WP_Query::get_posts` in the interpreter with G1: with a larger `JVMCINMethodSizeLimit` it compiles, for 3.9%
  of a render in one context (the correction above). A root that is split would not need the flag, and ParallelGC
  does not need it. Not measured in a server: the flag, and ParallelGC at the servers' heap with several contexts.
- The 139 once-per-render functions of the second burst: they pay 48 s of compilation for 10–20 s at +30%; they
  are a third of a render's functions by count.
- Nothing is kept between JVM runs. Truffle's engine cache needs a native image
  ([089](089-native-image-build-options.md)).
- The servers' G1 options were chosen for the footprint
  ([gate-2.md](../wiki/gate-2.md)); they put G1 in permanent marking under a rendering load.
