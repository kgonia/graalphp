# Post-Bootstrap Graph & Mutation Census (5-Plugin WordPress)

Measured on 2026-11-25 against the 5-plugin `bootshare` WordPress 7.2 site (WooCommerce 11.2.1, Yoast SEO 28.8, Elementor 4.3.5, Contact Form 7 6.2.3, Akismet 5.7.2) to decide the JVM architecture for freezing an immutable post-bootstrap object graph across concurrent requests.

**Bottom line:** **Hybrid Selective Freeze wins decisively.**
- **95% of the `9.4–9.6 MB` graph at `wp_loaded` (`95.6%` of `13.8 MB` at `rest_api_init`) is pure `PhpArray` + `TruffleString`**; all `PhpObject`s combined are only **`0.39 MB`** (`4,512–4,617` instances).
- **84% of the `wp_loaded` graph (`~7.9 MB`: `4.0 MB` pure-data `PhpArray`s + `3.9 MB` strings) contains zero `PhpObject`, `PhpClosure`, or `PhpRef` transitively.** Even cloning *every* object, closure, ref, and object-holding array per request leaves only **`~1.5 MB`** per-request shell with **zero** post-bootstrap object read/write barrier overhead!
- **93–98.5% of pre-barrier `PhpObject`s and 99.2–99.5% of `PhpArray`s are never mutated after the barrier.** Of the `~243–315` mutated objects, **60–87% are `WP_Hook`**, plus `_WP_Dependency` and ~70 singletons (`WP_Object_Cache`, `wpdb`, `WP_Query`, etc.).
- **Zero `__destruct`, zero lazy, zero hooked-property objects exist at `wp_loaded`**, and only **2 native-handle objects** (`mysqli` + `mysqli_result`).
- **99.56% of top-level roots (`2,266 / 2,276`) are bit-for-bit identical** between two consecutive anonymous requests at `wp_loaded`; freezing also skips **79–100 MariaDB queries** per request.

---

## Method

- **Probe harness:** [bench/bootstrap-census/](../bench/bootstrap-census/run.ps1) — isolated classpath overlay (`BootstrapCensus.java` + 7 instrumented runtime/node classes on top of `wordpress-elementor` `176273ba`), leaving `src/main/` untouched.
- **Trigger:** mu-plugin `zzz-census.php` hooks `wp_loaded` (and `rest_api_init` on REST) at `PHP_INT_MAX` and calls `truffle_bootstrap_census($barrier, $page)`.
- **Dual verification:** Every write to pre-barrier `PhpObject`, `PhpArray` (content vs. `pointer`-only vs. CoW `copy()`), and `PhpRef` is write-tracked during post-barrier execution **and** independently cross-checked against a full structural diff at request shutdown (`PhpContext.endScript()` after `runShutdownFunctions()`).
- **Cross-check result:** **`0 / 0 / 0 / 0` missed writes** (objects / array content / array pointer / refs) across all 7 measured requests.
- **Evidence:** `bench/bootstrap-census/out/` (`summary.txt`, `report.txt`, `server.log`, and per-request `.json` files).

---

## 1. Graph Composition at the Barrier

Estimated HotSpot 64-bit CompressedOops heap size (with deduplicated `TruffleString` backing `byte[]`):

| Page | Barrier | Total | `PhpArray` | `TruffleString` | `PhpObject` | `PhpClosure` + `PhpRef` | Objects | Arrays | Strings | Closures | Refs |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `home-anon-1` | `wp_loaded` | **9.42 MB** | 5.08 MB (54%) | 3.89 MB (41%) | 0.39 MB (4.1%) | 0.06 MB (0.6%) | 4,512 | 25,602 | 40,486 | 385 | 929 |
| `home-anon-2` | `wp_loaded` | **9.38 MB** | 5.05 MB (54%) | 3.89 MB (41%) | 0.39 MB (4.2%) | 0.06 MB (0.6%) | 4,512 | 25,602 | 40,486 | 385 | 929 |
| `home-auth` | `wp_loaded` | **9.52 MB** | 5.12 MB (54%) | 3.95 MB (41%) | 0.39 MB (4.1%) | 0.06 MB (0.6%) | 4,600 | 26,023 | 41,110 | 407 | 940 |
| `post-auth` | `wp_loaded` | **9.52 MB** | 5.12 MB (54%) | 3.95 MB (41%) | 0.39 MB (4.1%) | 0.06 MB (0.6%) | 4,599 | 26,020 | 41,107 | 407 | 940 |
| `dashboard-auth` | `wp_loaded` | **9.61 MB** | 5.24 MB (55%) | 3.92 MB (41%) | 0.39 MB (4.1%) | 0.06 MB (0.6%) | 4,617 | 26,769 | 41,068 | 454 | 939 |
| `rest-anon` | `wp_loaded` | **9.39 MB** | 5.06 MB (54%) | 3.89 MB (41%) | 0.39 MB (4.2%) | 0.06 MB (0.6%) | 4,531 | 25,698 | 40,323 | 385 | 932 |
| `rest-anon` | `rest_api_init` | **13.81 MB** | 8.75 MB (63%) | 4.45 MB (32%) | 0.50 MB (3.6%) | 0.11 MB (0.8%) | 6,235 | 45,683 | 47,143 | 729 | 1,799 |

