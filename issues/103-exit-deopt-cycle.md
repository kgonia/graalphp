# 103: `exit` invalidates the compiled code of every function on the call stack; after fifteen recompilations Graal gives the function up and it is interpreted for the rest of the server's life

**State:** Open. Found 2026-11-10 during [wiki/beat-php.md](../wiki/beat-php.md), in the compilation trace of the
FastCGI server (`-Dpolyglot.engine.TraceCompilation=true`). The mechanism is read from the source and confirmed by
a controlled run (the same measurement with and without the `exit`); the engine was not changed that night.

**What happens:**

- **PHP's `exit` is `TruffleContext.closeExited`** (`PhpContext.exit`: the shutdown functions, the generators and
  fibers, the request's end, then `env.getContext().closeExited(null, status)`). The exception that call throws is
  neither an `AbstractTruffleException` nor a `ControlFlowException`.
- **The Bytecode DSL interpreter treats such an exception as an internal error.** The generated `resolveThrowable`
  (`PhpBytecodeRootNodeGen`) does, in every frame the exception leaves:

  ```java
  if (throwable instanceof AbstractTruffleException ate) { ex = ate; }
  else if (throwable instanceof ControlFlowException cfe) { throw cfe; }
  else {
      CompilerDirectives.transferToInterpreterAndInvalidate();
      throw sneakyThrow(root.interceptInternalException(throwable, localFrame, this, (int) bci));
  }
  ```

  So one `exit` deoptimizes and **invalidates the compiled code of every PHP function on the call stack**. Each is
  hot, so each is compiled again.
- **Graal stops that after fifteen recompilations of a target.** The next compilation fails for good:
  `SourceStackTraceBailoutException: Deopt taken too many times. Deopt Node: … This could indicate a deopt cycle,
  which typically hints at a bug in the language implementation or Truffle.` No compilation of that target is
  attempted again in the engine.

**Measured** (`jvm-fcgi.trace`: the server after 720 s of warm-up at eight users, then `native.sh k8`, whose K8
requests end in the plugin's `exit` inside a `template_redirect` callback; t in UTC). The call stack at that `exit`
is the entry script's root (`ScriptRootNode`) → `index.php` → `wp-blog-header.php` → `template-loader.php` →
`do_action` → `WP_Hook::do_action` → `WP_Hook::apply_filters` → the plugin's closure. Those are the eight targets
that fail:

| target | before the first `exit` | after it: deoptimizations | compilations | compiler time | the compilation fails at |
|---|---|---|---|---|---|
| `do_action` | 3 compilations, 1 deoptimization | 16 (11 invalidate) | 14 | 6.4 s | 03:51:31.4 |
| `WP_Hook::do_action` | 2 compilations | 22 | 15 | 1.9 s | 03:51:34.6 |
| `WP_Hook::apply_filters` | 6 compilations, 13 deoptimizations (4 invalidate) | 15 (all invalidate) | 14 | 16.2 s | 03:51:35.5 |
| `template-loader.php` | 2 compilations (both tiers) | 16 | 15 | 10.5 s | 03:51:38.2 |
| `wp-blog-header.php` | 2 compilations (both tiers) | 16 | 15 | 3.7 s | 03:51:49.1 |
| `ScriptRootNode` (not a bytecode root: its compiled code has `index.php` inlined) | 2 compilations (both tiers) | 8 | 15 | 0.3 s | 03:51:49.5 |
| `index.php` | 2 compilations (both tiers) | 24 | 15 | 0.4 s | 03:51:50.8 |
| the K8 plugin's closure | never compiled | 16 | 16 | 12.9 s (160 KB of code) | 03:51:57.7 |

- The first deoptimization of the eight is at 03:51:11.3, the first K8 request. The last failure is 46 s later,
  at the end of the first page's hundred K8 requests: a recompilation waits in the compile queue, so the fifteen
  take 50–100 requests, not fifteen.
- 52.3 s of compiler time go into the eight targets, 48.3 s of it in the first 41 s, beside 64.3 s for every
  other target: the cycle is 43% of the compiler's work while it lasts.
- After its failure no target of the eight has another compile event in the remaining four minutes of the run.
  **The hook dispatch (`do_action`, `WP_Hook::do_action`, `WP_Hook::apply_filters`) and the main scripts run in the
  interpreter from then on,** wherever they are called as targets of their own. A copy inlined into another
  function's compiled code is a part of that function's compilation and stays: in the server's profile
  `WP_Hook::apply_filters` is in the interpreter in 60% of its samples, not in all.
- The run's other failures are two of "code is too large" (`WP_Query::get_posts`, `wp-admin/menu.php`:
  [094](094-l0-red-baseline.md)). In all: 8322 compilations, 569 deoptimizations, 53 invalidations, 10 failures.
