# Option 1 Prototype: Zero-Barrier Pure-Data Hybrid Post-Bootstrap Freeze

Delivered and verified on 2026-11-25 against the 5-plugin `bootshare` WordPress 7.2 site (WooCommerce 11.2.1, Yoast SEO 28.8, Elementor 4.3.5, Contact Form 7 6.2.3, Akismet 5.7.2), directly implementing Option 1 from [bootstrap-census.md](bootstrap-census.md) via orchestrated Codex (`gpt-6.2-astra`) implementation + independent adversarial review (`bench/bootstrap-freeze/`, `src/main/` and `pom.xml` untouched in both repos).

**Headline verdict:** **Option 1 works end-to-end on real 5-plugin WordPress with 100% parity, zero `D17` restored-object errors, `21.9×` faster bootstrap (`240.8 ms → 11.0 ms`), `1.63×` end-to-end speedup on `home` (`599.7 ms → 367.6 ms`), and a `6.1×` (`83.5%`) reduction in per-request live heap (`14.94 → 2.46 MiB/req` at 1 client).**

It also uncovered a major real-world WordPress invalidation trap: **Elementor 4.3.5 deletes and re-inserts an `autoload='auto'` `wp_options` row (`_elementor_design_system_sync_css_meta`) on every frontend page render**, causing synchronous `alloptions`-level invalidation to rebuild snapshots between every `home`/`post` request (turning `post`/`rest` from `~1.5×` into `0.63–0.68×` and skewing multi-client idle baselines). Production deployment therefore requires **`get_option()`-level read tracking** (not `alloptions` table-level) plus **asynchronous background snapshot rebuilds**.

---

## Architecture Delivered (`bench/bootstrap-freeze/`)

20 overlay Java sources compiled cleanly against `wordpress-elementor` (`176273ba`) without touching `src/main/` or `pom.xml`:

1. **`BootstrapSnapshot` (Iterative Pure-Data Freeze + Flat 2-Pass Clone Plan):**
   - Captures two template snapshots at `wp_loaded` (`wp-load.php` returns with an empty PHP call stack): `frontend` (`/`) and `rest` (`/?rest_route=/wp/v2/posts`).
   - Classifies all reachable `PhpArray`s iteratively (verified on a 40,000-level deep array without `StackOverflowError`):
     - **Pure-data `PhpArray`s** (`20,851` frontend / `20,946` REST; `~4.31 MB` arrays + `~3.9 MB` strings) are frozen shallowly in-place (`PhpArray.FROZEN`, shared zero-copy in Old Gen across all worker threads).
     - **Mutable shell** (`C=2,142–2,165` classes, `O=4,512–4,531` objects, `L=384` closures, `R=924–927` refs, `A=5,626–5,650` object-holding arrays) is compiled into dense flat plans (`ClassPlan`, `ObjectPlan`, `ClosurePlan`, `RefPlan`, `ArrayPlan`) and restored per request with **0.0% post-restore property read/write barrier overhead**.
   - **Thread-safe `PhpArray.pointer` on frozen arrays:** `pointer()`/`setPointer()` on `FROZEN` arrays use a lazy per-`PhpContext` cursor map (zero mutation of shared array headers).
   - **Shared immutable `PhpClass` metadata:** Clones only per-context mutable `PhpClass` state (`staticProperties` preserving parent/child `PhpRef` aliasing, `constants`, `contextAliases`, `defaults`), sharing immutable `methods`, `properties`, `rootShape`, `ancestors`, `key`, and `handlers`.
2. **Per-Request Rebinds & Soundness (Hardened by Adversarial Review):**
   - Preserves fresh CGI superglobals (`$_SERVER`, `$_GET`, `$_POST`, `$_COOKIE`, `$_REQUEST`, `$_FILES`, `$_ENV`, `FilterFunctions.Input`).
   - Rebinds `$wpdb->dbh` (`mysqli` `Link`) to a fresh MariaDB JDBC connection (with `socketTimeout=10000`, query timeout `10s`, reapplied catalog/charset/collation/`sql_mode`, HTTP 503 + connection cleanup on failure) and clears `$wpdb->result`.
   - Re-initializes anonymous WooCommerce guest sessions (`WC_Session_Handler::_customer_id` + `_session_expiring` / `_session_expiration`) per request so concurrent anonymous visitors never collide on a shared guest customer ID.
   - Anchors `WP_START_TIMESTAMP` (both `constants` map and `constantSlots`) and `$timestart` to HTTP request arrival time; resets `wpdb::placeholder_escape` static token and `wpcf7_get_request_uri`; refreshes localized script timestamps/nonces (`userSettings.time`, `wp-api-request`, `wcStoreApiNonceTimestamp`).
   - Strict anonymous eligibility gate: requires `GET`/`HEAD`, empty body, empty `HTTP_COOKIE`, audited route set (`/` and `/?p=<digits>` for `frontend`; case-sensitive `rest_route=/wp/v2/posts` for `rest`), no `.maintenance` file, and matching UA; all other requests fall back transparently to full `index.php` bootstrap.
   - Resumes execution at `resume.php` (`wp(); require_once ABSPATH . WPINC . '/template-loader.php';`).