### Array Purity Split (Transitive Reachability)

| Page | Barrier | Pure-Data `PhpArray`s (no obj/cl/ref) | Holds Obj/Cl/Ref | Transitively Reaches Any Mutated Obj/Ref |
|---|---|---:|---:|---:|
| `home-anon` | `wp_loaded` | **19,973 (4.00–4.01 MB)** | 5,629 (1.05–1.07 MB) | **716 (0.22 MB)** |
| `home-auth` / `post-auth` | `wp_loaded` | **20,150–20,153 (4.03 MB)** | 5,870 (1.09 MB) | **788–809 (0.23 MB)** |
| `dashboard-auth` | `wp_loaded` | **19,915 (3.99 MB)** | 6,854 (1.25 MB) | **578 (0.18 MB)** |
| `rest-anon` | `wp_loaded` | **20,045 (4.01 MB)** | 5,653 (1.05 MB) | **289 (0.11 MB)** |
| `rest-anon` | `rest_api_init` | **32,976 (6.52 MB)** | 12,707 (2.23 MB) | **182 (0.10 MB)** |

### Where the Memory Sits (Top Roots at `wp_loaded`, `home-anon`)

| Root | Total Reachable | Exclusive | Notes |
|---|---:|---:|---|
| `$wp_filter` | 3.83 MB (31,230 nodes) | 2.53 MB | 1,703 `WP_Hook`s + priority/callback arrays |
| `WP_Block_Type_Registry` | 1.41 MB (10,632 nodes) | 0.32 MB | 296 `WP_Block_Type`s + schemas |
| `$jetpack_autoloader_loader` / `$jetpack_packages_classmap` | 1.34 MB (7,942 nodes) | 0.00 MB (shared) | Pure string classmaps |
| `WP_Block_Metadata_Registry` | 1.05 MB (6,355 nodes) | 0.13 MB | Block metadata arrays |
| Composer classloaders (`$yoast_autoloader`, etc.) | 0.67 MB (4,573 nodes) | 0.00 MB (shared) | Pure string classmaps |
| `TruffleReflection::$state` | 0.56 MB (5,960 nodes) | 0.56 MB | Truffle PHP prelude artifact (would not exist in a standalone JVM runtime) |
| `$wp_rest_server` (`rest_api_init` only) | 5.50 MB (37,870 nodes) | 3.18 MB | REST route & StoreApi schema definitions |

### Special & Unfreezable Objects at Barrier

- **`__destruct` objects:** **0** across all 7 runs.
- **Lazy objects / hooked properties:** **0** across all 7 runs.
- **Native-handle (`internal != null`) objects:** **2 total** across all 7 runs — `mysqli` (`Link` × 1, `$wpdb->dbh`) and `mysqli_result` (`Result` × 1, `$wpdb->result`).

---

## 2. Post-Barrier Mutation Footprint

All counts combine **persistent** diffs (still changed at shutdown) + **transient** mutations (mutated during request then restored to original value, e.g. `WP_Hook::$nesting_level` `0 → 1 → 0`), verified with `0` missed writes:

