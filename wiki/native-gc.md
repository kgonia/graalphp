# Native image: the serial GC or G1 for the WordPress server (2026-11-01)

An outside reviewer suggested trying G1 for the native image ("You need to switch at build-time"). Oracle GraalVM's
native image has the serial GC by default and G1 on Linux, chosen when the image is built (`--gc=G1`). This page
measures both on WordPress over HTTP, with the setup of [gate-2.md](gate-2.md), "WordPress on native image with our
own parser".

**Verdict: keep the serial GC.** G1 serves the cold visit 10% faster, but a warm visit 16% slower, for 36% more CPU
per visit and 55% more anonymous memory. Its pauses are shorter on the median and longer at worst. Tuned (a 50 ms pause
goal, periodic collections, a low free-heap floor), G1 is as fast as serial warm and 17% faster cold, but it still
spends 26% more CPU and 52% more memory per visit.

## The switch
- `NI_GC=G1 bench/native/build.sh` (or `NI_GC=G1` for `image.sh` in the build image) passes `--gc=G1`; the default is
  `serial`. The other options keep their place: `NI_OPTS`, `NI_MAIN`, `NI_CLASSES`.
- Fixed at build time with G1: the region size (`-H:G1HeapRegionSize`, 1 MB here) and its other `-H:G1` options.
- Left to run time: the heap size (`-Xmx`, `-XX:MaxRAMPercentage`), `-XX:MaxGCPauseMillis`,
  `-XX:G1PeriodicGCInterval`, `-XX:MinHeapFreeRatio`, `-XX:InitiatingHeapOccupancyPercent`, the GC thread counts. Its
  `-XX:PrintFlags=` lists them.
- The serial image takes `-XX:MaximumYoungGenerationSizePercent` (10% of the heap by default) and `-XX:MaxNewSize` at
  run time.