- One context that never exits (the K8 loop from the command line) deoptimizes 179 times in 8000 renders and has
  no such cycle.

**The same run again and the run without `exit`** (one server run each, 720 s of warm-up, the trace on;
`jvm-fcgi.exit2` repeats the run above, `jvm-fcgi.noexit` adds `&k8noexit=1` to its K8 requests: the plugin's
callback returns, WordPress renders the page once more and the request ends at the script's end;
`tests/gate2/k8_cycle.py` and `k8_firstblock.py` on each run's files):

| | `jvm-fcgi.trace` (`exit`) | `jvm-fcgi.exit2` (`exit`) | `jvm-fcgi.noexit` |
|---|---|---|---|
| compilations failed with "Deopt taken too many times" | 8 | 8, the same eight targets | **0** |
| those failures, seconds after the first K8 request | 21–48 | 21–45 | none |
| failed compilations in all | 10 | 10 | 2 ("code is too large") |
| deoptimizations before / after the first K8 request | 147 / 422 | 392 / 427 | 132 / 325 |
| the K8 plugin's closure | 16 compilations, 12.9 s, then failed | 16, 13.1 s, then failed | 2: one per tier, 1.5 s each |
| compiler time after the first K8 request: the eight targets / every other target | 52.6 s / 130.3 s | 54.6 s / 124.5 s | 0 / 149.4 s |
| the first K8 block (`/`, 100 requests): seconds, CPU seconds | 49.9, 185.0 | 48.6, 183.2 | 44.9, 143.2 |
| its hot render, median of the first ten requests → of the last ten | 55.8 → 25.3 ms | 62.7 → 25.2 ms | 57.9 → 22.6 ms |
| hot render, ms: `/`, `/?p=1`, `/?page_id=2`, `/?cat=1`, `/?s=hello` | 33.27, 30.38, 22.25, 24.98, 22.68 | 29.93, 29.69, 21.57, 24.24, 22.45 | 23.12, 27.10, 20.03, 22.32, 20.05 |
| hot render over php-fpm's, geometric mean of the five pages (of the four after `/`) | 2.50 (2.39) | 2.40 (2.33) | **2.12 (2.13)** |
| boot over php-fpm's / first render over php-fpm's | 2.60 / 2.10 | 2.49 / 2.01 | 2.31 / 1.80 |
| plain request over php-fpm's (no K8, no `exit` in either) | 2.23 | 2.28 | 2.20 |
| memory.peak | 2052 MB | 1917 MB | 1914 MB |

- **Without the `exit` there is no cycle:** no compilation fails with "Deopt taken too many times", the plugin's
  closure is compiled once per tier, and the eight targets keep their compiled code.
- **With it, the cycle repeats exactly:** the same eight targets, the same pace, 52.6 and 54.6 s of compiler time.
- **What it cost the measurement:** without the `exit` the hot render is 7–15% shorter on each of the four later
  pages than in any of the four runs of this build with it (the two above and two without the trace: geometric
  mean of the four pages 2.33–2.39× php-fpm's against 2.13×; the runs with `exit` differ from one another by at
  most 5% a page). The boot is 2.31× php-fpm's against 2.49–2.65×, the first render 1.80× against 2.01–2.11×.
  The four runs' "2.40–2.51× php-fpm's hot render" is 2.12× without the `exit`.
- **The first block's fall is not the cycle alone.** The K8 path is new code to a server warmed by plain
  requests, so its first block compiles in both cases (143 CPU-seconds in 45 s without `exit`, 183–185 in 49–50
  with). The cycle adds the 40 CPU-seconds and leaves the block's end 12% higher (25.2–25.3 ms against 22.6).
- One run without `exit`; php-fpm's times are the run of two hours earlier (`k8_report.py`'s medians, not first
  deciles: [101](101-machine-two-speeds.md)).