| Page | Barrier | Mutated `PhpObject`s (`P+T / Tot`) | Untouched Objects | Mutated `PhpArray`s (`Content P+T` / `PtrOnly` / `CoW`) | Untouched Arrays | Mutated `PhpRef`s (`P+T`) | Missed Writes (`O/AC/AP/R`) |
|---|---|---:|---:|---:|---:|---:|---:|
| `home-anon-1/2` | `wp_loaded` | 218+25 / 4,512 (**5.4%**) | **94.6%** | 82+53 / **1** / 180 | **99.5%** (4.85 MB) | 32+9 / 929 | `0/0/0/0` |
| `home-auth` | `wp_loaded` | 264+25 / 4,600 (**6.3%**) | **93.7%** | 119+52 / **1** / 204 | **99.3%** (4.91 MB) | 37+9 / 940 | `0/0/0/0` |
| `post-auth` | `wp_loaded` | 287+28 / 4,599 (**6.8%**) | **93.2%** | 136+64 / **1** / 222 | **99.2%** (4.90 MB) | 39+9 / 940 | `0/0/0/0` |
| `dashboard-auth` | `wp_loaded` | 202+32 / 4,617 (**5.1%**) | **94.9%** | 135+65 / **1** / 215 | **99.2%** (5.03 MB) | 42+7 / 939 | `0/0/0/0` |
| `rest-anon` | `wp_loaded` | 157+32 / 4,531 (**4.2%**) | **95.8%** | 112+61 / **1** / 105 | **99.3%** (4.90 MB) | 27+13 / 932 | `0/0/0/0` |
| `rest-anon` | `rest_api_init` | 67+27 / 6,235 (**1.5%**) | **98.5%** | 54+624 / **1** / 826 | **98.5%** (8.43 MB) | 9+11 / 1,799 | `0/0/0/0` |

### Key Mutation Observations

1. **Mutations are overwhelmingly concentrated in `WP_Hook` and a tiny handful of singletons:**
   - `home-anon`: **148/243** mutated objects are `WP_Hook`, **22** are `_WP_Dependency`, and ~70 are 1-of-1 singletons (`WP_Object_Cache`, `wpdb`, `WP_Query`, `WP_Styles`, widgets, Elementor modules).
   - `rest-anon-restinit`: **82/94** mutated objects are `WP_Hook`, plus just **12** singletons (`wpdb`, `WP_Object_Cache`, `WP_Query`, `WP_REST_Server`, `Elementor\Frontend`, Yoast memoizers).
   - Top property write sites: `WP_Hook::$nesting_level` (`5,329–8,231` writes/req), `WP_Object_Cache::$cache_hits` (`2,063–4,680` writes/req), `wpdb::$last_query`/`$result`/`$last_error` (`82–308` writes/req), `WP_Hook::$priorities`/`$iterations`/`$current_priority`.
2. **`PhpArray.pointer`-only mutations are negligible:** Literally **1 pre-barrier array per request** (`9–153` ops, `0.00 MB`) had only its internal cursor moved without content writes.
3. **Symbol table growth after `wp_loaded`:**
   - Functions and constants are **100% frozen on frontend/REST** (`+0` functions, `+0–1` constants after `wp_loaded`; `dashboard` adds `+441` admin functions).
   - Autoloading still loads `+397–474` classes on frontend (`+64` on REST after `rest_api_init`), requiring a per-request delta overlay on the class & included-file tables.

---

## 3. Cross-Request Divergence & Bootstrap Taint

### Root Fingerprint Diffs at `wp_loaded`

| Pair | Differing Roots | What Differs |
|---|---:|---|
| `home-anon-1` vs `home-anon-2` (2s apart) | **10 / 2,276 (0.44%)** | `$_SERVER` (`REQUEST_TIME*`), `$timestart`, `WP_START_TIMESTAMP`, `wpdb::placeholder_escape` random static token, `ActionScheduler_QueueRunner::$runner` start time, `$woocommerce`, `$wp_object_cache`, `$wp_scripts`, test header `$census_page` |
| `home-auth` vs `post-auth` (`/` vs `/?p=1`) | **20 / 2,284 (0.88%)** | Same 10 time/random roots + `$_GET`, `$_REQUEST`, `wpcf7_get_request_uri` static `$request_uri` |
| `home-anon-1` vs `rest-anon` (`wp_loaded`) | **31 / 2,281 (1.36%)** | Same + `$_GET['rest_route']`, 5 WooCommerce Admin task-list statics, 5 extra hooks on `$wp_filter` |
| `home-anon-1` vs `home-auth` | **127 / 2,284 (5.56%)** | `$current_user`, `$user_*`, `$show_admin_bar`, `$_COOKIE`, WooCommerce onboarding/cart session, +36 hooks, +46 included files |
| `home-auth` vs `dashboard-auth` | **209 / 2,344 (8.92%)** | Admin-only classes/globals (`WPSEO_Admin`, `WC_Brands_Admin`, `Akismet_Admin`), +105 hooks, +224 included files |

