# Can Truffle PHP beat native PHP on WordPress? The overnight proof (2026-11-09/10)

**Question (owner, 2026-11-09 22:00):** by 07:00, prove whether native PHP (php-fpm 8.5.12, OPcache, tracing JIT) can
be beaten on WordPress or WooCommerce. Scope: plain WordPress (Gate 2's site through nginx and FastCGI), the
deployment both engines are measured in; WooCommerce only if a win shows on plain WordPress first.

**Status:** measurements done, 2026-11-10 06:00. **Verdict: no.** A hot render of the front page takes 1.94× PHP's time in the
engine's best case (one context kept alive, 2 GB heap; 1.83–1.95× on each of five pages) and 2.1× as served
(2.4–2.5× when the measured requests end in `exit`: a defect of the engine found on the way,
[103](../issues/103-exit-deopt-cycle.md)). The verdict and the evidence are under Results.

## What was known before tonight

| fact | number | source |
|---|---|---|
| the part of a request Graal can speed up (PHP code and built-ins, not the database, I/O, PCRE) | 57–73% of response time, 79–85% of CPU; ceiling 2.3–3.7× on response time | Gate 0, PHP 8.5 JIT, 2026-11-06 |
| hot WordPress functions, one context, steady state | 0.77–1.65× PHP's time (hooks 1.16, escape 0.77, slugs 0.85, wpautop 1.01, wptexturize 1.65, blocks 0.94, kses 1.49) | [gate-1.md](gate-1.md), 2026-11-04 |
| serving WordPress through nginx, 8 workers | 0.40× PHP's pages/s at 1 user, 0.33× at 8; CPU-ms/page 66–73 against 24–32; memory.peak ~2.0 GB against ~0.15 GB | `native.sh load`, 2026-11-09 |

So there is room in principle, the kernels are near parity, and serving is 3× behind. What is not known: whether
the serving gap is per-request overhead (a context per request: declarations, class linking, cold caches, which a
deployment could remove) or the compiled code itself on WordPress's real paths.

## The experiment: K8

`tests/gate2/k8-plugin.php` (a must-use plugin) and `tests/gate2/native.sh k8 ROUNDS VARIANT...`.

A request with `?k8=N` runs as any other up to `template_redirect`, then runs what `wp-blog-header.php` runs after
the bootstrap (`wp()` and `template-loader.php`) N times inside the same request, each into a buffer, and answers
with JSON: when each bootstrap hook ended, and per iteration its time, its output's length and crc32, and its
queries. Between iterations the hooks, the registered scripts and styles and the style engine's stores are put back
to their state at `template_redirect`, so every iteration prints the page again. The request then ends in `exit`;
with `&k8noexit=1` the callback returns and WordPress's own render follows, its output replaced by the JSON (what
an `exit` costs our engine: [103](../issues/103-exit-deopt-cycle.md)).

- **boot:** the request's start to the end of `wp_loaded`.
- **first render:** iteration 0. Its output is byte for byte the page a visitor gets (checked on PHP).
- **hot render:** iterations 3 and up: the same PHP code with the request's caches warm (the object cache, so no
  queries; theme.json; the registries). 95% of the page is printed again (the script modules' tags and the emoji
  loader's inline script are printed once per request). This is the engine on WordPress's rendering code alone:
  the best case for a JIT, and what a deployment that kept a booted WordPress between requests would run.

Both engines run the same plugin; the iterations' lengths and checksums are compared between them. One user, the
server on CPUs 14-21 under the CPU lock, after a k6 warm-up at 8 users (12 minutes for the JVM, 1 for php-fpm),
variants interleaved. Per page 200 plain requests, then 100 with `k8=10`; a block's second half counts.

**Reading the result:**
- hot render faster than PHP's: the compiled code wins on WordPress's own code, and the serving gap is per-request
  overhead. Beating PHP is then a question of how much of the request can be kept between requests.
- hot render slower than PHP's: no deployment change wins; the ratio is the factor the engine itself must gain, and
  the per-function profile of the loop (GuestSampler against Excimer on PHP) says where.

## Results

### Verdict

**No: as of 2026-11-10 this engine does not beat native PHP on WordPress, in no deployment and with no JVM option
tried, and the gap is the compiled code itself, a factor of two.**

| front page, one render | first decile | median | mean | fastest render | memory.peak |
|---|---|---|---|---|---|
| PHP 8.5.12 (php-cli, OPcache, tracing JIT) | 9.39 ms | 9.81 ms | 10.43 ms | 9.16 ms | 89–97 MB |
| our engine's best case: one context kept alive, compilation finished, 2 GB heap, ParallelGC | 18.26 ms | 19.53 ms | 22.91 ms | 17.49 ms | 2404 MB |
| ours over PHP's | **1.94×** | **1.99×** | **2.20×** | 1.91× | 25–27× |

- **The best case is the upper bound of every deployment.** The loop keeps a booted WordPress and renders the
  page again and again in one context: no request, no boot, the compiler done. With the default collector (G1) it
  is 2.13×. The fastest of 98,200 front-page renders on the JVM (17.42 ms) is slower than 96% of PHP's renders at
  its level. Section 2.
- **As served** (nginx → FastCGI, a context per request, eight workers, 12 minutes of warm-up): a hot render is
  2.12× php-fpm's (2.07–2.17× on each of five pages; one run), a plain request 2.2–2.3×, the boot 2.3×; 39–42
  pages a second against 149–153 at eight users; 1914–2176 MB against 136 MB. Section 1.
- **A defect found on the way.** The night's first four served runs read 2.40–2.51×: their measured requests
  ended in `exit`, and in our engine an `exit` invalidates the compiled code of every function on the call stack;
  after fifteen recompilations Graal gives those functions up and WordPress's hook dispatch is interpreted for the
  rest of the server's life. Confirmed by a run without the `exit` (no such failure, against eight in each of two
  traced runs with it); not fixed tonight. A real site ends requests in `exit` (redirects, the REST API,
  admin-ajax). [103](../issues/103-exit-deopt-cycle.md). With the best-case collector and heap the server read
  2.30× (measured with `exit`), 54.5 pages a second, 3117 MB.
- **The output is PHP's:** 10,000 of 10,000 renders identical by length and CRC-32.
- **Why not.** There is no hot spot to fix: 73% of a hot render is the compiled code of WordPress's own 1,135
  functions, and that part alone (14.6 ms) is longer than PHP's whole render (9.4 ms). The night's three fixes
  (095, 097, 098) were the largest outliers a differential profile showed; they took a render at equal warm-up
  from 33.9 to 26.7 ms and the server from 36.9 to 41 pages a second, and left the factor of two. Section 6.
- **Not the collector, not the heap, not the thresholds, not the warm-up:** each was varied; only ParallelGC with
  a 1.2 GB nursery moved the level (9%). Sections 3 and 5.
- **Not the compiler as such:** on a loop of integer arithmetic the engine's compiled code is 1.5× faster than
  PHP's JIT, and one of the eleven kernels of WordPress functions is faster than PHP (three more within 1.2×). The
  factor of two is the cost of PHP's everyday operations as we compile them (array reads and writes, string
  building, calls, property access) summed over a thousand functions. Section 8 lists what beating PHP would take.