- **A first attempt at this comparison was void, and looked like a refutation.** `native.sh k8` does not refresh
  the site volume the servers read their files from, the volume held the plugin without the `k8noexit` switch, so
  the "no-exit" run exited as before and showed the same eight failures. It was caught by the closure's line
  number in the trace (the old file's line 53, not the new file's 56). That run is `jvm-fcgi.exit2` above;
  `native.sh k8` now stops when the volume's plugin is not the checkout's.

**Why it matters:**

1. **A real site ends requests in `exit` all the time,** under the same frames: `redirect_canonical` and every
   other `wp_redirect(); exit;` on `template_redirect`, `template-loader.php`'s own `exit` for a HEAD request,
   `wp_die()` and `wp_send_json()` (every admin-ajax action), the REST API (`rest_api_loaded`: `die()` under
   `parse_request`), WooCommerce's `wc-ajax` endpoints (`WC_AJAX::do_wc_ajax`: `wp_die()` on `template_redirect`),
   `wp-login.php`, `wp-comments-post.php`. Each such request invalidates the compiled code of the hook dispatch and
   of everything else on its stack; after fifteen rounds those functions are interpreted until the JVM restarts.
   Every later request pays, the ones that never exit too.
2. **It is a candidate for what the WooCommerce load test saw** ("compile never settles",
   [woocommerce.md](../wiki/woocommerce.md)): a checkout is a `wc-ajax` request. Not measured.
3. **It bent tonight's served measurements.** Every K8 request of `native.sh k8` ended in `exit`: the four later
   pages, plain requests and K8 blocks alike, were measured with the hook dispatch interpreted, and the hot render
   read 2.40–2.51× php-fpm's where it is 2.12× (the table above). In the server's profile
   `WP_Hook::apply_filters` ran in the interpreter in 60% of its samples for the same reason.
4. Until fixed, a benchmark request must not end in `exit`: `?k8noexit=1` of `tests/gate2/k8-plugin.php`
   (`K8_EXTRA="&k8noexit=1"` for `native.sh k8`).

**Fix (sketch, not tried):** `exit` should leave the frames as a fatal error's bailout does. `PhpBailout` is an
`AbstractTruffleException`: the generated interpreter passes it through `interceptTruffleException` with no
invalidation; no `catch` takes it (`CaughtThrowable`) and no `finally` runs for it (`FinallyRuns`); each frame's
variables are released by the exceptional epilog (`Unwind`, which invalidates a function once, the first time an
exception leaves it, and never again); `ScriptRootNode` catches it at the script's root, and `endScript` already
calls `closeExited` from there for a fatal error's status 255.

- `PhpContext.exit` keeps doing its work where the `exit` is (the order of shutdown functions, destructors and
  output is observable and stays), then throws a bailout that carries the status; the script's root calls
  `closeExited(null, status)` once the frames are gone.
- What the check suite has to hold through it: `exit` inside a shutdown function (the remaining ones do not run),
  inside a destructor, an output buffer's callback, a generator, a fiber (`PhpFiber` carries a bailout across its
  thread already), `finally` blocks that must not run, the exit status of the CLI launchers
  (`PolyglotException.isExit()` in `PhpMain`, `BatchMain`, `CgiRequests`), and every `catch (PhpBailout …)` that
  now reads it as "a fatal error, already reported" (`ErrorFunctions`, `PhpContext.guarded`).
- Then the trace of a served run with K8 requests that exit must show no "Deopt taken too many times", and
  `tests/gate2/native.sh k8` with and without `k8noexit=1` must agree.

**Open:**

- The fix.
- Whether other exceptions reach the same branch of `resolveThrowable` in a hot path: any Java exception that is
  not an `AbstractTruffleException` and crosses a PHP frame invalidates it (a `StackOverflowError` that becomes
  PHP's fatal error, an internal error). `grep` the trace of a WooCommerce run for "Deopt taken too many times".