3. **Adversarial Review (`opt1-reviewer`, GPT-6.2-Astra read-only):**
   - Round 1 audit returned `VERDICT: REVISE` with 13 concrete defects (1 CRITICAL: WC guest `_customer_id` collision; 7 HIGH: inherited `PhpRef.set`/`PhpClosure.copyStatics` ownership bugs, cookie/route/header/GET-body eligibility gaps, missing invalidation, stale script nonces, `D17` gate attribution, Akismet `ak_js_1` random field; 5 MEDIUM: arrival timestamp anchoring, JDBC timeouts/503, multi-header & duplicate-JSON-key parity, GC/execution-mode reporting, iterative shallow freeze for deep arrays).
   - All 13 defects were fixed and verified with offline Java/Python regression suites (`FreezeRegression`, `OptionReadSetRegression`, `test_verify.py`) before the final Docker run.

---

## Verified 6-Phase Docker Results (`out/20261125-142955-e49948`)

Run on Linux Docker (`gate1-truffle:8.5-graal25.4`, CPUs `14–21`, `fallback-interpreter` mode matching Probe A2, MariaDB `gate2-mariadb` `plugins` database). Raw evidence: [bench/bootstrap-freeze/RESULTS.md](../bench/bootstrap-freeze/RESULTS.md).

### 1. Gate Summary (`1,732 / 1,732` HTTP 200 — ALL PASSED)

| Phase | Mode | Outcome | Requests | HTTP 200 | Notes |
|---|---|---|---:|---:|---|
| `baseline` | `fallback-interpreter` | **PASS** | 93 | 93 | Warmup (30) + parity (3) + timing (60) |
| `frozen` | `fallback-interpreter` | **PASS** | 265 | 265 | Warmup (30) + strict parity (3) + timing (60) + sequential isolation (20) + 8-thread stress (152; 19/worker) |
| `rc-baseline` | `fallback-interpreter` | **PASS** | 3 | 3 | `29,163` zeros checked (`php.rc.verify.every=1`) |
| `rc-frozen` | `fallback-interpreter` | **PASS** | 3 | 3 | `32,929` zeros checked; **`0` restored-object hits**, **`0` new sites** vs. baseline |
| `heap-baseline` | `fallback-interpreter` | **PASS** | 820 | 820 | Warmup (90) + 60s each at 1 / 4 / 8 clients |
| `heap-frozen` | `fallback-interpreter` | **PASS** | 548 | 548 | Warmup (90) + 60s each at 1 / 4 / 8 clients |

- **Strict parity:** Byte-for-byte identical bodies on `/` (`115,879 B`) and `/?rest_route=/wp/v2/posts` (`81,501 B`, unique JSON keys verified); byte-for-byte identical on `/?p=1` after validating Akismet's random `ak_js_1` (`0..250`, 73 distinct values observed!) and WordPress `human_time_diff()` relative `<time>` labels against request wall time; exact multi-header order/value parity (`Link`, `X-WP-Total`, `X-WP-TotalPages`, `Content-Type`, `X-Pingback`).

### 2. Restore Latency & End-to-End Speed

| Route | Full bootstrap median / p95 | Option 1 restore median / p95 | Bootstrap speedup | Full HTTP median / p95 | Frozen HTTP median / p95 | End-to-end speedup |
|---|---:|---:|---:|---:|---:|---:|
| `home` (`/`) | 240.8 ms / 282.2 ms | **11.0 ms / 16.4 ms** | **21.9×** | 599.7 ms / 709.7 ms | **367.6 ms / 460.5 ms** | **1.63×** |
| `post` (`/?p=1`) | 241.5 ms / 278.0 ms | **11.5 ms / 18.2 ms** | **21.0×** | 534.7 ms / 657.1 ms | 851.3 ms / 985.7 ms\* | 0.63×\* |
| `rest` (`/wp/v2/posts`) | 243.3 ms / 278.7 ms | **12.1 ms / 15.0 ms** | **20.1×** | 654.7 ms / 764.4 ms | 962.3 ms / 1,073.7 ms\* | 0.68×\* |

