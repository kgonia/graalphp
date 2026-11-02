# WooCommerce: does it work, and can one JVM serve concurrent orders better than php-fpm?

## Question
The owner's question: can PHP on the JVM (`HttpMain`: one process, one shared engine, a new context per request on a
thread pool) take many concurrent WooCommerce orders better than php-fpm? First WooCommerce has to work on Truffle
PHP exactly as on PHP 8.5 (part A); then a load test answers with numbers (part B).

## Setup
`tests/gate2/woo.sh` (usage in its header):
- **The store.** Gate 2's site (WordPress 7.2-beta1) with WooCommerce 11.2.1 in `wp-content/plugins`, its own
  MariaDB 11.4 (`wc-mariadb`, CPUs 22-23), network `wc-net`. `woo.sh setup` runs wp-cli on PHP 8.5: WooCommerce
  activated, the wizard skipped, the sample products imported (`OnboardingTasks::import_sample_products_from_csv`,
  as the shop manager, their image URLs left out: no network), stock managed with 100,000 of each simple product
  and variation, cash on delivery and bank transfer, a flat rate of $5, pretty permalinks, HPOS, a classic
  (shortcode) checkout page beside the block one; the Action Scheduler's and WP-Cron's backlog run; the database
  dumped as `target/wc/seed.sql`. Every run starts from it (`reset-db`).
- **Servers** (`woo.sh start VARIANT CPUSET PORT`): `php` (php -S 8.5.12, OPcache and the tracing JIT, as Gate 2's
  reference), `fpm` (php-fpm 8.5.12 behind nginx, `tests/gate2/woo-fpm/`: OPcache, tracing JIT,
  `validate_timestamps=0`, `pm=static`), `jvm` (`HttpMain` on GraalVM 25.4, the tree interpreter, our parser, Gate 2's
  `SERVER_OPTS`: `-Xmx1g`, heap trimming, `ReservedCodeCacheSize=512m`), `jvm-phases`, `native`. Port 8080 inside
  the container, so WordPress's loopback requests (to `localhost:80`) find no server on any engine: no WP-Cron or
  Action Scheduler runs during a load run. Uploads (WooCommerce's logs) on a tmpfs.
- **The walk** (`tests/gate2/woo_walk.py`, `woo.sh walk VARIANT PORT`): as a customer the home page, the shop, a
  category, a simple and a variable product, adding to the cart by the product form and by the shop's AJAX button,
  the cart, the block checkout page, an order through the classic checkout (`wc-ajax=checkout`) and its
  order-received page, an order through the Store API (`cart/add-item`, `checkout`) and its page, my account; as the
  shop manager the login, my account and its orders, wp-admin's orders list and an order's edit screen, the products
  list, WooCommerce's home and settings, the REST API (`wc/v3` orders and products, `wc-analytics` revenue and
  orders, `wc-admin` options, the Store API's products); then WP-Cron twice, the second 10 s after the first, so that
  the Action Scheduler imports the orders into the analytics tables. Each response is saved masked (nonces, keys,
  dates, uniqid()s, how long ago); `woo.sh compare A B` compares the responses, the PHP errors in the servers' logs,
  and the rows the walk wrote (`dbrows`): the HPOS order tables, items and their meta, `wc_order_stats`,
  `wc_order_product_lookup`, `wc_customer_lookup`, `wc_product_meta_lookup`, the stock and sales post meta, order
  notes, WooCommerce's options and transients.

## Part A: WooCommerce on Truffle PHP

### What was broken, and the fixes
Each is a port of php-src's behaviour with a smoke test (`tests/smoke/`, or `tests/http/` for what only a web
request shows) that failed before the fix, one commit per cause:

| Where it broke | Cause | Fix |
|---|---|---|
| WooCommerce's autoloader, `class_exists()` checks | `class_exists()` true for traits; no `trait_exists()` | `d13c616` |
| Action Scheduler, Jetpack, WC's legacy class names | no `class_alias()` | `c81c3b9`: aliases in `instanceof`, type tests, the class lists |
| Container (`League\Container`), REST schema | no Reflection of user code | `f5b5cb2`: classes, methods, functions, parameters, types, in the prelude |
| `WC()` loaded nothing, then recursed until the stack overflowed | `is_subclass_of()`, `is_a()`, `method_exists()`, `property_exists()`, `get_parent_class()` didn't autoload | `0dc4014` |
| hooks registered as `[ClassName, 'method']` | a callable didn't autoload its class; the dispatch cache was shared across contexts | `577adcc` (`@NonIdempotent` lookups) |
| many | `class_implements()`, `class_parents()`, `class_uses()`, `WeakReference`, `localeconv()`, `func_get_arg()`, `DatePeriod`, ext/date's exceptions | `43c4ec3`, `df7c67f`, `913c29b`, `e863939`, `82c9dd5` |
| input handling everywhere | no ext/filter | merged branch woo-filter: `filter_var()`, the validating and sanitizing filters, `filter_input()`, `filter.default` |
| templates | `extract()` in a function: the include didn't see the variables | `05cbd87` |
| ArgumentCountError text | `zend_missing_arg_error`'s caller location | `53df67e` |
| Closures in `foreach` / `json_encode` | object without properties | `24e132b` |
| wp-admin's submenu links | `HttpMain` ran every script in the docroot; a web SAPI runs it in its own directory | `6562be8` |
| placing an order (order e-mails) | no `mail()` | merged branch woo-mail: `mail()` as ext/standard/mail.c, sendmail through glibc's popen by FFM ([D15](decisions.md)) |
| concurrent checkouts: fatal error after the order was created | no `usleep()` (the batch-processing lock waits with it) | `1d80241`: `sleep`, `usleep`, `time_nanosleep`, `time_sleep_until` |
| transient versions in the database ended in `000`; equal `uniqid()`s | millisecond clock | `f8b3aa0`: microseconds, `uniqid()` waits for the clock |
| WP-Cron (marketplace promotions): fatal error | no `gethostbyname()` | `330633c`: through glibc's resolver on Linux |
| found checking wp-cron.php's `headers_sent()` (the warnings there were in the end php -S's `log_errors` off) | an empty echo sent the headers; no "headers already sent" warnings, no `X-Powered-By` | `c68273b` |
| a cached `WP_Error` in the database | `serialize()` didn't mangle protected names (nor `__serialize`, `__sleep`, `Serializable`, `R:`/`r:`, ...) | merged branch woo-serialize: serialize/unserialize ported from var.c and var_unserializer.re |

