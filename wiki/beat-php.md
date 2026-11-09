# Can Truffle PHP beat native PHP on WordPress? The overnight proof (2026-11-09/10)

**Question (owner, 2026-11-09 22:00):** by 07:00, prove whether native PHP (php-fpm 8.5.12, OPcache, tracing JIT) can
be beaten on WordPress or WooCommerce. Scope: plain WordPress (Gate 2's site through nginx and FastCGI), the
deployment both engines are measured in; WooCommerce only if a win shows on plain WordPress first.

**Status:** in progress. Results below are filled in as the runs finish; the verdict is at the top when done.

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
to their state at `template_redirect`, so every iteration prints the page again.

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

(to be filled in)