### Pre-Barrier Taint Summary

- **SQL before `wp_loaded`:** **79–100 queries** (`98` at `rest_api_init`) — `SET NAMES`, `autoload` options, plus ~75 individual `SELECT option_value FROM wp_options` / transient lookups for WooCommerce, Elementor, Yoast, Akismet. Freezing eliminates these DB round-trips per request (invalidation watches `wp_options` writes).
- **Superglobals read before `wp_loaded`:** **35–56 unique keys** (`$_GET[rest_route]`, `$_GET[page]`, `$_GET[wc-ajax]`, `$_COOKIE[wordpress_logged_in_*]`, `$_COOKIE[wp_woocommerce_session_*]`, `$_SERVER[HTTP_HOST]`, etc.) — mostly negative presence checks that determine request class (frontend vs. REST vs. admin vs. logged-in vs. WooCommerce session).
- **Non-deterministic calls before `wp_loaded`:** `13–24` unique `time()`/`microtime()` sites (start timestamps, transient expiry checks, session token checks, `wpdb::placeholder_escape`).

---

## 4. Architectural Conclusion for a JVM Engine

The census eliminates the worst-case fear (scattered mutations across tens of thousands of deep objects) and points to two clean, concrete JVM designs—both avoiding Truffle PE:

### Option 1 — Zero-Barrier Pure-Data Hybrid Freeze (Simplest)
- **Freeze once in Old Gen:** All **pure-data `PhpArray`s** (`4.0 MB` at `wp_loaded`, `6.5 MB` at `rest_api_init`) + all **`TruffleString`s** (`3.9–4.5 MB`) = **`~7.9 MB` (`84%`) at `wp_loaded`, `~11.0 MB` (`79%`) at `rest_api_init`** using `PhpArray.FROZEN`.
- **Clone per request from a flat template plan:** All `4,512` `PhpObject`s (`0.39 MB`), `385` `PhpClosure`s (`0.03 MB`), `929` `PhpRef`s (`0.03 MB`), and `5,629` object-holding `PhpArray`s (`1.05 MB`) = **`~1.50 MB` per request** (~0.3–0.5 ms clone time).
- **Runtime overhead after request start:** **0.0%** on every `PhpObject` read/write and `D17` refcount—zero `frozenId`, zero overlay lookups, zero immortal-object branches, 100% normal monomorphic HotSpot C2/Graal objects.

### Option 2 — Hot-Shell Hybrid Freeze (Maximum Memory Density)
- **Freeze once in Old Gen:** Pure-data arrays + strings + the **~94% read-only `PhpObject`s** (`WP_Block_Type`, `_WP_Dependency`, `IntegrationRegistry`, `WP_Post_Type`, `WP_Taxonomy`, StoreApi schemas) + their enclosing arrays = **`~9.1 MB` (`97%`) at `wp_loaded`, `~13.6 MB` (`98.5%`) at `rest_api_init`**.
- **Eagerly clone per request:** Only the small hot mutable set (`WP_Hook`, `WP_Object_Cache`, `wpdb`, `WP_Query`, `_WP_Dependency` active subset, plus the `0.10–0.23 MB` of arrays reaching them) ≈ **`~0.25–0.40 MB` per request**.
- **Fallback safety net:** A single 1-bit `isFrozen` guard on rare writes to any remaining frozen `PhpObject` lazily allocates a per-context overlay entry—while the hot loop (`WP_Hook::$nesting_level`, `WP_Object_Cache::$cache_hits`, `wpdb`) runs on eagerly cloned mutable objects with **zero** overlay lookups.

### Remaining Soundness Work (Shared by Both Options)
1. **Snapshot variants:** Separate immutable snapshots for `frontend-anon`, `rest-anon`, `admin-auth`, plus a taint-gated pre-user boundary (or deferred `set_current_user` + `WC_Session_Handler` replay) for logged-in frontend visitors.
2. **Per-request rebinds at restore:** Rebind `$wpdb->dbh` to a pooled JDBC connection, reset `$timestart` / `WP_START_TIMESTAMP` / `wpdb::placeholder_escape` / `$_SERVER` / `$_GET` / `$_COOKIE`, and clear `wpdb::$result`.
3. **Invalidation:** Watch `wp_options` / plugin-file writes and atomically swap the snapshot pointer on change (in-flight requests finish on the old immutable snapshot and GC collects it).