- Both log each collection with `-XX:+PrintGC`, G1 in HotSpot's format (`GC(3) Pause Young (Normal) (G1 Evacuation
  Pause) 117M->77M(156M) 7.280ms`), serial alike (`GC(3) Pause Incremental GC (Collect on allocation) 82M->42M
  60.9ms`). `tests/gate2/gclog.py` adds up a measurement's pauses.
- **The builds** (HttpMain, `wp-native`'s classes, `-J-Xmx7g`): serial 89 MB in 3 min 18 s, builder peak RSS 5.44 GiB;
  G1 100 MB in 3 min 23 s, 5.67 GiB. Both serve Gate 2's 21 pages as the JVM does: 19 identical to PHP 8.5's, and
  the post and the page without the oEmbed XML link, as everywhere.

## The measurement (`tests/gate2/native.sh measure 3 150 1`, 2026-11-01 03:09-05:13)
- **Variants:** PHP 8.5.12 for scale; the serial image (`native-main.gc`) and the G1 image (`native-g1.gc`), each at
  the harness's `-Xmx1g`; one tuned run of each:
  - serial with a young generation of up to 25% of the heap (`-XX:MaximumYoungGenerationSizePercent=25`);
  - G1 with `-XX:MaxGCPauseMillis=50 -XX:G1PeriodicGCInterval=10000 -XX:MinHeapFreeRatio=10`, as the JVM server's
    options let G1 give memory back.
  Every native variant logs its collections (`-XX:+PrintGC`). The same run measured bcrypt's variants (gate-2.md,
  "bcrypt"), so they share its baseline.
- **Method:** as gate-2.md's: three rounds, the variants interleaved and the order reversed every other round, every
  run on a server just started and a database just reset (WordPress installed afresh by wp-cli into a MariaDB of this
  session's own). The server on CPUs 14-21, the client on 0-5, the database on 22-23. A run is the cold visit, then
  150 visits 1 s apart; "warm" is the median of visits 101-150. A cell is the median of the three rounds, with their
  range.
- **The host was busy.** Other sessions kept its median CPU at 13-66% during the runs (no run passed the 75% that
  would have repeated its round). Everything is slower than on 2026-10-31: native's warm visit is 0.955 s here
  against 0.608 s then, PHP's 0.380 against 0.262. Compare within the table, not across the pages.

| | cold visit: wait, CPU | warm: wait per visit | warm: CPU-s per visit | RssAnon: cold, warm | memory.peak: cold, warm |
|---|---|---|---|---|---|
| **serial**, `-Xmx1g` | 2.94 s (2.87–3.01), 9.3 | **0.955 s** (0.947–0.985) | **6.10** (6.04–6.17) | 361, **900 MB** (818–905) | 411, 1682 MB (1638–1698) |
| serial, young ≤ 25% | 2.92 s (2.91–3.33), 9.3 | 0.974 s (0.957–1.155) | 6.18 (6.00–6.75) | 377, 822 MB (785–863) | 414, 1632 MB (1624–1692) |
| **G1**, `-Xmx1g` | **2.64 s** (2.47–2.72), 11.0 | 1.112 s (0.938–1.194) | 8.32 (7.47–9.02) | 684, 1395 MB (1360–1396) | 689, **1442 MB** (1435–1442) |
| G1, pause 50 ms, periodic | **2.43 s** (2.42–2.91), 10.1 | **0.942 s** (0.936–1.072) | 7.67 (7.46–8.10) | 693, 1367 MB (1349–1416) | 699, 1440 MB (1434–1455) |
| PHP 8.5, for scale | 1.65 s (1.33–1.80), 1.5 | 0.380 s (0.376–0.395) | 0.36 | 37, 57 MB | 85, 104 MB |

Against serial, as ratios of the medians:

| | cold visit | warm visit | CPU per warm visit | warm RssAnon | memory.peak |
|---|---|---|---|---|---|
| G1 | 0.90× (faster) | 1.16× (slower) | 1.36× | 1.55× | 0.86× |
| G1, tuned | 0.83× | 0.99× | 1.26× | 1.52× | 0.86× |
| serial, young ≤ 25% | 0.99× | 1.02× | 1.01× | 0.91× | 0.97× |

**The collections**, over a whole run (the cold visit and 150 warm ones, about 5 minutes), from `-XX:+PrintGC`:

| | pauses | pause time | median pause | longest pause | full collections | G1's concurrent marking |
|---|---|---|---|---|---|---|
| serial | 1369 (1360–1372) | 20.8 s (20.6–20.9) | 11.7 ms | 243 ms (242–277) | 21 (20–22) | – |
| serial, young ≤ 25% | 1374 (1352–1376) | 20.4 s (20.4–23.5) | 11.8 ms | 258 ms (254–298) | 19 (17–20) | – |
| G1 | 2149 (2126–2162) | 22.9 s (21.4–27.1) | 7.6 ms | 516 ms (421–670) | 11 (10–17) | 90 s (85–101) |
| G1, tuned | 2176 (2120–2187) | 21.9 s (20.8–25.2) | 7.5 ms | 460 ms (371–503) | 10 (10–15) | 86 s (84–96) |

## What the logs show
- **The serial GC keeps the heap small.** It collects every ~0.2 s, about 20 MB at a time, on a heap of 100-125 MB.
  A full collection (about 20 a run) leaves 84-89 MB and takes 180-280 ms.
  - Allowing a larger young generation changes neither the number of collections nor their times.
  - Its anonymous memory (900 MB warm) is mostly not the Java heap. memory.peak reaches 1.7 GB by visit 25, while
    the heap is at about 130 MB: the runtime compilation, which `-Xmx` doesn't cap (gate-2.md).
- **G1 fills the heap up to `-Xmx`.** Its anonymous memory is 650-750 MB after the cold visit, and the heap runs at
  0.9-1 GB before each collection after that.
  - It is marking concurrently almost all the time: 375 cycles and 90 s of marking in a 5-minute run. That is where
    its extra CPU goes: 2.2 more CPU-s a visit.
  - When the heap is full it compacts: 10-17 full collections of 170-670 ms. They leave 380-855 MB, where the serial
    GC's leave 84-89 MB, so G1 keeps alive something the serial GC frees; what, isn't measured here.
  - 234 young collections in a run report an "Evacuation Failure: Pinned".
  - The 50 ms pause goal and the periodic collections don't make it give memory back: RssAnon is the same.
- **The peak is G1's only gain.** memory.peak is 1.44 against 1.68 GB, although warm RssAnon is 1.4 against 0.9 GB.
  The serial image's peak comes early (visit 25), G1's at the end; why the early peak is lower under G1 isn't
  measured here.

## Open
- What G1 keeps alive: its full collections leave 4-10 times what the serial GC's leave. Soft references
  (`-XX:SoftRefLRUPolicyMSPerMB`) are a candidate. Then whether a smaller `-Xmx` (or `-XX:MaxRAMPercentage`) keeps
  its marking and full collections down without failing.
- A larger G1 region (`-H:G1HeapRegionSize`, a rebuild) for the humongous allocations its log shows.
- Neither GC changes what blocks native image for WordPress (gate-2.md): PHP code runs at 0.72× the JVM warm, and
  the CPU per visit stays high.
