# Bootstrap share of WordPress requests on PHP 8.5

Asked on 2026-10-27, after Gate 1 stood at 0.81× of PHP 8.5 JIT on PHP code: could Truffle PHP win without
out-running PHP's JIT, by snapshotting WordPress's state after its bootstrap and starting every request from that
snapshot? The most that could save is the share of a request spent bootstrapping, measured here on PHP itself.

## Method

`bench/bootshare/` (setup.sh, then run.sh; PROFILE=1 for the per-callback profile):

- **Server:** PHP 8.5 with OPcache, the tracing JIT and `validate_timestamps=0`, under `php -S`, one request at a
  time, pinned to CPUs 14-21. The site lives in the container's own file system and reaches the Gate 2 MariaDB over
  the Docker bridge. A first run on the Windows bind mount through host port forwarding was 5-10× slower everywhere,
  and its shares were wrong.
- **Site:** WordPress 7.2-beta1 (Gate 2's trunk commit), its default theme (ipsum, a block theme) and 50 generated
  posts. There are two databases:
  - `core`, with no plugins;
  - `plugins`, with WooCommerce 11.2.1, Yoast SEO 28.8, Elementor 4.3.5, Contact Form 7 6.2.3 and Akismet 5.7.2
    active. These are among the most-installed plugins; a business site often runs more.
- **Production settings:** no debug output, no cron spawned by requests, no outgoing HTTP, no persistent object
  cache.
- **Pages:**
  - the home page, anonymous;
  - a post, logged in;
  - the dashboard (`/wp-admin/`), logged in;
  - the REST collection `/wp/v2/posts`, anonymous.
- **Requests:** 4 rounds of 50 requests per page after 50 warmup requests.
- **Timing:**
  - `auto_prepend_file` stamps the start, and a shutdown function registered during shutdown stamps the end.
  - A mu-plugin stamps the end of each bootstrap hook, after its last callback.
  - Times are medians. curl's whole-request time is 4-9 ms above PHP's.

## Results

Each phase column gives the time, in ms, at which that phase ends. boot % is the median of each request's
`wp_loaded` time divided by its end time.

| page | core | plugins_loaded | init | wp_loaded | end | boot % | peak at wp_loaded | peak |
|---|---|---|---|---|---|---|---|---|
| core/home | 2.5 | 2.9 | 7.3 | 7.3 | 38.8 | 18.7 | 3.1 MB | 4.7 MB |
| core/post | 2.4 | 2.9 | 7.7 | 7.7 | 36.0 | 21.3 | 3.1 MB | 4.6 MB |
| core/dashboard | 2.5 | 3.0 | 7.1 | 7.1 | 31.2 | 22.8 | 2.8 MB | 4.4 MB |
| core/rest | 2.5 | 2.9 | 7.3 | 7.3 | 21.4 | 34.5 | 3.1 MB | 4.9 MB |
| plugins/home | 2.6 | 11.2 | 39.8 | 41.7 | 102.5 | 40.9 | 10.3 MB | 14.1 MB |
| plugins/post | 2.6 | 11.9 | 43.6 | 45.7 | 117.5 | 38.7 | 10.5 MB | 14.7 MB |
| plugins/dashboard | 2.6 | 13.9 | 46.5 | 48.1 | 112.9 | 42.9 | 10.7 MB | 15.1 MB |
| plugins/rest | 2.6 | 11.0 | 40.2 | 41.9 | 104.0 | 40.2 | 10.3 MB | 19.6 MB |

- "core" is the core files, the database connection and the autoloaded options. With OPcache it takes 2.5 ms.
- The plugins' cost is mostly on `init`: 27 ms of the 42 ms.
- REST registers every route on each request, in `rest_api_init` after `wp_loaded`. That ends at 9.3 ms on `core`
  and at 58.8 ms on `plugins`, so route registration is another 17 ms on a plugin site.
- After the bootstrap:
  - the home page and the post spend 58-68 ms rendering the block theme;
  - REST spends 45 ms on the query and on Yoast's SEO head for each post.

### What in the bootstrap depends on the request (PROFILE=1)

The profile times each callback on `plugins_loaded`, `init`, `wp_loaded` and `rest_api_init`, and each plugin's main
file. These are the largest on plugins/home, as a share of 46 ms to `wp_loaded`:

| entry | time | share of 46 ms |
|---|---|---|
| Elementor's init | 9.8 ms | 21% |
| WooCommerce block registration | 5.0 ms | 11% |
| WooCommerce's main file | 2.6 ms | 6% |
| WooCommerce's variation-gallery migration check | 1.9 ms | 4% |
| Yoast's integrations | 1.6 ms | 3% |
| Elementor's main file | 1.6 ms | 3% |
| Action Scheduler's migration check | 1.3 ms | 3% |

The callbacks fall into three kinds:

- **Registration.** Post types, taxonomies, blocks, block styles and patterns, widgets, Elementor's modules,
  WooCommerce's features, and the REST routes. It is the same for every request, and it is most of the time.
- **Request-type dependent.** Some callbacks branch on the kind of request:
  - Yoast's integrations and WooCommerce's admin includes differ between the front end and wp-admin;
  - `rest_api_init` runs on REST requests only;
  - the migration checks read options and the Action Scheduler tables.

  A snapshot would need one variant per request type, and the database state it read would have to stay
  unchanged.
- **Visitor dependent.** WooCommerce's cart session (0.3 ms), Yoast's admin bar (0.2 ms), and the part of
  `WooCommerce::init` that sets up the customer: 1.7 ms logged in against 1.0 ms anonymous. Together this is
  1-3 ms of 46 ms.

The visitor-dependent part is small in time. The difficulty of a snapshot is correctness, not size. Any bootstrap
callback that reads a cookie, the current user or `$_GET` and keeps what it read would hand that to the next
visitor. PHP's semantics give no way to prove a callback doesn't. So a snapshot would need to track every such read
during the bootstrap, and fall back to a full bootstrap when one happens.

## What it means

The ceiling of a snapshot, with everything up to `wp_loaded` skipped (on REST, up to the end of `rest_api_init`):

| page | `core`: PHP + snapshot | `plugins`: PHP + snapshot | `plugins`: Truffle PHP at 0.81× + snapshot |
|---|---|---|---|
| home | 1.23× | 1.69× | 1.37× |
| post | 1.27× | 1.64× | 1.33× |
| dashboard | 1.30× | 1.74× | 1.41× |
| REST | 1.77× | 2.30× | 1.86× |

The last column scales the time after the bootstrap by Gate 1's 0.81×. That's a rough guide: these pages' share of
regex and other built-ins isn't Gate 1's.

- **Speed:**
  - Even at the ceiling, a bare WordPress gains 1.2-1.3× on pages.
  - A plugin-heavy site gains 1.6-1.7× on pages and 2.3× on REST. On Truffle PHP that becomes 1.3-1.4× and 1.9×,
    because Truffle is still slower than PHP on the rest of the request.
- **Not specific to Truffle.** The same saving is open to regular PHP. Copy-on-write `fork()` of a worker after
  `wp_loaded` restores the same pristine state per request. A PHP server or extension could do that, with the same
  correctness problem: the visitor-dependent reads, and connections opened during the bootstrap. Its cost isn't
  measured here: a fork of a process with ~15 MB of request heap is likely well under 1 ms.
- **Memory:**
  - By `wp_loaded`, a plugin-site request has reached 10.3-10.7 MB of its 14-20 MB peak (~70%); a bare site, 3 of
    5 MB. A snapshot shared across concurrent requests would hold that once instead of once per request. PHP-FPM
    already shares the compiled code through OPcache's shared memory; this would be the heap state on top.
  - Truffle PHP's fixed cost is 0.5-1.5 GB per process (gate-1.md). Against that, sharing ~10 MB per concurrent
    request pays only at high concurrency, beyond roughly 50-150 requests in flight.

**Conclusion:** the bootstrap is 19-35% of a request on bare WordPress and 39-43% with popular plugins. That is a
real but bounded saving, which regular PHP can also take with `fork()`. A snapshot alone doesn't make Truffle PHP a
faster WordPress. It would add up to 1.3-1.9× on plugin-heavy sites, on top of an engine that is still at 0.81× on
PHP code.
