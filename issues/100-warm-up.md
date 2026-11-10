# 100: Warm-up: one context needs 2.5–3.5 minutes and 9 CPU-minutes of compilation to reach its level; a server at eight users is still warming after twelve

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
- **`WP_Query::get_posts` never compiles.** Its first-tier compilation fails after 9.4 s: "Code installation
  failed: code is too large" (HotSpot's `JVMCINMethodSizeLimit`, [094](094-l0-red-baseline.md)). It runs in the
  interpreter in every render of every run.
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

**What happens in the server** (nginx → FastCGI, eight users): the K8 block of the first page measured after 720 s
of warm-up (≈ 29,000 requests at 40.8 pages a second) still falls from 45–47 ms to 26–29 ms per hot render within
its own thousand renders. Eight request threads and the compiler threads share eight CPUs, and each request is a
new context that runs the boot and one first render.

**Why it matters:** [beat-php.md](../wiki/beat-php.md) compares the engines at their levels; this is the time
before the level. A restart or a deploy costs minutes at 2–4× the level and 9 CPU-minutes per page type, against
none for php-fpm. Short benchmarks of this engine measure the warm-up: every "steady" number taken before render
5000 of a context, or before many minutes of a server, is still moving.

**Open:**

- More compiler threads for one context (`-Dpolyglot.engine.CompilerThreads=N`): if the queue is the bound, the
  step should come earlier. Measured below when the run is in.
- Why the first decile is 30–45% above the level while the last tier's queue is busy (150–165 s and 220–230 s),
  with the first-tier code of every function still installed. Either the machine (its fast mode's frequency with
  four cores busy instead of one, [101](101-machine-two-speeds.md)) or the engine. The parallel calibration below
  decides.
- `WP_Query::get_posts` in the interpreter: a larger `JVMCINMethodSizeLimit`, or a root that is split.
- The 139 once-per-render functions of the second burst: they pay 48 s of compilation for 10–20 s at +30%; they
  are a third of a render's functions by count.
- Nothing is kept between JVM runs. Truffle's engine cache needs a native image
  ([089](089-native-image-build-options.md)).
- The servers' G1 options were chosen for the footprint
  ([gate-2.md](../wiki/gate-2.md)); they put G1 in permanent marking under a rendering load.