- **WooCommerce was not measured again:** there is no win on plain WordPress to carry over; the last measurement
  stands (about a quarter of php-fpm's orders per second, [woocommerce.md](woocommerce.md)).
- **How sure:** the factor of two shows in every run of the night (thirteen long loops on the front page, four
  on other pages, nine server runs), far outside the noise of a machine that itself runs at two speeds
  (section 4). Differences of a few percent between JVM variants are not established. The other four pages give
  the same answer in the best case: 1.83–1.95× by the first decile on each of the five (section 2).

### 1. The server: nginx → FastCGI, a context per request

Before tonight's fixes (the build of 2026-11-09 evening), warm-up 300 s for the JVM and 60 s for php-fpm, one round,
medians:

| page | engine | plain ms | CPU-ms/plain | boot ms | first render ms | hot render ms | hot vs fpm | plain vs fpm |
|---|---|---|---|---|---|---|---|---|
| `/` | fpm | 31.1 | 34.3 | 9.8 | 21.0 | 10.92 | 1.00 | 1.00 |
| `/` | jvm-fcgi | 112.9 | 347.3 | 30.9 | 57.0 | 33.48 | 3.07 | 3.63 |
| `/?p=1` | fpm | 43.0 | 35.4 | 9.8 | 25.1 | 13.28 | 1.00 | 1.00 |
| `/?p=1` | jvm-fcgi | 86.3 | 148.2 | 27.6 | 59.0 | 38.17 | 2.88 | 2.01 |
| `/?page_id=2` | fpm | 37.2 | 31.6 | 9.8 | 19.7 | 9.45 | 1.00 | 1.00 |
| `/?page_id=2` | jvm-fcgi | 88.9 | 76.8 | 26.7 | 46.4 | 29.27 | 3.10 | 2.39 |
| `/?cat=1` | fpm | 30.9 | 31.8 | 9.4 | 20.6 | 10.54 | 1.00 | 1.00 |
| `/?cat=1` | jvm-fcgi | 95.7 | 115.5 | 28.1 | 53.4 | 30.80 | 2.92 | 3.09 |
| `/?s=hello` | fpm | 30.5 | 30.1 | 12.8 | 32.5 | 15.83 | 1.00 | 1.00 |
| `/?s=hello` | jvm-fcgi | 84.9 | 79.0 | 28.1 | 47.6 | 30.09 | 1.90 | 2.79 |

Geometric means over the pages, the JVM's time over php-fpm's: plain 2.72, boot 2.75, first render 2.24,
hot render 2.73. memory.peak 2003 MB against 136 MB.

By the rule above: the hot render is slower than PHP's on every page, by the same factor as the whole request.
The serving gap is not per-request overhead; a deployment that kept a booted WordPress between requests would be
as far behind as today's.

After tonight's fixes, 720 s of warm-up for the JVM, one round, the three variants one after another
(`jvm-fcgi.pre` is the build before the fixes, `jvm-fcgi` the build with 095, 097 and 098):

| page | engine | plain ms | CPU-ms/plain | boot ms | first render ms | hot render ms | hot early → late | hot vs fpm | plain vs fpm |
|---|---|---|---|---|---|---|---|---|---|
| `/` | fpm | 29.8 | 25.7 | 10.2 | 21.3 | 11.26 | 10.73 → 10.85 | 1.00 | 1.00 |
| `/` | jvm-fcgi.pre | 75.4 | 100.7 | 29.7 | 58.0 | 32.23 | 47.12 → 28.56 | 2.86 | 2.53 |
| `/` | jvm-fcgi | 74.1 | 89.4 | 29.4 | 51.5 | 27.58 | 45.08 → 25.55 | 2.45 | 2.48 |
| `/?p=1` | fpm | 33.1 | 33.8 | 9.6 | 24.8 | 13.13 | 12.83 → 13.61 | 1.00 | 1.00 |
| `/?p=1` | jvm-fcgi.pre | 82.0 | 80.0 | 24.7 | 53.0 | 31.58 | 44.01 → 30.73 | 2.41 | 2.48 |
| `/?p=1` | jvm-fcgi | 75.0 | 77.5 | 24.9 | 48.7 | 30.26 | 40.92 → 29.32 | 2.30 | 2.27 |
| `/?page_id=2` | fpm | 29.4 | 31.7 | 9.1 | 19.7 | 9.45 | 9.43 → 9.61 | 1.00 | 1.00 |
| `/?page_id=2` | jvm-fcgi.pre | 70.3 | 67.8 | 24.9 | 38.9 | 22.55 | 26.21 → 23.11 | 2.39 | 2.39 |
| `/?page_id=2` | jvm-fcgi | 68.0 | 73.1 | 24.5 | 39.5 | 22.04 | 21.45 → 21.67 | 2.33 | 2.31 |
| `/?cat=1` | fpm | 41.2 | 31.2 | 9.7 | 20.7 | 10.46 | 11.05 → 10.44 | 1.00 | 1.00 |
| `/?cat=1` | jvm-fcgi.pre | 70.0 | 72.0 | 24.2 | 43.2 | 25.49 | 28.04 → 25.47 | 2.44 | 1.70 |
| `/?cat=1` | jvm-fcgi | 76.3 | 67.8 | 24.1 | 41.9 | 25.53 | 25.01 → 25.82 | 2.44 | 1.85 |
| `/?s=hello` | fpm | 28.5 | 28.9 | 9.5 | 18.6 | 9.54 | 9.72 → 9.40 | 1.00 | 1.00 |
| `/?s=hello` | jvm-fcgi.pre | 67.0 | 73.7 | 26.5 | 41.2 | 23.78 | 22.53 → 22.89 | 2.49 | 2.35 |
| `/?s=hello` | jvm-fcgi | 68.5 | 61.1 | 25.1 | 40.8 | 23.49 | 22.85 → 23.83 | 2.46 | 2.40 |

| geometric mean over the pages, over fpm | plain | boot | first render | hot render | memory.peak MB | warm-up: pages/s | CPU-ms/page |
|---|---|---|---|---|---|---|---|
| fpm | 1.00 | 1.00 | 1.00 | 1.00 | 136 | 149.1 | 41 |
| jvm-fcgi.pre | 2.27 | 2.69 | 2.21 | 2.51 | 2033 | 36.9 | 206 |
| jvm-fcgi | 2.25 | 2.65 | 2.11 | 2.40 | 2176 | 40.8 | 186 |

The fixed build again two hours later, and beside it the same build with the best collector and heap of section 2
(`jvm-fcgi.tuned`: `-XX:+UseParallelGC -Xmx2g -Xms2g -Xmn1200m` after the servers' options), 720 s of warm-up, one
round:

| page | engine | plain ms | CPU-ms/plain | boot ms | first render ms | hot render ms | hot early → late | hot vs fpm | plain vs fpm |
|---|---|---|---|---|---|---|---|---|---|
| `/` | fpm | 29.8 | 26.5 | 11.0 | 26.5 | 10.99 | 13.15 → 12.66 | 1.00 | 1.00 |
| `/` | jvm-fcgi | 69.6 | 107.8 | 29.1 | 55.6 | 33.84 | 48.93 → 26.76 | 3.08 | 2.34 |
| `/` | jvm-fcgi.tuned | 65.6 | 64.6 | 27.4 | 53.4 | 29.86 | 44.66 → 24.52 | 2.72 | 2.20 |
| `/?p=1` | fpm | 32.8 | 32.1 | 9.7 | 25.4 | 13.10 | 12.93 → 13.21 | 1.00 | 1.00 |
| `/?p=1` | jvm-fcgi | 75.8 | 79.1 | 24.6 | 48.3 | 30.45 | 47.45 → 30.49 | 2.33 | 2.31 |
| `/?p=1` | jvm-fcgi.tuned | 71.5 | 73.7 | 23.6 | 46.1 | 28.67 | 45.14 → 28.05 | 2.19 | 2.18 |
| `/?page_id=2` | fpm | 30.0 | 28.1 | 9.8 | 19.6 | 9.47 | 9.83 → 10.55 | 1.00 | 1.00 |
| `/?page_id=2` | jvm-fcgi | 70.2 | 62.5 | 25.0 | 41.6 | 22.32 | 22.90 → 23.16 | 2.36 | 2.34 |
| `/?page_id=2` | jvm-fcgi.tuned | 73.3 | 64.5 | 23.7 | 36.7 | 20.05 | 20.90 → 19.55 | 2.12 | 2.45 |
| `/?cat=1` | fpm | 31.9 | 29.4 | 9.5 | 19.2 | 10.34 | 10.30 → 10.21 | 1.00 | 1.00 |
| `/?cat=1` | jvm-fcgi | 76.4 | 63.5 | 24.0 | 40.5 | 24.87 | 28.31 → 24.76 | 2.41 | 2.39 |
| `/?cat=1` | jvm-fcgi.tuned | 71.6 | 69.6 | 24.7 | 41.4 | 23.02 | 22.64 → 22.96 | 2.23 | 2.24 |
| `/?s=hello` | fpm | 36.5 | 30.0 | 9.6 | 19.1 | 9.23 | 9.31 → 9.21 | 1.00 | 1.00 |
| `/?s=hello` | jvm-fcgi | 82.0 | 75.0 | 24.1 | 38.2 | 22.84 | 24.58 → 22.16 | 2.47 | 2.24 |
| `/?s=hello` | jvm-fcgi.tuned | 64.8 | 66.2 | 24.7 | 37.2 | 21.01 | 22.37 → 20.54 | 2.28 | 1.77 |

| geometric mean over the pages, over fpm | plain | boot | first render | hot render | hot render, the four pages after `/` | memory.peak MB | warm-up: pages/s | CPU-ms/page |
|---|---|---|---|---|---|---|---|---|
| fpm | 1.00 | 1.00 | 1.00 | 1.00 | 1.00 | 136 | 152.7 | 40 |
| jvm-fcgi | 2.32 | 2.55 | 2.04 | 2.51 | 2.39 | 2006 | 41.7 | 181 |
| jvm-fcgi.tuned | 2.16 | 2.50 | 1.96 | 2.30 | 2.20 | 3117 | 54.5 | 141 |

The fixed build three more times with the compilation trace in the server's log, 720 s of warm-up, one round each:
`jvm-fcgi.trace` and `jvm-fcgi.exit2` as every run above (a K8 request ends in the plugin's `exit`),
`jvm-fcgi.noexit` with K8 requests that return to WordPress and end at the script's end (`&k8noexit=1`). php-fpm's
rows are the run of the table above:

| page | engine | plain ms | CPU-ms/plain | boot ms | first render ms | hot render ms | hot early → late | hot vs fpm | plain vs fpm |
|---|---|---|---|---|---|---|---|---|---|
| `/` | fpm | 29.8 | 26.5 | 11.0 | 26.5 | 10.99 | 13.15 → 12.66 | 1.00 | 1.00 |
| `/` | jvm-fcgi.trace | 68.9 | 80.2 | 29.8 | 54.3 | 33.27 | 49.25 → 26.67 | 3.03 | 2.32 |
| `/` | jvm-fcgi.exit2 | 77.0 | 115.5 | 25.7 | 46.1 | 29.93 | 48.86 → 25.48 | 2.72 | 2.59 |
| `/` | jvm-fcgi.noexit | 65.4 | 68.8 | 23.2 | 41.6 | 23.12 | 40.20 → 22.87 | 2.10 | 2.20 |
| `/?p=1` | fpm | 32.8 | 32.1 | 9.7 | 25.4 | 13.10 | 12.93 → 13.21 | 1.00 | 1.00 |
| `/?p=1` | jvm-fcgi.trace | 78.6 | 79.0 | 25.3 | 50.4 | 30.38 | 43.56 → 31.25 | 2.32 | 2.39 |
| `/?p=1` | jvm-fcgi.exit2 | 75.0 | 79.1 | 23.9 | 48.1 | 29.69 | 43.14 → 29.81 | 2.27 | 2.29 |
| `/?p=1` | jvm-fcgi.noexit | 92.0 | 75.4 | 22.9 | 45.1 | 27.10 | 39.03 → 26.94 | 2.07 | 2.80 |
| `/?page_id=2` | fpm | 30.0 | 28.1 | 9.8 | 19.6 | 9.47 | 9.83 → 10.55 | 1.00 | 1.00 |
| `/?page_id=2` | jvm-fcgi.trace | 66.6 | 82.4 | 25.3 | 42.2 | 22.25 | 22.55 → 22.14 | 2.35 | 2.22 |
| `/?page_id=2` | jvm-fcgi.exit2 | 67.0 | 84.6 | 24.4 | 38.9 | 21.57 | 23.61 → 21.22 | 2.28 | 2.23 |
| `/?page_id=2` | jvm-fcgi.noexit | 69.3 | 63.2 | 22.8 | 36.2 | 20.03 | 19.99 → 19.67 | 2.11 | 2.31 |
| `/?cat=1` | fpm | 31.9 | 29.4 | 9.5 | 19.2 | 10.34 | 10.30 → 10.21 | 1.00 | 1.00 |
| `/?cat=1` | jvm-fcgi.trace | 71.7 | 69.1 | 24.6 | 44.8 | 24.98 | 27.52 → 25.88 | 2.42 | 2.25 |
| `/?cat=1` | jvm-fcgi.exit2 | 75.2 | 69.5 | 25.5 | 49.4 | 24.24 | 25.25 → 24.58 | 2.34 | 2.35 |
| `/?cat=1` | jvm-fcgi.noexit | 68.7 | 66.1 | 22.9 | 38.0 | 22.32 | 24.92 → 22.11 | 2.16 | 2.15 |
| `/?s=hello` | fpm | 36.5 | 30.0 | 9.6 | 19.1 | 9.23 | 9.31 → 9.21 | 1.00 | 1.00 |
| `/?s=hello` | jvm-fcgi.trace | 72.2 | 82.0 | 24.5 | 37.7 | 22.68 | 22.87 → 22.78 | 2.46 | 1.98 |
| `/?s=hello` | jvm-fcgi.exit2 | 72.9 | 87.9 | 23.8 | 37.6 | 22.45 | 22.49 → 22.19 | 2.43 | 1.99 |
| `/?s=hello` | jvm-fcgi.noexit | 60.8 | 63.2 | 22.5 | 35.6 | 20.05 | 25.30 → 19.97 | 2.17 | 1.66 |

| geometric mean over the pages, over fpm | plain | boot | first render | hot render | hot render, the four pages after `/` | memory.peak MB | warm-up: pages/s | CPU-ms/page | compilations failed with "Deopt taken too many times" |
|---|---|---|---|---|---|---|---|---|---|
| jvm-fcgi.trace | 2.23 | 2.60 | 2.10 | 2.50 | 2.39 | 2052 | 39.2 | 193 | 8 |
| jvm-fcgi.exit2 | 2.28 | 2.49 | 2.01 | 2.40 | 2.33 | 1917 | 39.0 | 194 | 8 |
| jvm-fcgi.noexit | 2.20 | 2.31 | 1.80 | **2.12** | 2.13 | 1914 | 41.3 | 183 | 0 |

- **As served, after 12 minutes of warm-up, a hot render is 2.1× php-fpm's: 2.07–2.17× on each of the five pages
  in the run whose measured requests do not end in `exit`** (geometric mean 2.12), the boot 2.3×, the first
  render 1.8×. A plain request is 2.20–2.32× by geometric mean in the fixed build's five runs (1.7–2.8× on single
  pages). Under eight concurrent users the JVM serves 39.0–41.7 pages a second against 149.1 and 152.7, with
  4.5–4.9× the CPU per page and 14–16× the memory.
- **The four runs before it read 2.40–2.51× (2.33–2.39× on the four later pages): that was the measurement's own
  `exit` meeting a defect of the engine.** In our engine an `exit` invalidates the compiled code of every function
  on the call stack, and after fifteen recompilations Graal gives such a function up. From the first page's K8
  block on, eight targets (`do_action`, `WP_Hook::do_action`, `WP_Hook::apply_filters`, the three main scripts,
  the script's root, the plugin's closure) ran in the interpreter. The two traced runs with `exit` show the same
  eight failing at the same pace; the run without shows none, and each of the four later pages 7–12% faster than
  in any of the three runs beside the same php-fpm run (which differ from one another by at most 4%).
  [103](../issues/103-exit-deopt-cycle.md).
- **Which of the two a site sees depends on its requests.** A request that ends in `exit` under WordPress's hook
  dispatch (a canonical redirect, the REST API, admin-ajax, WooCommerce's `wc-ajax`) does the same as the K8
  request did; some fifty to a hundred of them leave the dispatch interpreted until the JVM restarts. Not measured
  on such a request. One run without `exit`; medians, as in every table of this section.
- **The first page measured starts with a compile burst** ("hot early → late": 40–49 → 23–29 ms within its 1,000
  hot renders): the K8 path is new code to a server warmed by plain requests. Its median says where in the burst
  the run was (23.1 ms without `exit`, 27.6–33.8 with), and the five-page mean moves with it. Section 3.
- **With ParallelGC and a 2 GB heap the server is 2.20× on those four pages (2.30× on all five) against 2.39×
  (2.51×) for the servers' options in the same run, both measured with `exit`; it serves 54.5 pages a second
  (+31%) at 141 CPU-ms a page (−22%), and peaks at 3117 MB (23× php-fpm's).** The same 8% as in one context
  (section 2). It is the best served throughput of the night, and php-fpm serves 2.8× its pages with a
  twenty-third of its memory. It was not run without `exit`.
- The fixes (095, 097, 098) against the build before them: throughput 36.9 → 40.8 and 41.7 pages a second, CPU
  per page 206 → 186 and 181 ms; on the four later pages the hot render is 2.43× before and 2.38× after, within
  one round's noise; on the first page 32.2 → 27.6 ms in the run that had both, and 33.8 in the fixed build's
  second run: not a finding (section 4). The fixes' effect on the render is measured in section 6, at equal
  warm-up.
- The longer warm-up took the first table's 2.73 to 2.51 before any fix: the server is still compiling after five
  minutes at eight users, and after twelve as well (section 3).
- `/?p=1` and `/?page_id=2` are 197–203 bytes shorter than PHP's (the oEmbed discovery link needs
  `SimpleXMLElement`, [049](../issues/049-missing-extensions-wp-suite.md)); the other pages' bodies match in length.

### 2. One context kept alive: the engine's best case

`tests/gate2/k8-cli.sh`: the same plugin from the command line, one process, one context, the front page rendered
N times in a row. Nothing of a request is left in the loop, the compiler has the whole run to finish, and the
context is never thrown away: no deployment can do better than this for the engine. PHP is php-cli of the php-fpm
image with OPcache and the JIT on. Render ms, first decile / median per segment of the run (why the first decile:
section 4), page `/`:

| run | 0-500 | 500-1000 | 1000-2000 | 2000-3000 | 3000-4000 | 4000-5000 | 5000-6000 | 6000-7000 | 7000-8000 | 8000-9000 | 9000-10000 | renders | last 1000: p10 | median | mean | above 1.5× p10 | best render | loop s | peak MB |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| php-long | 9.3 / 9.9 | 9.4 / 9.8 | 9.6 / 10.3 | 9.6 / 10.0 | 9.8 / 10.6 | 9.8 / 10.3 | 10.0 / 10.5 | 10.2 / 10.8 | 10.3 / 10.8 | 10.5 / 11.3 | 10.5 / 11.0 | 10000 | 10.54 | 11.03 | 11.77 | 6% | 9.16 | 123 | 89 |
| sys-long | 40.0 / 60.0 | 33.2 / 41.8 | 29.4 / 34.4 | 28.6 / 33.8 | 30.5 / 42.1 | 30.3 / 47.1 | 25.6 / 38.2 | 21.5 / 23.1 | 21.7 / 23.8 | 21.8 / 24.2 | 21.8 / 23.9 | 10000 | 21.78 | 23.87 | 28.39 | 23% | 20.63 | 390 | 1339 |
| new-long | 44.2 / 61.5 | 38.8 / 55.9 | 26.6 / 29.9 | 27.8 / 32.0 | 21.5 / 29.7 | 20.1 / 21.8 | 21.6 / 32.0 | 20.7 / 30.1 | 20.3 / 25.7 | 20.1 / 21.5 | 21.3 / 37.0 | 10000 | 21.31 | 37.01 | 35.69 | 66% | 19.40 | 358 | 1412 |
| new-young | 38.7 / 59.0 | 29.7 / 34.5 | 26.8 / 31.9 | 26.2 / 30.5 | 23.1 / 29.9 | 19.8 / 21.0 | 20.1 / 27.2 | 19.9 / 21.3 | 20.0 / 21.1 | 20.0 / 21.0 | 20.0 / 21.2 | 10000 | 20.05 | 21.24 | 25.34 | 20% | 19.13 | 316 | 2432 |
| thr1k | 46.9 / 67.9 | 37.5 / 46.4 | 29.1 / 35.9 | 26.5 / 29.9 | 25.9 / 29.6 | 20.3 / 28.0 | 19.9 / 20.8 | 19.9 / 20.8 | 20.0 / 21.0 | | | 8000 | 20.02 | 21.01 | 23.53 | 12% | 19.25 | 275 | 1464 |
| thr1k-young | 43.0 / 61.8 | 33.4 / 39.6 | 28.5 / 32.9 | 27.3 / 32.0 | 26.1 / 30.4 | 26.1 / 30.7 | 19.9 / 21.3 | 20.0 / 21.4 | 20.0 / 21.1 | | | 8000 | 20.02 | 21.12 | 23.82 | 16% | 19.20 | 275 | 2504 |
| **thr1k-par** | 39.3 / 56.7 | 32.7 / 38.7 | 27.3 / 32.7 | 24.8 / 29.7 | 23.5 / 27.2 | 24.1 / 28.7 | 18.4 / 26.4 | 18.1 / 19.1 | 18.3 / 19.5 | | | 8000 | **18.26** | **19.53** | **22.91** | 20% | 17.49 | 260 | 2404 |
| trace-long | 40.4 / 55.2 | 33.5 / 39.0 | 27.6 / 32.3 | 30.8 / 39.7 | 23.2 / 35.9 | 20.5 / 28.2 | 20.5 / 28.7 | 20.2 / 21.4 | 20.2 / 21.5 | | | 8000 | 20.21 | 21.54 | 24.83 | 17% | 19.49 | 293 | 1546 |
| trace-noflush | 41.1 / 58.3 | 32.3 / 37.1 | 28.7 / 33.8 | 30.1 / 37.7 | 25.0 / 37.5 | 21.4 / 31.6 | 22.0 / 36.2 | 20.5 / 23.4 | 20.4 / 22.0 | | | 8000 | 20.44 | 21.98 | 25.67 | 22% | 19.60 | 310 | 1251 |
| new-shared | 31.0 / 39.5 | 21.5 / 30.8 | 21.3 / 22.9 | 21.0 / 22.4 | 21.1 / 22.3 | 26.1 / 31.0 | 21.2 / 22.4 | | | | | 6000 | 21.22 | 22.36 | 25.22 | 14% | 20.45 | 178 | 1728 |
| new-ctx6 | 21.2 / 23.0 | 21.2 / 23.6 | | | | | | | | | | 1200 | 21.12 | 22.69 | 26.47 | 21% | 20.43 | 32 | 1507 |
| php-nojit | 10.6 / 11.3 | 10.7 / 11.4 | 10.8 / 11.3 | 10.9 / 11.5 | 11.0 / 11.7 | | | | | | | 4000 | 11.02 | 11.66 | 14.20 | 22% | 10.37 | 54 | 62 |
| php-nogc | 9.4 / 9.9 | 9.4 / 10.0 | 9.6 / 10.2 | 9.6 / 10.0 | | | | | | | | 3000 | 9.62 | 10.04 | 11.63 | 14% | 9.18 | 37 | 123 |

The runs: `php-long` is php-cli; `sys-long` the build with 095 only and the servers' JVM options; `new-long` the
build with 095, 097 and 098, the same options; `new-young` that build with `-Xmx2g -Xms2g -Xmn1200m`; `thr1k` the
servers' options and `-Dpolyglot.engine.LastTierCompilationThreshold=1000` (default 10,000); `thr1k-young` both;
`thr1k-par` both and `-XX:+UseParallelGC`; `trace-long` the servers' options with the compilation trace on,
`trace-noflush` the same with `-XX:-UseCodeCacheFlushing` (section 3); `new-shared` the second context on a shared
engine, after the first context's 6000 renders, `new-ctx6` the sixth after five of 1200 renders each (the servers'
kind of context, section 3); `php-nojit` php-cli with OPcache and without its JIT, `php-nogc` with the JIT and
without its cycle collector.

- PHP is at its level from the first hundred renders: 9.39 / 9.81 / 10.43 ms (first decile / median / mean) over
  renders 200–1200. Along the loop its level creeps up (first decile 9.3 → 10.5 ms over 10,000 renders), with the
  cycle collector off and without the JIT as well, so it is some state of the loop that grows in PHP; the JVM's
  level does not creep (20.0 ms from render 5000 to 10,000). The comparison uses PHP's early level: it is the one
  a fresh php-fpm request has, and the one less favourable to us (against PHP's last thousand renders the factors
  below are 11% lower).
- The engine reaches its level after 4000–6000 renders (2.5–3.5 minutes of one thread rendering, section 3) and
  holds it. With G1 that level is 20.0 ms by the first decile in four runs (new-long's undisturbed segments,
  new-young, thr1k, thr1k-young), whatever the heap and the threshold, and 20.2–20.4 in the two runs with the
  compilation trace on: **2.13× PHP's time by the first decile, 2.14–2.17× by the median, 2.26–2.43× by the
  mean.**
- **The best steady state measured is thr1k-par: 18.26 / 19.53 / 22.91 ms at 2404 MB, 1.94× PHP's time by the first
  decile, 1.99× by the median, 2.20× by the mean, 25–27× its memory.** ParallelGC takes 9% off the level itself
  (its write barrier is cheaper than G1's: the likely reason, not measured apart). A second run with the same
  options an hour later gave 18.28 / 19.40 / 22.53 ms at 2339 MB (the table of pages below), and two more with one
  option added each (section 3) 18.59 ms with six compiler threads and 18.07 ms with `WP_Query::get_posts`
  compiled: 1.92–1.98× over the four runs, the spread of one configuration on this machine.
- Of 98,200 renders of the front page on the JVM in the night's loops the fastest took 17.42 ms. 96% of PHP's
  renders at its level are faster than that (renders 200–1200: its 90th percentile is 11.6 ms), and 86% of all
  10,000 of php-long; the rest are PHP's renders while the machine is in its slow mode (section 4).
- Every render's output is PHP's, byte for byte: 10,000 of 10,000 by length and CRC-32.
- PHP without its JIT renders the page in 10.6 / 11.3 ms (first decile / median, renders 0–500) at 62 MB: the
  tracing JIT buys PHP 12% on this page. The engine's best level is 1.72× the time of PHP's interpreter.

The other pages, the same two engines (php-cli 1500 renders, then one context with thr1k-par's options 7000
renders, page after page; PHP's renders 200–1200 against the JVM's last thousand, first decile / median / mean):

| page | PHP ms | JVM ms | JVM over PHP: first decile | median | mean | best JVM render | JVM at its level from | PHP's renders above 1.5× its first decile | peak MB PHP / JVM |
|---|---|---|---|---|---|---|---|---|---|
| `/` | 9.35 / 9.85 / 12.02 | 18.28 / 19.40 / 22.53 | 1.95 | 1.97 | 1.88 | 17.52 | render 5650 (208 s) | 26% | 83 / 2339 |
| `/?p=1` | 11.34 / 12.24 / 14.49 | 21.49 / 22.52 / 25.44 | 1.89 | 1.84 | 1.76 | 20.79 | render 5100 (221 s) | 17% | 82 / 2359 |
| `/?page_id=2` | 8.23 / 8.96 / 11.69 | 15.26 / 16.04 / 18.55 | 1.85 | 1.79 | 1.59 | 14.93 | render 5750 (176 s) | 37% | 81 / 2354 |
| `/?cat=1` | 9.44 / 10.89 / 13.28 | 17.27 / 17.99 / 19.00 | 1.83 | 1.65 | 1.43 | 16.84 | render 5350 (187 s) | 33% | 81 / 2390 |
| `/?s=hello` | 8.19 / 8.64 / 10.16 | 15.87 / 16.83 / 19.45 | 1.94 | 1.95 | 1.92 | 15.41 | render 6000 (183 s) | 10% | 82 / 2388 |
| geometric mean | | | **1.89** | 1.84 | 1.70 | | | | |

- **Every page is 1.83–1.95× PHP's time by the first decile; none comes nearer.** The front page is not a bad
  case: it is the pages' upper end by 3%.
- The medians and means of this table flatter the JVM: each of PHP's runs is 16–22 s long and starts right after
  four minutes of the JVM with its compiler threads, and 10–37% of its renders are in the machine's slow mode
  (6% in php-long's quiet thousand). The first decile is the comparison (section 4).
- The output is PHP's on `/`, `/?cat=1` and `/?s=hello` (1500 of 1500 renders by length and CRC-32); `/?p=1` and
  `/?page_id=2` are 197 and 203 bytes shorter in every render (the oEmbed discovery link,
  [049](../issues/049-missing-extensions-wp-suite.md)).
- Each page in a fresh JVM needs 5100–6000 renders (2.9–3.7 minutes) to its level with these options.

### 3. Warm-up

PHP is at its level within the first hundred renders. The engine, one context, one thread rendering (`trace-long`:
the servers' options and `-Dpolyglot.engine.TraceCompilation=true`):

- **The curve.** First decile of the render time per 10 s of the loop: 229 ms, 60, 48, 39, 37, 35, 30, 31, 28 (at
  80 s), 27, 29–34 from 100 to 160 s, **20.9 at 170 s** and 20.2–21.8 after, with one relapse to 27.7 at 220 s.
  Within 5% of the final level from render 3600 (168 s); PHP renders 17,000 pages in that time.
- **The bound is the compiler's throughput.** 2632 compilations for this one page: 1662 first-tier (143 s of
  compiler time) and 970 last-tier (398 s, 0.41 s each), 47 MB of code, **541 s of compiler time**. The last tier's
  queue is saturated at three threads' worth from 90 to 180 s, and the level is reached when it drains (166–168 s
  in both traced runs). A last-tier threshold of 1000 instead of 10,000 makes it later (196–201 s): more targets
  queue, the hot ones wait behind them.
- **A second burst** at renders 5000–5300: 139 functions that run once or twice per render reach the last tier
  together (48 s of compiler time); for 10–20 s no render takes less than 24–28 ms.
- **`WP_Query::get_posts` never compiles** ("code is too large" after 9.4 s in the first tier): it runs in the
  interpreter in every render of every run above. It is not what the factor of two is made of: with HotSpot's limit
  raised (`-XX:+UnlockExperimentalVMOptions -XX:JVMCINMethodSizeLimit=4194304` on the best case's options) it
  compiles, 10.0 s in the first tier and 9.4 s in the last for 520 KB of code, and the last thousand renders take
  18.07 / 19.24 / 22.50 ms against 18.26–18.28 / 19.40–19.53 / 22.53–22.91 without: 1%, inside the spread between
  runs; the same warm-up (render 5450, 205 s), 2389 MB.
- **HotSpot's code-cache sweeper** unloads compiled code of hot functions as cold and Truffle resets their profiles
  (39 "opt reprof" events, `WP_Object_Cache::get` and `WP_Hook::do_action` among them). With
  `-XX:-UseCodeCacheFlushing` there are none, and the curve is the same: not the cause of anything above.

Later contexts on a shared engine (the server's kind: a context per request):

| | boot (to `wp`) | first render | first hundred renders, median | at the level from |
|---|---|---|---|---|
| the first context | 3.8–4.5 s | 2.2–3.0 s | 75–104 ms | render 3600–5000 (170–200 s) |
| the second, after the first's 6000 renders | 603 ms | 516 ms | 88.8 ms | render 550–600 (27–29 s) |
| the sixth, after five of 1200 renders each | 78 ms | 93 ms | 31.4 ms | render 100 (3 s) |
| php-cli, a cold process (no file compiled yet) | 0.41–0.55 s | 146–214 ms (34 ms without its JIT) | 10.3–13.2 ms | the first hundred |

- The compiled code carries over between contexts, more of it with each. A context's first render stays 4× its
  level even in the sixth context (93 ms against 21): the caches that belong to a context are filled again. A
  served request is exactly that render, which is why the server's "first render" column is 39–52 ms and not 21.

More compiler threads, and the server's own trace:

- **More compiler threads bring the level earlier, not lower** (`par-ct6`: the best case's options and
  `-Dpolyglot.engine.CompilerThreads=6`): within 5% of the level from render 2550 (123 s) against render 5450–5650
  (201–208 s) in three runs without; the last thousand renders 18.59 ms by first decile against 18.07–18.28. Before
  the level a render is 21% slower than with three threads (seven busy cores: section 4), 7000 renders take the
  same 238–240 s either way, and the peak is 2728 MB against 2339–2404. The compile queue is the bound, and the
  cores to drain it faster are the cores the requests need.
- **The server compiles through all of its twelve minutes** (`jvm-fcgi.trace`, the fixed build with the trace on,
  section 1). 8322 compilations against one context's 2632: every request boots WordPress, so the boot's functions
  are hot too. The first tier is done after four minutes (47–56 s of compiler time per 30 s until then). The last
  tier's queue is saturated from minute 4 to the end of the warm-up: 65–89 s of compiler time per 30 s, 96–237
  compilations. In all ≈ 2014 s of compiler time in the 720 s: **2.8 of the server's 7.5 busy cores, about 71 of
  its 193 CPU-ms a page.**
- **So the JVM's "pages a second" at eight users (39–42; 54.5 tuned) is an average over a warm-up with a third of
  the CPU compiling.** The steady state was not reached in twelve minutes and was not measured. What was measured
  after the warm-up, at one user: a plain request costs 62–108 CPU-ms against php-fpm's 26–32, 2.6–2.7× by
  geometric mean over the pages in two runs. **An estimate, not a measurement:** by that ratio, or by the 122
  CPU-ms a page that are not the compiler's, the steady state is 55–60 pages a second, against php-fpm's 149–153.
  The 3.6–3.8× in throughput is the factor of 2.2–2.7 of a request plus the compiler's share of twelve minutes.
- **The measurement after the warm-up has a compile burst of its own, and the night's runs added a defect to it.**
  The first page's K8 block (100 requests, the K8 path being new code) takes 143 CPU-seconds in 45 s; its hot
  render falls from 40.2 to 22.9 ms between the block's first and last quarter. With K8 requests that end in
  `exit` (every run but the last) the block takes 183–185 CPU-seconds in 49–50 s: eight targets are recompiled
  fifteen times each, 53–55 s of compiler time, and then given up by Graal
  ([103](../issues/103-exit-deopt-cycle.md)).

Issue [100](../issues/100-warm-up.md).

### 4. The machine has two speeds, and what that does to every number here

The box is a laptop (Core Ultra 9 275HX: 8 P-cores, 16 E-cores). Under load its cores do not hold one frequency. A plain Java loop of integer arithmetic (no Truffle, no allocation, no WSL), pinned
to one P-core on the host while the server A/B loaded the VM, ran its fixed 2,000,000 iterations in 1.67 ms or in
3.09 ms, little in between: 67% of the chunks at 1.8–1.9× the fast time, in runs of 0.24 s that start every 0.4 s.
No chunk took more than 6 ms, so it is not preemption. Windows' counters at the same time: every core between 100%
and 128% of its base frequency, performance limit 100%: turbo in bursts, base frequency otherwise.

In the VM, with nothing else running in it, the same loop in PHP (`tests/gate2/calib.php`, php-cli with its JIT,
45 s on the benchmark CPUs under the lock) spends 24–30% of its time at half speed, in runs of 0.12 s every 0.35 s;
its mean is 1.16–1.22× its first decile:

| cpuset | first decile | median | 90th percentile | slow chunks (> 1.4× first decile) | slow runs: length, period, slowdown |
|---|---|---|---|---|---|
| 14-21 | 0.837 ms | 0.848 ms | 1.635 ms | 16.2% of chunks, 28.2% of time | 0.120 s, one every 0.362 s, ×1.97 |
| 17 | 3.369 ms | 3.393 ms | 5.710 ms | 13.1%, 24.0% | 0.116 s, one every 0.367 s, ×2.29 |
| 22-23 | 1.684 ms | 1.698 ms | 3.919 ms | 16.6%, 30.4% | 0.124 s, one every 0.344 s, ×2.33 |

In the K8 loops it reads as renders that take twice as long, in runs of ≈ 125 ms every ≈ 0.3 s, whatever the
garbage collector is doing: 6% of PHP's renders in a quiet thousand (17% over its 10,000), 12–23% of a JVM loop's
last thousand, and 66% in one run's. php-cli's mean render is 1.11–1.18× its first decile.

The same loop on our engine (`calib.sh NAME CPUS SECONDS jvm`, one context, the best flags of section 2), one run
after the other on CPU 17:

| engine, cpuset | fast mode, ns per iteration | slow chunks (> 1.4× first decile) | slow runs: length, period, slowdown | mean over first decile |
|---|---|---|---|---|
| php-cli with its JIT, 17 | 1.315 | 15.9% of chunks, 29.1% of time | 0.119 s, one every 0.368 s, ×2.34 | 1.21 |
| our engine, 17 | 0.872 | 15.8%, 32.0% | 0.124 s, one every 0.350 s, ×2.37 | 1.25 |
| our engine, 14-21 | 0.872 | 8.7%, 17.7% | 0.125 s, one every 0.367 s, ×2.38 | 1.16 |

- The two engines see the same machine: the same slow runs, the same share.
- **On this loop (integer arithmetic in a local variable) the engine's compiled code is 1.5× faster than PHP's
  JIT** (0.872 against 1.315 ns an iteration). The 2× of a WordPress render is not the compiler's level on PHP
  code as such; it is what WordPress's code does (arrays, strings, calls, properties: section 6).

**How many cores are busy decides how slow.** `calib.php` on several of the benchmark CPUs at once (one php-cli
per CPU, 30 s, nothing else in the VM), and what the timings do to 20 ms of fast-mode work, a hot render's size:

| busy CPUs | first decile of a chunk, ns per iteration | mean, ns per iteration | slow chunks (> 1.4× the fast level), share of the time | 20 ms of work: first decile / median / mean / 90th percentile, × the fast level |
|---|---|---|---|---|
| 1 (CPU 17; three runs, before, between and after the others) | 1.307–1.317 | 1.584–1.635 | 15–20% of chunks, 29–34% | 1.00 / 1.02 / 1.20–1.24 / 1.89–2.04 |
| 2 (16, 17) | 1.315–1.317 | 1.745–1.746 | 26–29%, 43–45% | 1.00 / 1.04–1.05 / 1.33 / 2.34 |
| 4 (14–17) | 1.315 on each | 1.888–1.890 | 36–37%, 54–55% | 1.01 / 1.23–1.24 / 1.44 / 2.34 |
| 5 (14–18) | 1.315–1.318 | 1.870–1.871 | 39–46%, 55–59% | 1.01–1.02 / 1.31–1.32 / 1.42 / 2.09–2.12 |
| 6 (14–19) | 1.307–1.320 | 2.067–2.070 | 46–60%, 64–72% | **1.12–1.15** / 1.44–1.45 / 1.57 / 2.34–2.35 |
| 7 (14–20) | 1.307–1.363 | 2.090–2.094 | 48–67%, 66–76% | **1.30** / 1.45–1.46 / 1.59 / 2.34 |
| 8 (14–21) | 1.315–1.323 on five, 1.55–1.56 on three | 2.196–2.203 | 54–76%, 71–83% | **1.40** / 1.52–1.53 / 1.67–1.68 / 2.34 |

- The fast mode's level does not move (1.315 ns an iteration); the time spent out of it does: a third with one
  busy core, over half with four. With eight, no stretch of 20 ms is fast any more: the **first decile** of a
  render-sized piece of work is 1.40× what it is with one or four busy cores, the same on each of the eight CPUs.
- **The first decile leaves the fast level between five and six busy cores:** 1.00–1.02× up to five, 1.12–1.15×
  with six, 1.30× with seven, 1.40× with eight. The median leaves it at once: 1.04–1.05× with two busy cores,
  1.23–1.24× with four, 1.31–1.32× with five. (Four and eight were measured an hour before the others; the
  one-core row was repeated each time.)
- **What it settles about the warm-up.** One context that is still compiling keeps four to six cores busy (the
  render thread, three compiler threads at 2.6–2.9 threads' worth, G1's concurrent threads): the machine takes
  1–15% of its first decile there, and the rise seen while the last tier's queue is busy is 30–45% (section 3).
  So at most a third of that rise is the cores' frequency. The rest is the engine, or what this calibration
  cannot see (the loop touches no memory: the cores' shared cache while the compiler works); the two were not
  separated. With six compiler threads (seven busy cores) the renders before the level are 21% slower than with
  three, which is inside the 1.30× of that row.
- **The first decile carries over between runs only at an equal number of busy cores.** The best case of section 2
  (one thread rendering, the compiler finished, a stop-the-world collector) and php-cli are both the one-core row:
  the 1.94× stands. A JVM that is still compiling (a render thread, three compiler threads or more, G1's
  concurrent threads) is further down the table than PHP is, and a server at eight users is the last row for both
  engines.
- Eight busy CPUs do 5.9 times one CPU's work, not eight (455 million iterations a second each against 611–617
  alone): both servers' pages per second at eight users carry that, in the same proportion.

What follows for the numbers:

- A median or a mean over a window is the engine's level times the share of the window the machine spent slow.
  The first decile is the engine's level in the fast mode; this page gives all three.
- Two runs of the same build differ by up to 1.7× in the median of a thousand renders (new-long's last thousand:
  a median of 37 ms with a first decile of 21). A shift of the median with the first decile in place is the
  machine. A shift of the first decile at an equal number of busy cores is the engine: section 3 has one of four
  minutes.
- How much of the time the machine is slow depends on how many cores are busy, anywhere on the box: a JVM with its
  compiler and collector threads running slows its own main thread's clock more than single-threaded PHP does.
  On a server CPU that holds its frequency this part of the JVM's mean would not be there; the first decile is the
  number that carries over.
- The kernels' "steady" value (the median of the last two rounds of `hot.php`) has the same defect: differences
  between two builds of less than 2× on one kernel are not findings.

Issue [101](../issues/101-machine-two-speeds.md).

### 5. The garbage collector and the heap

A hot render allocates 12–13.6 MB. With the servers' options (`-Xmx1g`, heap given back eagerly:
`MinHeapFreeRatio=10`, `MaxHeapFreeRatio=30`, periodic GC) G1 settles on a 502 MB heap with a young collection
every 0.3–1.1 s and stays in concurrent cycles through the warm-up (436 in 549 s).

| run (page `/`, one context) | renders | steady first decile / median / mean ms | memory.peak MB |
|---|---|---|---|
| nursery capped at 64 MB (`-Xmn64m`) | 2500 | 49.2 / 59.4 / 58.6 | 1204 |
| the servers' options | 10000 | 21.3–21.8 / 23.9–37.0 / 28.4–35.7 | 1339–1412 |
| `-Xmx2g -Xms2g -Xmn1200m` | 10000 | 20.05 / 21.24 / 25.34 | 2432 |
| the servers' options, last-tier threshold 1000 | 8000 | 20.02 / 21.01 / 23.53 | 1464 |
| `-Xmx2g -Xms2g -Xmn1200m`, threshold 1000 | 8000 | 20.02 / 21.12 / 23.82 | 2504 |
| the same with `-XX:+UseParallelGC` | 8000 | 18.26 / 19.53 / 22.91 | 2404 |
| ParallelGC, the servers' heap | 2500 | 29.3 / 40.0 / 40.3 (not warm yet; the others at 2500: 28.4–29.4 by the first decile) | 1229 |

- A small nursery costs 2×: the render's garbage is promoted and collected again. This is the regime a memory
  budget near PHP's would put the JVM in.
- With G1 the level is 20.0 ms whatever the heap: a 1.2 GB nursery turns 1777–2085 pauses of a run into 106
  (847 ms in all, one young collection every 2.2 s, no concurrent cycle) and moves the mean, not the first decile,
  for 1.0 GB more memory.
- ParallelGC with the same heap: 101 pauses, 462 ms in all, and a level 9% lower (18.26 against 20.02): the one
  option of the night that moved the first decile. It is the collector's cost inside the compiled code, not its
  pauses.
- With the servers' options G1 marks back to back for most of a run (30–47 concurrent cycles per 10 s in the log:
  the heap is kept nearly full on purpose, so every young collection ends above the marking threshold). When the
  cycles stop, the median render falls from 25–37 ms to 22 with the first decile where it was. That is the
  distance between the servers' options' medians and the big heap's; issue [100](../issues/100-warm-up.md).
- So the collector is not the gap either: with as much memory as it wants and the cheapest collector the render is
  1.94× PHP's.

### 6. Where a hot render's time goes

- **No hot spot.** The in-situ profile of the hot renders (main thread, compiler drained): compiled operations of
  the PHP functions themselves ≈ 73% of the samples, built-in functions' bodies ≈ 22.5%, call dispatch ≈ 3.9%; no
  PHP function and no built-in above 7%. A hot render of the front page is 53,238 calls of 1,135 PHP functions
  and allocates 12–13.6 MB.
- **A bound from that split.** 73% of 20 ms is 14.6 ms in the compiled PHP functions alone, against 9.4 ms for
  PHP's whole render: with every built-in function at zero cost the render would still be 1.5× PHP's.
- **The time is in last-tier code, not in the interpreter.** Of that profile's 27,822 stacks (the server's hot
  renders) the function on top runs in last-tier compiled code in 95.4%, in first-tier code in 1.3%, in the
  interpreter in 3.3%: `WP_Hook::apply_filters` 2.7% (60% of its own samples; in one context it is in the last
  tier within the first minute and stays there; the profiled requests end in `exit`, which costs the functions on
  the stack their compiled code: issue [103](../issues/103-exit-deopt-cycle.md)) and `WP_Query::get_posts` 0.4%
  (too large to compile, section 3). One context deoptimizes 179 times in 8000 renders, 144 of them one loop in
  its first minute: a loop that never exits has no deoptimization cycle to remove.
- **What the differential profile found, and tonight's fixes.** Against Excimer's profile of the same renders on
  PHP, three places stood far out and are fixed: a class-name callable walked the stack on every resolution
  ([095](../issues/095-callable-resolution-walks-stack.md): ≈ 2.65 ms of a 33 ms render; the style engine's function
  118× PHP's time); `ord`, `chr`, `array_key_exists` and a string key's integer test were calls out of the compiled
  code ([097](../issues/097-builtins-behind-a-boundary.md)); a megamorphic method call, `global` and a
  function-name callable did a name's work per call ([098](../issues/098-per-call-name-work-in-lookups.md): 9.6% of
  a render's allocation). Open: [099](../issues/099-object-vars-and-dynamic-property-writes.md) (`new WP_Post`,
  8–13× PHP, 0.13 ms of a render). At equal warm-up (renders 1000–2000 of one context, first decile): 33.9 ms
  before, 28.4 after 095, 26.6–26.8 after 097 and 098.
- **The fixes under the check suite** (`bench/check/check.sh l0` on the branch against `main`, `8113e1c`): the
  unit tests and the destructor fixtures are as on `main` (`CompilationTest`'s two failures are `main`'s:
  [094](../issues/094-l0-red-baseline.md)). The smoke suite has `main`'s eight failures and one more,
  `callable-deprecated`: the test added with 095 for a gap `main` has too
  ([096](../issues/096-callable-deprecations-missing.md), open: PHP 8.2's deprecation of `self`, `parent` and
  `static` in a callable is never raised).
- **Kernels of the functions the differential profile ranked worst** (`tests/gate2/kernels/hot.php`, 11 kernels on
  WordPress's own code, php-cli against one context, CPUs 14-21): geometric mean 2.51× PHP's time before the fixes,
  2.04× after (a shared engine's code: 2.69 → 2.21). From 0.68–0.90× (`args`: `wp_parse_args`, faster than PHP) and
  1.14–1.19× (`esc`, `tags`, `hasf`) to 8.5× (`post`, issue 099); `mega` (a megamorphic hook dispatch) went from
  16× to 1.6–3.3×, `tags` from 3.7× to 1.15×. One kernel's value moves by up to 1.8× between runs (section 4); peaks
  869–942 MB against 32 MB. The two builds interleaved twice on the host JVM (P-cores): the fixed build's geometric
  mean is 0.79 and 0.80 of the earlier one's, the earlier one's second run 1.12 of its first.
- **Exceptions are not it:** 11.7 Java exceptions per render (missing files under `file_exists()`, `json_decode`
  of non-JSON), without stack traces; the file functions are 0.4% of the main thread's samples.

### 7. Memory

| | php-fpm, 8 workers | JVM server | JVM server, 2 GB heap, ParallelGC | php-cli, one loop | JVM, one context, the servers' options | JVM, one context, 2 GB heap with a 1.2 GB nursery |
|---|---|---|---|---|---|---|
| memory.peak | 136 MB | 1914–2176 MB | 3117 MB | 81–97 MB | 1251–1546 MB | 2339–2504 MB |

14–16× PHP's as served and for one context with the servers' options, 23× for the fastest server and 25–27× for
the fastest single context. The server's peak is the compiler's (libgraal's native memory during the warm-up) on
top of the heap ([woocommerce.md](woocommerce.md) has the breakdown under load). No configuration was found that trades time for
memory in the JVM's favour: the only one near a small footprint (a 64 MB nursery) doubles the render time
(section 5) and still peaks at 1204 MB.

### 8. What beating PHP would take, and what tonight does not prove

What it would take, each of them measured above:

1. **The compiled code of WordPress's own functions at half its present time, everywhere.** 1.94× in the best case
   (2.13× with the default collector), no hot spot, 1,135 functions in a render, 73% of the time in their compiled
   operations. Tonight's three fixes were the largest outliers the differential profile had; they took the
   worst functions' kernels from 2.51× to 2.04× and a render at equal warm-up from 33.9 to 26.7 ms, and the factor
   of two is what is left after them. The next known one (099) is 0.13 ms of a 20 ms render.
   What is left is the cost of the common operations (array reads and writes: the `aget` kernel at 2.5–5×, string
   building, calls, property reads) and has to be found in the compiled code's graphs, not in a profile of
   functions.
2. **The boot in a context per request: 2.3× PHP's** (22.5–23.2 ms against 9.5–11.0; 2.5–2.65× in the runs with
   `exit`), or a deployment that keeps a booted WordPress between requests, which is a different isolation than
   PHP's and would still render at 1.9–2.1×. [093](../issues/093-per-request-name-lookups.md).
3. **The warm-up** (section 3): minutes of load before the level is reached, against none for PHP.
4. **Memory:** 14–27× PHP's (section 7).
5. **`exit` without the loss of compiled code** ([103](../issues/103-exit-deopt-cycle.md)): a defect, found
   tonight and not fixed. It is worth the distance between 2.4–2.5× and 2.1× as served on a site whose requests
   exit; it does not touch the best case's 1.94×.

What tonight does not prove:

- **That it cannot be done.** Of the 11 kernels of the worst functions one is faster than PHP (0.68–0.90×) and
  three more are within 1.2×; Gate 1's kernels of hot WordPress functions were at 0.77–1.65×. The compiled code can match PHP on
  WordPress's code in places; a whole render is the sum of a thousand functions where it does not yet.
- **WooCommerce** was not measured again: on plain WordPress there is no win to carry over, and the last
  measurement stands (PR #21: about a quarter of php-fpm's orders per second, [woocommerce.md](woocommerce.md)).
- **The server's steady state.** Twelve minutes at eight users do not drain its compile queue (section 3): the
  39–42 pages a second are an average over a warm-up, the 55–60 are an estimate. A warm-up of an hour, or one at
  a lower load, was not run.
- **One round per variant** in the server tables (the fixed build six times over the night, one round each: four
  with the servers' options and `exit`, `tuned`, one without `exit`), on a machine with two speeds: the factors
  of 1.9–2.7× are far outside that noise, the differences between the two JVM builds of a few percent are not.
  The served 2.12× without `exit` is one run against php-fpm's run of two hours before.
- **Another machine.** A server CPU that holds its frequency would take the two-speed share out of the means; the
  first deciles (1.94–2.13×) are what carries over. Not measured.
- **Native image** was not measured tonight ([089](../issues/089-native-image-build-options.md)).

### 9. How to reproduce

```bash
export WPN_NAME=fc WPN_NET=172.31.153 WPN_DB_PORT=3453 WPN_AUX_CPUS=6-13 WPN_DB_CPUS=22-23 WPN_THREADS=8
# the server: php-fpm, the build before the fixes (target/classes-pre), this build; 12 minutes of warm-up
WARM=720s tests/gate2/native.sh k8 1 fpm jvm-fcgi.pre jvm-fcgi
python -I tests/gate2/k8_report.py target/fc/k8 target/fc/k8-ab fpm jvm-fcgi.pre jvm-fcgi
# the same with the best one-context collector and heap for the server
JAVA_OPTS_tuned="-XX:+UseParallelGC -Xmx2g -Xms2g -Xmn1200m" WARM=720s tests/gate2/native.sh k8 1 fpm jvm-fcgi jvm-fcgi.tuned
# the server with the compilation trace in its log (target/fc/k8/jvm-fcgi.trace-r1.server.log); its compile queue
# per 30 s
JAVA_OPTS_trace="-Dpolyglot.engine.TraceCompilation=true" WARM=720s tests/gate2/native.sh k8 1 jvm-fcgi.trace
python -I tests/gate2/k8_complog.py target/fc/k8/jvm-fcgi.trace-r1.server.log 30
# K8 requests that return instead of ending in `exit` (issue 103). The server reads the plugin from the site
# volume: `volume` first, after any change to tests/gate2/k8-plugin.php (`k8` stops on a stale copy)
tests/gate2/native.sh volume
trace="-Dpolyglot.engine.TraceCompilation=true -Dpolyglot.engine.CompilationFailureAction=Print"
K8_EXTRA="&k8noexit=1" JAVA_OPTS_noexit="$trace" WARM=720s tests/gate2/native.sh k8 1 jvm-fcgi.noexit
python -I tests/gate2/k8_report.py target/fc/k8 target/fc/k8-noexit fpm jvm-fcgi.trace jvm-fcgi.noexit
# the targets Graal gave up ("Deopt taken too many times") and their events; each K8 block's course in tens of requests
python -I tests/gate2/k8_cycle.py target/fc/k8/jvm-fcgi.trace-r1.server.log
python -I tests/gate2/k8_firstblock.py target/fc/k8/jvm-fcgi.trace-r1.k8.json target/fc/k8/jvm-fcgi.noexit-r1.k8.json
# one context, a long loop (NAME.1.json and NAME.1.err under target/fc/k8cli)
tests/gate2/k8-cli.sh php php-long 10000 /
PHP_OPTS="-d opcache.jit=disable" tests/gate2/k8-cli.sh php php-nojit-long 4000 /
tests/gate2/k8-cli.sh one new-long 10000 /
JAVA_OPTS="-Xmx2g -Xms2g -Xmn1200m" tests/gate2/k8-cli.sh one new-young 10000 /
best="-Dpolyglot.engine.LastTierCompilationThreshold=1000 -Xmx2g -Xms2g -Xmn1200m -XX:+UseParallelGC"
JAVA_OPTS="$best" tests/gate2/k8-cli.sh one thr1k-par 8000 /
# with the compilation trace and the GC log in NAME.1.err
JAVA_OPTS="-Dpolyglot.engine.TraceCompilation=true -Xlog:gc:stderr:utctime,uptime" tests/gate2/k8-cli.sh one trace-long 8000 /
# six contexts on one engine, the last one's renders in the output
K8_CONTEXTS=6 tests/gate2/k8-cli.sh shared new-ctx6 1200 /
python -I tests/gate2/k8_traj.py php=target/fc/k8cli/php-long.1.json one=target/fc/k8cli/new-long.1.json
# when a run is within 5, 10, 25% of its final level; compilations per 10 s; renders beside compilations
python -I tests/gate2/k8_reach.py trace=target/fc/k8cli/trace-long.1.json
python -I tests/gate2/k8_complog.py target/fc/k8cli/trace-long.1.err 10
python -I tests/gate2/k8_align.py target/fc/k8cli/trace-long.1.json target/fc/k8cli/trace-long.1.err 10
# five pages, php-cli against one context with the best options (t-php-p1..5, t-one-p1..5)
for i in 1 2 3 4 5; do page=$(sed -n ${i}p <<< $'/\n/?p=1\n/?page_id=2\n/?cat=1\n/?s=hello')
  tests/gate2/k8-cli.sh php t-php-p$i 1500 "$page"; JAVA_OPTS="$best" tests/gate2/k8-cli.sh one t-one-p$i 7000 "$page"; done
python -I tests/gate2/k8_pages.py target/fc/k8cli t-php-p t-one-p
# the machine's two speeds, beside any of the above
tests/gate2/calib.sh e1 17 45 && tests/gate2/calib.sh e1j 17 45 jvm
python -I tests/gate2/calib.py target/fc/calib/e1.json target/fc/calib/e1j.json
# the same on several CPUs at once (one calib.sh per CPU, outputs NAME-CPU.json), a row per group
python -I tests/gate2/calib_par.py par1 par2 par4 par5 par6 par7 par8
# the kernels
tests/gate2/kernels/hotbench.sh new 300
python -I tests/gate2/kernels/hottable.py target/fc/hot new.php new.one new.shared
```

The raw outputs of tonight's runs are under `target/fc/` of the `wordpress-fcgi` worktree (not committed): `k8/`,
`k8-pre095/`, `k8-ab095/`, `k8cli/`, `calib/`, `hot/`, `jfrcli/`.