Earlier, merged from main: `match`, writes to `$GLOBALS`, include frames in traces, `crypt()` (PR #18).

### The walk now
`woo.sh walk` on php -S and on the JVM from the same seed, `woo.sh compare php jvm` (2026-11-02, `18d1f61`):
- **Every page and action answers alike:** 37 responses, every status and redirect the same; 26 bodies
  byte-identical after masking. The other 11 differ in one line each, the oEmbed XML `<link>` that WordPress prints
  only when `SimpleXMLElement` exists (no ext/simplexml: the same 2 diffs as Gate 2's pages).
- **Both orders, the classic checkout's and the Store API's,** are placed alike: `wp_wc_orders`, their addresses,
  operational data, items and item meta, the stock and `total_sales` of each product, the product lookup table,
  the order notes: identical rows. The PHP errors in the servers' logs (the update checks' warnings, sendmail's
  absence) are identical too.
- **What still differs (open):**
  - The analytics import. WP-Cron's Action Scheduler run imports order 37 into `wc_order_stats`,
    `wc_order_product_lookup` and `wc_customer_lookup` on both engines, but on the JVM it stops there; PHP imports
    order 38 in the same run: the JVM's batch claimed both imports and ran one, with no error. Most likely the
    batch limit: the Action Scheduler ends a batch when `memory_get_usage(true)` passes 90% of `memory_limit` (256M
    in cron), and Truffle PHP's `memory_get_usage()` is the whole JVM's used heap, shared by every request (a warm
    server's is several hundred MB). The next run would import the rest, so the rows converge, but the run differs. PHP's number is
    the request's own allocator; a JVM has no per-request heap figure that cheap. That needs a decision (an
    estimate per context, or Oracle GraalVM's sandbox heap accounting), not a quick fix.
  - Following from it: `woocommerce_admin_scheduler_last_processed_order_id`, and two order meta rows
    (`_debug_log_source_pending_deletion`) that the next batch would delete.
- **Gaps that don't show in the walk, found on the way:** ext/dom (WooCommerce's e-mails go out as plain text,
  not HTML with inlined CSS), ext/simplexml, `memory_limit` not enforced, `WeakReference::get()` clears only when the
  GC runs, no typed properties or enums at run time (the serialize port's list), nullsafe `?->`, named arguments,
  variable variables, `eval()`, `setlocale()`, string offset writes, `STDIN`.

### The checks
On `18d1f61`/`054c0bf`, 2026-11-02:
- `mvn test`: passes (37 tests).
- `tests/smoke/run.sh`: all 111 equal PHP 8.5's, 27 of them new on this branch; `tests/http/run.sh` (php -S
  against `HttpMain`, new): both equal.
- Gate 1: the kernels' checksums equal PHP's on every iteration (7 kernels, 5 iterations, CPUs 6-13).
- Gate 2's pages (`native.sh pages`, the Gate 2 seed, a stack of its own `wc-g2-*`): 19 of 21 identical, the other
  two the known oEmbed XML link.
- php-src `.phpt` (`tests/phpt/run.sh`, CPUs 6-13) against main `f7e634a` (its classes, the same oracle): 3299
  passing (44.9%) against 3174 (43.2%); 128 newly pass, 3 newly fail:
  - `class_alias_009.php`, `inter_007.php`: PHP dies at compile time ("cannot implement previously implemented
    interface", "Cannot make non static method c::B() static"). Main died too, but because `class_alias()` was
    undefined, which the comparison counts as a pass. Now they reach PHP's inheritance checks, and Truffle PHP has
    none of `zend_inheritance.c`'s (final, visibility, static, signature compatibility, repeated interfaces): an
    open gap of its own, not part of this branch.
  - `non-rep-float-as-int-extra1.php` prints `rand(40, 42)`'s exponent: random, it fails on any build now and then.

## Part B: concurrent orders under load

### Method
`woo.sh bench` and `woo.sh load` (2026-11-02, the classes of `18d1f61`, the native image of `d3b26c0`: the same Java
code):
- **Client:** k6 1.3.0 (`tests/gate2/woo-load.js`) on CPUs 0-5, no think time: VUS virtual users, each iteration a
  new visitor with its own cookie jar. Every visitor opens the home page, the shop and a product; a buyer then adds
  it to the cart with the product form, opens the cart and the block checkout page, takes the Store API nonce from
  it, places the order through the Store API (cash on delivery) and opens the order-received page; a browser looks
  at a category and another product instead. Mixes: 20% buyers (`0.2`) and 100% buyers (`1`).
- **Servers** on CPUs 14-21 (8 cores, the CPU lock held), one life per configuration: started from the load seed
  (the store without its e-mails: Truffle PHP has no ext/dom, so WooCommerce would send them as plain text where
  PHP inlines CSS with DOMDocument; without them both do the same work), warmed up (300 s for the JVM and the native
  image, 60 s for php-fpm, half the visitors buying, as many users as threads), then 60 s per mix and level 1, 4, 8,
  16, 32, the database reset from the seed before each. Configurations: `jvm8`, `jvm16` (`HttpMain --threads`,
  Gate 2's `SERVER_OPTS`, `-Xmx1g`), `native16` (`wp-http`, `-Xmx1g`), `fpm8`, `fpm16` (php-fpm 8.5.12
  `pm.max_children`, OPcache and the tracing JIT, `validate_timestamps=0`, behind nginx). MariaDB 11.4 on CPUs 22-23.
- **Per run:** the server container's CPU (cgroup `usage_usec`), the RssAnon of all its processes (php-fpm: every
  worker), cgroup anon, shmem (OPcache) and `memory.current` every 0.5 s, and `memory.peak`; the database's CPU;
  InnoDB's row lock counters before and after, `SHOW ENGINE INNODB STATUS` after.
- **Correctness under load** (`woo_check.py`): every order k6 placed is in `wp_wc_orders`, processing, with one line
  item of the product and quantity k6 logged; no other order; no order id or key twice; each product's `_stock`,
  `wc_product_meta_lookup.stock_quantity` and `total_sales` equal the seed's less what the orders took; no PHP
  warning or error on any page.
- **Rounds:** 3, interleaved, round 2 in reverse order. Round 3 ran ~25% slower on every engine (the box was
  busier); a cell is the median with the range over the rounds. Round 1's `fpm8` was run again after the others: its
  first run had nginx keep FastCGI connections alive (fixed in `fc01b60`), which binds a php-fpm worker to an idle
  connection, so above 8 users requests waited 60 s. `fpm16` ran without it in every round.
- Every run's line: `bench/woocommerce/runs.txt`; every cell: `bench/woocommerce/load-table.md`.

### Results: every visitor buys
| engine | users | orders/s (range) | pages/s | p50 ms | p99 ms | CPU-s per page | RssAnon MB |
|---|---|---|---|---|---|---|---|
| php-fpm 8 | 1 | 1.05 (1.01-1.06) | 8.4 | 99 | 293 | 0.082 | 175 |
| php-fpm 8 | 8 | 5.52 (5.08-6.72) | 44.1 | 140 | 479 | 0.116 | 172 |
| php-fpm 8 | 32 | 5.52 (4.96-7.15) | 44.2 | 638 | 1719 | 0.120 | 183 |
| php-fpm 16 | 1 | 1.08 (0.90-1.15) | 8.6 | 96 | 267 | 0.080 | 334 |
| php-fpm 16 | 4 | 3.64 (3.09-3.82) | 29.1 | 107 | 370 | 0.093 | 334 |
| php-fpm 16 | 8 | 6.03 (4.68-6.20) | 48.3 | 125 | 457 | 0.107 | 327 |
| php-fpm 16 | 16 | 7.38 (5.49-7.70) | 59.1 | 212 | 778 | 0.117 | 328 |
| php-fpm 16 | 32 | 7.56 (5.35-8.07) | 60.5 | 469 | 1213 | 0.113 | 335 |
| JVM 8 | 1 | 0.46 (0.41-0.49) | 3.6 | 252 | 492 | 1.188 | 1766 |
| JVM 8 | 8 | 1.72 (1.28-1.91) | 13.8 | 521 | 1085 | 0.548 | 1945 |
| JVM 8 | 32 | 1.76 (1.35-1.91) | 14.1 | 2197 | 3514 | 0.538 | 2306 |
| JVM 16 | 1 | 0.45 (0.33-0.51) | 3.6 | 245 | 718 | 1.220 | 1887 |
| JVM 16 | 4 | 1.39 (1.11-1.49) | 11.1 | 308 | 692 | 0.605 | 1782 |
| JVM 16 | 8 | 1.76 (1.37-1.93) | 14.1 | 511 | 1202 | 0.540 | 1979 |
| JVM 16 | 16 | 2.11 (1.72-2.21) | 16.9 | 859 | 1959 | 0.457 | 2242 |
| JVM 16 | 32 | 2.12 (1.69-2.30) | 16.9 | 1760 | 3092 | 0.456 | 2368 |
| native 16 | 1 | 0.30 (0.30-0.35) | 2.4 | 377 | 757 | 1.561 | 5051 |
| native 16 | 4 | 0.78 (0.72-0.89) | 6.2 | 592 | 1282 | 0.837 | 10319 |
| native 16 | 8 | 0.77 (0.72-0.96) | 6.1 | 1243 | 2504 | 0.777 | 10637 |
| native 16 | 16 | 0.63 (0.58-0.71) | 5.1 | 3096 | 5910 | 0.767 | 11039 |
| native 16 | 32 | 0.67 (0.61-0.71) | 5.4 | 5848 | 9027 | 0.623 | 5634 |

RssAnon is the most seen in the run (php-fpm: summed over the workers, OPcache's 151 MB of shmem apart).
`memory.peak` over each server's life, rounds 1-3: php-fpm 8 287/289/280 MB, php-fpm 16 412/408/394 MB, JVM 8
10.6/8.9/9.1 GB, JVM 16 9.4/8.9/9.2 GB, native 16 10.8/11.7/11.1 GB. With 20% buyers the picture is the same in
pages: php-fpm 16 peaks at 62.9 pages/s (2.2 orders/s), the JVM 16 at 17.8 (0.69), the native image at 6.2 (0.25).
No run after the nginx fix had a failed request or a PHP warning.

### Answer
**No. One JVM serving WooCommerce takes about a quarter of php-fpm's orders on the same 8 cores:** 2.1 orders/s at
best against 7.6 (php-fpm, 16 workers), at 2-4 times the latency at every level, and it holds 2-2.4 GB of RssAnon,
with bursts to 9-10 GB, where php-fpm holds 0.33 GB and 0.15 GB of OPcache. The native image is slower still (0.8
orders/s) and as large. Sharing one engine and one process doesn't pay for itself, because each request costs far
more CPU:
- **CPU per page:** 0.46 CPU-s on the JVM at saturation against 0.11 for php-fpm (4×), 0.6-0.8 natively. Both
  saturate the 8 cores: the JVM from 8 users (7.8-8.3 cores busy), php-fpm from 16.
- **Where the JVM's CPU goes** (30 minutes at 16 users, the threads' CPU from `/proc`): the requests' threads 58%,
  **Truffle's compiler threads 25%** (3 threads, ~2 cores busy the whole time), the GC 16%. Compilation never
  settles: 12,346 compilations in 15 minutes, of 10,482 distinct call targets, most of them split copies of small
  helpers (`WC_Data::get_prop` compiled 462 times, `absint` 165, `esc_attr` 139). Only 270 were invalidated, so it
  isn't a deopt loop. php-fpm compiles nothing after its first requests.
- **Not the database:** MariaDB used 0.4-0.5 of its 2 cores under the JVM, up to 1.3 under php-fpm 16, where it
  starts to matter: InnoDB row lock waits grow from 0 to ~450 per minute at 7.6 orders/s (30-200 ms of lock time
  per minute, no deadlocks).
- **More threads help php-fpm, not the JVM:** 16 workers instead of 8 take php-fpm from 5.5 to 7.6 orders/s; 16 JVM
  threads instead of 8 take it from 1.8 to 2.1, since its cores are already busy compiling and collecting.
- **The native image** runs its serial GC in the requests' threads and compiles in the same process: its throughput
  falls after 4-8 users and its latency grows linearly with them.

**Marginal memory per concurrent request:** php-fpm: one worker each, ~21 MB of RssAnon (172 MB for 8, 330 MB for
16), plus the shared OPcache once. The JVM: from 1 to 32 users its steady RssAnon grows by ~0.5 GB, ~15 MB per user,
an upper bound since its compiled code grows over the same minutes (below); the GC agent measured ~2.7 MB of live
heap per in-flight WordPress request. Users beyond the thread count queue and add nothing.

**Warm-up:** php-fpm is at full speed within 10-30 s. The JVM reaches its plateau in pages per 10 s after 50-270 s
(the warm-up curves in `runs.txt`), but its compiler keeps working for more than 30 minutes. The native image
answers after 1 s and plateaus after ~300 s, lower.

**Correctness under load:** in every run every order k6 placed is in the database once, processing, with the right
line item; order ids and keys are unique; stock and `total_sales` are exact on the JVM and the native image. php-fpm
16 lost one stock decrement twice (rounds 1 and 3, 8 and 16 buyers: `_stock` one too high, the order and
`total_sales` right): a race in WooCommerce on PHP itself under concurrent checkouts of one product, not seen on the
slower engines.

## The JVM's memory under load
For WordPress the GC agent measured an idle server's heap at ~95 MB, ~2.7 MB of live heap per in-flight request and
a warm peak of 1.3-1.6 GB. Under WooCommerce load the same server reaches 9-10 GB with `-Xmx1g`. Where does it go,
and is it a leak?

### Method
`woo.sh memrun` (`tests/gate2/memsnap.sh`, `memsplit.py`, `woo_memreport.py`): one server life on CPUs 14-21 under
a steady k6 load (16 users, half buying, 16 threads), with `-XX:NativeMemoryTracking=summary`, a snapshot every
60 s without a full GC (status, NMT, the code heaps, the heap's ranges, every anonymous mapping, each thread's CPU),
one with a full GC at the end. `memsplit.py` puts each mapping where its address says: the Java heap, the code
cache (machine code, and NMT's malloc for its metadata), glibc's arenas (64 MB-aligned: NMT's malloc in them, the
rest "malloc kept": freed and kept by glibc, plus PCRE2's and curl's own mallocs), the JVM's other mappings by NMT.
What no rule claims is "untracked": libgraal's heap, the compiler as a native library, which NMT doesn't see.
Results: `bench/woocommerce/mem-*.json` (every snapshot), `mem-*-final-split.txt`, `compiles-jvm16-15min.txt`.

### Where it goes: a burst in the compiler, not a leak
30 minutes, 28,650 requests (`m-jvm16-base`), MB of RssAnon:

| s | requests | RssAnon | untracked (libgraal) | compiled code | malloc kept | Truffle compile CPU-s |
|---|---|---|---|---|---|---|
| 47 | 406 | 1465 | 196 | 81 | 23 | 93 |
| 234 | 3378 | 2520 | 1077 | 203 | 83 | 443 |
| 421 | 6310 | 4197 | 2624 | 277 | 108 | 844 |
| 483 | 7232 | **7350** | **5826** | 285 | 115 | 983 |
| 609 | 9237 | 1895 | 247 | 318 | 119 | 1249 |
| 983 | 15571 | 2056 | 236 | 468 | 133 | 1948 |
| 1358 | 21676 | 2199 | 300 | 591 | 123 | 2650 |
| 1732 | 27676 | 2550 | 511 | 714 | 129 | 3356 |

- **The burst is libgraal's heap:** up to 5.8 GB of the 7.4 GB in minutes 4-9, then back to ~0.2-0.5 GB. It comes
  with the largest compilations: `WP_Query::get_posts` at 115-122k graph nodes (23 s in tier 2),
  `WP_Theme_JSON::sanitize` at 62-68k, a property default's `ThunkRootNode` that ran 60 s and failed ("stack offset
  for oopmap is greater than encoding limit"). Three compiler threads can each be working on such a graph at once.
- **The Java heap stays in its 1 GB** (~240 MB live after a full GC); the JVM runtime ~200 MB; glibc's kept malloc
  ~100-150 MB, flat: no growth with requests from PCRE2's match data or curl. No per-request native leak in our code.
- **What does grow is compiled code:** 81 MB → 714 MB in 30 minutes (~20 MB a minute: machine code and NMT's
  metadata for it), because compilation never stops (the CPU split above). At 30 minutes the non-profiled code heap
  is full (248 of 253 MB); its growth then ends, but so does further compilation.
- **The native image** has the same burst later: its runtime compiler works inside the image's process; in the
  bench it reached 10-11 GB at 4-16 users (10-25 minutes into its life), and 1.0-1.6 GB in a 12-minute memrun.
- **php-fpm**, the same 12 minutes at 16 users: RssAnon 329 MB over its 16 workers, 151 MB of OPcache shmem,
  `memory.current` 403 MB, `memory.peak` 405 MB. Flat.

### Mitigations, measured
Each a 12-minute server life from cold at 16 users (`mem-runs.txt`); single runs on a shared box (the arena run,
which shouldn't change speed, ran 30% slower than the baseline), so the throughput column is a rough guide:

| run | peak RssAnon | libgraal at peak | pages/s | p50 ms | Truffle compile CPU-s |
|---|---|---|---|---|---|
| baseline (`m-jvm16-trace`, 15 min) | 6.8-8.9 GB | 5.2 GB | 16.1 | 890 | 1570 |
| `engine.Splitting=false` | 9.1-10.2 GB | 7.6 GB | 12.4 | 1128 | 1378 |
| `engine.CompilerThreads=1` | 3.7-3.9 GB | 2.4 GB | 16.4 | 854 | 532 |
| `MALLOC_ARENA_MAX=2` | 8.7-9.5 GB | 7.2 GB | 11.4 | 1239 | 1351 |
| `compiler.MaximumGraalGraphSize=50000` | **1.9-2.1 GB** | 0.27 GB | 12.9 | 1081 | 1207 |
| native image | 1.8 GB (in 12 min) | | 5.3 | 2785 | |

- **The graph-size limit removes the burst:** the compilations of the largest functions bail out, so libgraal never
  needs GBs (peak 2.1 GB, flat after warm-up); those functions stay interpreted, which costs some throughput.
- **One compiler thread** halves the peak (and delays it: at 12 minutes it was still rising, 2.4 GB of libgraal) at
  no loss in throughput here: the requests get the cores the compilers took.
- **Splitting off** doesn't help memory (the big graphs remain), and it was slower.
- **MALLOC_ARENA_MAX=2** shrinks glibc's kept memory (from ~100 to ~30 MB) but not the burst.
- **The native image doesn't avoid it** (the bench's 10-11 GB), it only reaches it later.
- Not tried: a heap limit for libgraal itself. HotSpot's `-XX` flags have none, and I found no documented option
  for the libgraal isolate's heap in GraalVM 25.4.

**What would fix it, as opposed to capping it:** less to compile. The compile volume comes from splitting (copies
per call site of WooCommerce's and WordPress's small helpers) and from PE graphs of whole large PHP functions; both
are known from Gate 2 (`@ReportPolymorphism` on every `ExprNode`, [gate-2.md](gate-2.md)). A server today would want
`CompilerThreads=1` and a graph limit, or a hard memory limit with headroom for ~4 GB of compiler.