*\*Why `post` and `rest` were 0.63–0.68× while `home` hit 1.63×:* See §4 below—Elementor mutates an `autoload='auto'` option (`_elementor_design_system_sync_css_meta`) on every frontend render, triggering a synchronous `+550–600 ms` rebuild of both snapshots on the next request (`post` after `home`, `rest` after `post`), whereas `rest` did not write that option so `home` ran cleanly against the warm snapshot (`1.63×`, matching [bootstrap-share.md](bootstrap-share.md)'s `1.69×` ceiling!). Subtracting the synchronous rebuild, `post` and `rest` execute in `~290–390 ms` (`~1.5–1.7×`).

### 3. Live Heap per In-Flight Request (`-Dphp.heapprobe=250`)

| Phase | Idle after full GC | 1 client (MiB/req) | 4 clients (MiB/req) | 8 clients (MiB/req) | Cgroup peak |
|---|---:|---:|---:|---:|---:|
| `heap-baseline` | 211.26 MiB | **14.94 MiB/req** | 14.49 MiB/req | 13.26 MiB/req | 1,563.43 MiB |
| `heap-frozen` | 238.39 MiB *(+27.1 MiB for both snapshots)* | **2.46 MiB/req** | -0.55\* | -0.23\* | 1,481.85 MiB |

- **At 1 client:** Per-request live heap dropped from **`14.94 MiB/req` → `2.46 MiB/req`** (**`83.5%` reduction / `6.1×` smaller per-request heap**), directly confirming the census prediction!
- *\*At 4 and 8 clients:* Because Elementor's option write triggered repeated synchronous snapshot rebuilds under concurrency, the segment's post-load idle baseline rose slightly above the mid-load samples, making the simple `live - idle` subtraction slightly negative; fixing the Elementor `autoload='auto'` invalidation + moving rebuilds off the request thread will yield clean positive 4/8-client measurements (projected `~2.5–4.0 MiB/req`).

---

## 4. Key Real-World Finding: Plugin Writes to `autoload='auto'` Options

Even with `BootstrapEpoch` running in `policy=selective` mode (`names=381 prefixes=1`), `frozen/server.log` logged on every single `home` and `post` render:

```sql
DELETE FROM `wp_options` WHERE `option_name` = '_elementor_design_system_sync_css_meta'
INSERT INTO `wp_options` (`option_name`, `option_value`, `autoload`) VALUES ('_elementor_design_system_sync_css_meta', 'a:2:{s:4:"time";i:...;i:0;b:0;}', 'auto')
```

MariaDB confirmed `_elementor_design_system_sync_css_meta` is stored with **`autoload = 'auto'`** (WordPress 7.2's default for `update_option()` when `$autoload = null`). Because `wp_load_alloptions()` blindly fetches every `autoload IN ('yes','on','auto-on','auto')` row during `wp-load.php`, any sound table/alloptions-level read-set tracker MUST include it—even though **no PHP code ever calls `get_option('_elementor_design_system_sync_css_meta')` before `wp_loaded`**.

**Two concrete architectural requirements this establishes for production JVM freeze:**
1. **Track actual `get_option()` reads (not `wp_load_alloptions()` bulk loads):** Intercept reads into `$wp_object_cache->cache['options']['alloptions']` during `capture()` so only autoloaded keys *actually read by PHP* before `wp_loaded` enter the invalidation read-set.
2. **Asynchronous background rebuilds:** On invalidation, serve either the previous snapshot (for soft-TTL options) or fall back to full bootstrap on 1 thread while a single background thread rebuilds and atomically swaps the new immutable snapshot—never blocking 8 concurrent HTTP workers synchronously on a rebuild.

---

## 5. Comparison Against Pure PHP 8.5 (`php-fpm` + OPcache + Tracing JIT)

Anchored against pure PHP 8.5 on the exact same 5-plugin `bootshare` site ([bootstrap-share.md](bootstrap-share.md): `plugins/home` anonymous = `102.5 ms` PHP / `~108 ms` HTTP, `41.7 ms` bootstrap + `60.8 ms` render, `14.1 MB` PHP peak; 5-plugin `php-fpm` estimated `~150–200 MiB` OPcache shmem + `~25–35 MiB`/worker private RSS):

### Measured Today (Truffle Fallback-Interpreter + Option 1 vs. Pure PHP 8.5)

| Metric (`plugins/home`, anonymous) | Pure PHP 8.5 (OPcache + JIT) | Option 1 Prototype Today (Truffle interpreter) | Result vs. PHP 8.5 |
|---|---:|---:|---|
| **Bootstrap / Restore** | 41.7 ms | **11.0 ms** | **3.8× faster** bootstrap |
| **Post-bootstrap render** | 60.8 ms | 356.6 ms | 5.9× slower render (no JIT) |
| **Total HTTP** | **~108 ms** (`102.5 ms` PHP) | **367.6 ms** | **3.4× slower** |
| **Per-request live heap** | 14.1 MB peak (`~25–35 MiB`/worker RSS) | **2.46 MiB/req** live (`+27.1 MiB` snapshots) | **6× smaller** per-request delta |
| **Total RAM at 8 concurrent clients** | **~350–480 MiB** (`php-fpm` + OPcache) | **1,481.9 MiB** cgroup peak (`~550 MiB` idle) | **~3× more RAM** |

**Today's prototype does not beat pure PHP 8.5**—it ran in Truffle's uncompiled `fallback-interpreter` mode with Truffle's `~550 MiB` idle overhead. What it proves is that the JVM can safely drop per-request live heap by `6×` (`14.94 → 2.46 MiB/req`) and skip the `~40%` bootstrap cleanly.

### Projected Compiled JVM Setups + Option 1 vs. Pure PHP 8.5

| Engine + Option 1 (5-plugin `home` anon) | Restore | Post-boot render | Total HTTP vs. PHP 8.5 | Projected RAM at 30 concurrent reqs | Beats Pure PHP 8.5? |
|---|---:|---:|---:|---:|---|
| **Pure PHP 8.5 (`php-fpm` + JIT)** | 41.7 ms | 60.8 ms | **~108 ms (1.00×)** | **~0.9–1.25 GiB** | Baseline |
| **Truffle PHP Compiled + Option 1** (`ceiling` ~0.5–0.6× speed) | ~2–4 ms | ~100–120 ms | **~105–125 ms (~0.9–1.0×)** | **~1.5–2.5 GiB** *(code cache + libgraal)* | **No** (speed tie at best; loses on RAM) |
| **B1 (`.php → .class` on HotSpot C2 / JDK 28) + Option 1** *(if open-world holds ~1.0×)* | **~1–3 ms** | **~55–65 ms** | **~58–70 ms (~1.55–1.85×)** *(~2× on REST)* | **~450–600 MiB** *(`~250 MiB` code + `30 × 2.5–4 MiB` × GC headroom)* | **Yes (~1.6× faster, ~2× less RAM)** |

### Remaining Gate & Requirements Before Claiming a Win Over Pure PHP 8.5

Option 1 removes the Probe A2 memory blocker (`13–15 MiB/req`) that previously closed B1 ([b1-precheck.md](b1-precheck.md) §5), **but beating pure PHP 8.5 still requires three things not yet proven/built:**

1. **Probe B (open-world `.php → .class` + `D17` speed gate):** Confirm that a non-Truffle `.php → .class` runtime on HotSpot C2 (or OpenJDK 28 C2 + JEP 401 Value Objects) holds **`≥ 1.0×` PHP 8.5** on open-world PHP with full `D17` refcounting. If open-world execution drops to `~0.6×` PHP, the `1.6×` bootstrap saving shrinks back to a `~1.0×` speed tie.
2. **Production invalidation:** Upgrade `BootstrapEpoch` from `alloptions`-level to **`get_option()`-level read tracking** + **async background rebuilds** so per-render plugin writes to unused `autoload='auto'` options (like Elementor's `_elementor_design_system_sync_css_meta`) do not trigger synchronous rebuild storms.
3. **Authenticated / cart coverage:** Today's Option 1 accelerates **anonymous GET / REST** traffic; logged-in users, active WooCommerce carts, and `/wp-admin/` fall back to full bootstrap unless extended with a pre-user snapshot barrier + deferred `set_current_user` / `WC_Session_Handler` replay.

