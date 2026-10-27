# TLA+: the lifecycle of curl handles (2026-10-27)

ext/curl is a port of php-src 8.5.12's ext/curl over libcurl, bound by FFM: `src/main/java/pl/codeloom/php/builtins/CurlFunctions.java`, with `CurlNative.java` and `CurlConstants.java`. php-src frees a handle when its refcount drops to zero. Truffle PHP has no refcounts for objects, so it frees a handle's native part in two steps:
1. A `java.lang.ref.Cleaner` notices that the PHP object is unreachable and queues the native part on its own thread.
2. The context frees it on the context's thread, at its next curl call (`State.drain`) or when the context closes (`State.close`).

This page checks that design with a TLA+ model and TLC, and compares it with php-src. The model is `models/curl/CurlLifecycle.tla`; the configurations are beside it.

**Result:**
- The safety properties hold in every configuration that matches how the runtime runs. TLC checked 6.8 million distinct states for one context and 4.4 million for two contexts on two threads.
- The code relies on one fact for that: a built-in call's Truffle frame keeps its arguments reachable. Without it, TLC finds a multi handle freed under `curl_multi_add_handle`.
- Two observable differences from php-src stay open. Both are destruction timing: php-src writes `CURLOPT_COOKIEJAR`, and flushes a `CURLOPT_FILE` stream, when the last reference goes. Truffle PHP does it later, and it writes `CURLOPT_FILE` data without a C `FILE*` buffer.
- One real bug is fixed: a thread's persistent shares weren't cleaned up when the thread ended (php-src does that in `GSHUTDOWN`).
- One assumption is latent: a persistent share is shared by every context on a thread, so a context must not move to another thread. The launchers never move one.

## What the code does

| Part | In the code | In the model |
|---|---|---|
| CurlHandle | The PHP object holds an `Easy`. Its native part (`EasyNative`: the `CURL*`, the error buffer's arena, the `ToFree` its copies share) is in `State.live`. The Cleaner watches the `Easy`. | `kind = "easy"`, `nat`, `inLive`, `group` (the `ToFree`), `toFreeDone` |
| CurlMultiHandle | The PHP object holds a `Multi`. The `Multi` is itself the native part in `State.live`. Its `easyh` holds the CurlHandle objects added. The Cleaner watches the **PHP object**. | `kind = "multi"`, `easyh`, `lmulti` (libcurl's `data->multi`) |
| CurlShareHandle | The PHP object holds a `Share`; a `ShareNative` is in `State.live`. The Cleaner watches the **PHP object**. `Easy.share` holds the `Share` for `CURLOPT_SHARE`. Freeing a share that easy handles still use is refused by libcurl (`CURLSHE_IN_USE`); the share goes to `State.deferredShares` and is retried whenever an easy handle is freed, and at the context's end. | `kind = "share"`, `shareOf` (`Easy.share`), `attached` (libcurl's `data->share`), `deferred` |
| CurlSharePersistentHandle | `PERSISTENT_SHARES`, a `ThreadLocal`: one native share per thread and option set, not tracked by any context. php-src's ZTS keeps them per thread too. They are cleaned up when the thread ends (finding G1). | `kind = "pshare"`, `pnat`: one native per thread; `EndThread`, `ended` |
| `curl_copy_handle` | `curl_easy_duphandle`. The copy shares the `ToFree`; its last holder frees it. | `Copy`; `DUP_SHARE`: whether libcurl attaches the copy to the source's share |
| Callbacks | FFM upcalls look the `Easy` up in `EASIES` (a `WeakReference`) by the id passed as userdata. `callCallback` holds the `Easy` while PHP code runs. | `EnterCb`/`LeaveCb`. A callback whose `Easy` was collected is skipped (`easyOrNull`); the model counts that as a violation, since php-src would run it. |
| Error handlers | `_php_curl_verify_handlers` warns about a closed `CURLOPT_FILE`/`INFILE`/`WRITEHEADER`/`STDERR` stream. The warning runs the user's error handler before libcurl is called. | `MultiAdd`, `MultiClose`: a `"warn"` window, then the native calls |
| Freeing | `State.drain` runs at the start of most curl functions, from any PHP code: between calls, in a callback, in an error handler. It frees what the Cleaner queued. `free` runs only for handles still in `State.live`. | `Clean` (the Cleaner's thread, any time after the watched object is unreachable), `Drain` (one handle per step) |
| Context end | `BuiltinState.close` → `State.close`, from `finalizeContext`, after the script and its shutdown functions. It clears the queue, frees every live handle (easy, then multi, then share), retries the deferred shares, and drops them. | `Close` |
| Threads | `launcher/HttpMain` builds, runs and closes each request's context on one pool thread (`--threads=n`). `tests/shared/SharedContexts.java` does the same. | `thread`; contexts on distinct threads; `MIGRATE` lets a context move between calls |

Global state shared by every context: `EASIES`, `MULTIS`, `MIME_SOURCES` (by ids from one `AtomicLong`, unique across contexts), the Cleaner and its thread, and `PERSISTENT_SHARES` (per thread). Each context has its own `State`, which the Cleaner reaches only through `State.released`, a concurrent queue.

**php-src's side** (`ext/curl/interface.c`, `multi.c`, `share.c`): a handle is freed when its refcount drops to zero. Its holders are:
- the variables that hold it;
- the call frames of the functions it was passed to;
- a multi's `easyh` (`Z_ADDREF` in `curl_multi_add_handle`);
- an easy handle's `ch->share` (`GC_ADDREF` in `CURLOPT_SHARE`; `curl_copy_handle` doesn't copy it).

`curl_free_obj` calls `curl_easy_cleanup`, which writes `CURLOPT_COOKIEJAR`, and drops the handle's references to its streams and share. `curl_multi_free_obj` calls `curl_multi_cleanup` and then releases `easyh`. `curl_share_free_obj` calls `curl_share_cleanup`. Persistent shares are freed in `GSHUTDOWN`. In the model, `PhpAlive` is that refcount.

## What the model abstracts away

- **PHP references** are a flag per handle, `rooted`: some variable, array element or property holds the object. Dropping it (`Drop`) means the last one went.
- **Calls** are one level deep: a callback or error handler can create and drop handles, copy them, set `CURLOPT_SHARE`, and call curl functions (which drain). It can't start another transfer. A call's PHP arguments, and the Java internals it uses after libcurl returns, are listed per function (`frame`).
- **libcurl** is reduced to:
  - which easy handle is in which multi, and which share it uses;
  - `curl_share_cleanup` refusing a share in use;
  - `curl_multi_cleanup` refusing to run from a callback of the same multi (`CURLM_RECURSIVE_API_CALL`). The code then drops its pointer anyway: the model calls that "leaked".
- **Bounds:** `K` handle slots per context, `MaxCb` callbacks per call.
- **Not modelled:**
  - server push (`CURLMOPT_PUSHFUNCTION`);
  - `CURLOPT_STDERR`'s `fopencookie` `FILE*`;
  - `curl_reset`;
  - nested transfers (`curl_exec` from a callback).

## Properties

| Name | What it says |
|---|---|
| `NoBad` | No native handle is used after it was freed: by a call (`curl_exec`, `curl_multi_*`, `curl_setopt`, `curl_copy_handle`), by libcurl through a multi or a share, or by a transfer still running when a callback returns. No `curl_easy_cleanup` runs on an easy handle that libcurl is running. No callback is skipped because its `Easy` was collected. |
| `NoDoubleFree` | A freed handle isn't in `State.live`: the next `free` of it is a no-op. |
| `NoLeakAfterClose` | Once a context has closed, all its handles are freed (not leaked), and so are all its `ToFree`s. |
| `Isolation` | A multi's easies, an easy handle's share, and libcurl's links never cross contexts. Two threads never run libcurl on the same native handle at once (a persistent share has no lock callbacks). |
| `NoLeakAfterThreadEnd` | Once a thread has ended (after every context on it closed), its persistent shares are cleaned up, as php-src's `GSHUTDOWN` does. |
| `NoEarlyFree` | Nothing is freed while php-src would still hold it. |
| `DestroyedWhenPhpDoes` | Whenever PHP code runs after php-src would have destroyed a handle, the handle has been freed. Only `Timing.cfg` checks this; it is expected to fail. |

## What TLC checked

TLC 2.19, `-workers 4 -Xmx2g`, breadth-first.

| Configuration | Contexts × threads | `K` | `MaxCb` | Other | Result | Distinct states | Depth | Time |
|---|---|---:|---:|---|---|---:|---:|---|
| `Safety.cfg` | 1 × 1 | 3 | 2 | | holds | 87,735 | 20 | 1 s |
| `Wide.cfg` | 1 × 1 | 4 | 2 | `DUP_SHARE` | holds | 6,834,667 | 28 | 3 min 35 s |
| `Threads.cfg` | 2 × 2 | 2 | 1 | | holds | 4,363,058 | 23 | 2 min 28 s |
| `Threads3.cfg` | 2 × 2 | 3 | 1 | | no error found; stopped (bounded) | 35,554,841 | 11+ | 15 min |
| `NoFrameArgs.cfg` | 1 × 1 | 3 | 2 | arguments not held | fails (finding M3) | | 7 | 1 s |
| `Timing.cfg` | 1 × 1 | 2 | 1 | `DestroyedWhenPhpDoes` | fails (findings T1, T2) | | 4 | 0 s |
| `Migrate.cfg` | 2 × 3 | 2 | 1 | `MIGRATE` | fails (finding P1) | | 11 | 3 min 23 s |
| `ThreadEnd.cfg` | 1 × 1 | 3 | 2 | no `GSHUTDOWN` | fails (finding G1) | | 5 | 0 s |

`Threads3.cfg` doesn't finish: after 15 minutes, breadth-first through depth 11, it had 35.6 million distinct states and 23.8 million more on the queue. So it is a bounded check of the shorter behaviours. All configurations but `ThreadEnd.cfg` model the code with G1's fix (`GSHUTDOWN = TRUE`). A failing configuration stops at its first counterexample; its depth is the number of states in the trace.

The model was widened step by step: one context with 3 slots and one callback per call, then two callbacks, then 4 slots with `DUP_SHARE`, then two contexts. Every widening that matches the runtime held.

## Findings

Each counterexample is listed with its verdict: a model error, a real bug fixed, or a real bug left open.

### M1: a share switched during a transfer (model error)

**Trace:** an easy handle uses share S1 in `curl_exec`. Its callback sets `CURLOPT_SHARE` to S2 and drops S1's last variable; a curl call drains S1. The transfer then used S1 after it was freed.

**Why the model was wrong:** the first model fixed a transfer's shares when the transfer started. libcurl reads `data->share` when it needs it, and the switch already detached S1. php-src does the same thing: `OBJ_RELEASE` frees S1 inside the callback. The model now derives the shares in use from `attached` at each check.

### M2: `curl_pause` callbacks for a transfer that isn't running (model error)

**Trace:** an easy handle that runs no transfer gets `curl_pause`, and two callbacks. The first drops the handle; the second finds its `Easy` collected, because `curlPause` doesn't use `ch` after libcurl returns.

**Why the model was wrong:** only a running transfer has held-back data for `curl_pause` to deliver. Such a transfer is in a multi, whose `easyh` holds the handle, or in `curl_exec`, whose frame holds it. The model now gives `Pause` callbacks only for an easy handle in a multi.

### M3: a multi freed under a call that has it (model error, and what the code relies on)

**Trace** (`NoFrameArgs.cfg`, 7 states):
1. `curl_multi_init`, `curl_init`.
2. `curl_multi_add_handle($GLOBALS['mh'], $ch)` warns that `CURLOPT_FILE`'s stream was closed.
3. The error handler runs `unset($GLOBALS['mh'])`; the Cleaner queues the multi.
4. The handler calls `curl_init()`, which drains: `curl_multi_cleanup` runs, and `Multi.multi` becomes null.
5. `curl_multi_add_handle` goes on to `curl_multi_add_handle(NULL, ...)`.

The same thing can happen in `curl_multi_close`'s warnings. In `curl_multi_exec`, a write callback that drops `$mh` makes the drain call `curl_multi_cleanup` from inside the multi. libcurl refuses that, and the native multi leaks. With `curl_pause`, a later callback would be skipped.

The cause would be that the Cleaner watches the CurlMultiHandle *object*, while `curlMultiAddHandle`, `curlMultiExec` and `curlMultiClose` keep only the `Multi`. The Java code alone doesn't keep the object reachable: `a`, the argument array, is dead after the arguments are parsed.

**Why it can't happen:**
- A built-in runs as a call target whose Truffle frame holds the arguments array. `debug_backtrace()` reads it, and Truffle must be able to materialize the frames of active calls for that.
- `models/curl/repro/frame-args.php` shows it: an error handler inside `curl_multi_add_handle` runs `unset($GLOBALS['mh'])`. `debug_backtrace()` called after that still shows the `CurlMultiHandle` among the call's `args`, on both runtimes.
- 3,000 rounds of the trace in one context, under a full JIT warm-up, never failed.

So the PHP objects passed to a curl function stay reachable until it returns, as php-src's call frame holds them. The model has this as `FRAME_ARGS = TRUE`; `NoFrameArgs.cfg` keeps the trace to record what the code relies on.

**Test:** `tests/shared/curl-lifecycle.php`, which `tests/shared/run.sh` runs in 40 contexts on 4 threads with `-Xmx512m`, so that the GC and the Cleaner run often. Each of 100 rounds:
- builds a multi of three easy handles (one a copy) on a shared share, whose write callbacks drop the easies' variables, allocate, and call `curl_init`;
- drops the share's variable while the easies use it;
- runs this trace's `curl_multi_add_handle` with an error handler that drops `$GLOBALS['mh']`;
- runs a `curl_exec` whose callback drops the handle's only variable;
- uses a persistent share.

Every transfer must deliver all bytes, and every add must return 0; php 8.5 prints the same line. In a run with a temporary counter, 100,717 native handles were freed by drains, 92,547 of them inside callbacks. Nothing failed.

### T1: `CURLOPT_COOKIEJAR` written late (real, open)

**Trace** (`Timing.cfg`, 4 states): `curl_init`, then `unset($ch)`. php-src has destroyed the handle at the `unset`; Truffle PHP hasn't freed it yet. It frees it after the GC finds it unreachable, at the context's next curl call or at its end.

**Observable** (`models/curl/repro/cookiejar-at-unset.php`): set `CURLOPT_COOKIEJAR` and a cookie through `CURLOPT_COOKIELIST`, then `unset($ch)`.
- On PHP 8.5, the jar exists right after the `unset`.
- On Truffle PHP, it doesn't. It appears at some later curl call, or when the request ends.

What else `curl_free_obj` does moves the same way:
- releasing the handle's `CURLOPT_FILE`/`INFILE`/`WRITEHEADER` streams (a stream whose last holder is the handle closes, and releases its `flock`);
- releasing its share;
- for a multi, `curl_multi_cleanup` and the release of its easy handles.

**Why it stays open:** an exact fix means freeing at the last reference, which needs refcounts on objects. The runtime has none; `__destruct` isn't run either, for the same reason. A partial fix, such as freeing at `unset` when the handle "looks" unshared, would itself be a deviation. This belongs with deterministic destruction for all objects. WordPress core doesn't see it: it sets none of these options and no share. The repro script is outside `tests/smoke` so that the suite stays green; `models/curl/repro/run.sh` shows the difference.

### T2: `CURLOPT_FILE` goes through a C `FILE*` in php-src (real, open)

This was found while checking what `curl_free_obj` flushes.

**What php-src does:** `curl_setopt` with `CURLOPT_FILE`, `CURLOPT_WRITEHEADER` or `CURLOPT_INFILE` casts the stream to stdio (`php_stream_cast(..., PHP_STREAM_AS_STDIO)`). For a plain file, `php_stdiop_cast` makes a `FILE*` with `fdopen` and sets `data->fd = -1`. From then on, all of the stream's I/O, PHP's own included, goes through that buffered `FILE*`.
- libcurl's data reaches the file when glibc's buffer fills, at `curl_exec`'s `fflush`, at `fflush($fp)`, or when the stream closes. `curl_multi_exec` doesn't flush.
- The stream's own position (`ftell`) doesn't count libcurl's bytes.

**What Truffle PHP does:** it writes each chunk through the PHP stream at once.

**Observable:**
- `models/curl/repro/file-at-unset.php`: a `curl_multi` transfer to `$fp`. php-src shows an empty file until `unset($ch)` frees the handle, which holds the stream's last reference. Truffle PHP has the whole file at once.
- `models/curl/repro/file-position.php`: after a multi transfer of 5 bytes and `fwrite($fp, "TRAILER\n")`, php-src's `ftell($fp)` is 8 and Truffle PHP's is 13. The file content is the same.

**Why it stays open:** a fix is a stdio mode in the stream layer. Once a plain-file stream is cast, its I/O would go through an emulated glibc `FILE*`, which needs:
- the buffer size of `st_blksize`, 4096 on the reference image;
- `_IO_file_xsputn`'s flushing rules, and read-ahead for `INFILE`;
- a stream position that only PHP's own calls move.

Its flushes at the stream's destruction would also need T1. That is the stream layer's work, not the handles' lifecycle. WordPress core isn't affected: it never sets `CURLOPT_FILE`, `CURLOPT_INFILE`, `CURLOPT_WRITEHEADER` or `CURLOPT_COOKIEJAR`, and Requests' cURL transport writes a `filename` download itself, with `fwrite` from its `CURLOPT_WRITEFUNCTION`.

### P1: a persistent share used from two threads (latent, holds under the launchers)

**Trace** (`Migrate.cfg`, 11 states):
1. Context c1 runs on thread t2: `curl_share_init_persistent` returns t2's share, and c1 sets it on easy E1.
2. c1 moves to thread t1, and c2 moves to t2.
3. c2 gets t2's share too, and sets it on E2.
4. Both run transfers at once: two threads use one libcurl share without lock callbacks.

**Verdict:** this can't happen in this runtime. HttpMain builds, runs and closes each request's context in one task on one pool thread, and the tests' harnesses do too. php-src's ZTS has the same per-thread map, and a request stays on its thread. An embedder that enters a context from several threads, which Truffle allows one thread at a time, would break this assumption. Such an embedder would need the persistent shares keyed by context, or lock callbacks on them.

### G1: persistent shares outlive their thread (real, fixed)

**Trace** (`ThreadEnd.cfg`, `GSHUTDOWN = FALSE`, 5 states):
1. `curl_share_init_persistent` on thread t1.
2. The context closes.
3. t1 ends. Its persistent share is never cleaned up (`NoLeakAfterThreadEnd`).

php-src destroys `CURL_G(persistent_curlsh)` in `GSHUTDOWN`, when the thread ends, and that runs `curl_share_cleanup` on each share. `PERSISTENT_SHARES` was a `ThreadLocal` map of native pointers: the thread's end dropped the map, and the shares, with the connections they kept, stayed open until the process ended. HttpMain's pool threads live as long as the server, but an embedder whose threads come and go leaked a share, and its connections, per thread and option set.

**Test:** `tests/shared/PersistentShares.java`, run by `tests/shared/run.sh`.
1. A context on its own thread fetches from a local keep-alive HTTP server through `curl_share_init_persistent([CURL_LOCK_DATA_CONNECT])`.
2. The context closes, and the thread ends.
3. The server must see the connection closed within 10 seconds, with `System.gc()` run between checks.

Before the fix the connection stayed open.

**Fix:** the per-thread value is a `PersistentShares` object that the Cleaner watches. The JDK drops a thread's thread-local values when it ends, and the Cleaner's action then runs `curl_share_cleanup` on the shares. By then every context that ran on the thread has closed, so no easy handle uses them. If one did (P1), libcurl would refuse the cleanup, not free a share in use. The model has this as `GSHUTDOWN = TRUE` and `EndThread`.

## How to rerun

```
models/curl/tlc.sh Safety.cfg          # also Wide.cfg, Threads.cfg: each prints "No error has been found"
timeout 900 models/curl/tlc.sh Threads3.cfg   # doesn't finish in hours: a bounded search
models/curl/tlc.sh NoFrameArgs.cfg     # M3's trace
models/curl/tlc.sh Timing.cfg          # T1's trace
models/curl/tlc.sh Migrate.cfg         # P1's trace
models/curl/tlc.sh ThreadEnd.cfg       # G1's trace (the code before its fix)
tests/shared/run.sh                    # the contexts test (after mvn package -DskipTests)
models/curl/repro/run.sh               # M3's probe (same), T1 and T2 (differ while open) on PHP 8.5 and Truffle PHP
```

`tlc.sh` needs `~/tools/tla2tools.jar`, or `TLA2TOOLS=...`, and a JDK. It runs TLC with 4 workers and a 2 GB heap; TLC's state files go to a temporary directory.

When `CurlFunctions` changes, update the table in "What the code does" and the matching action in the model, then rerun the configurations. In particular, watch for these changes:
- what a function's Java code holds after libcurl returns;
- which functions drain;
- what `free` does;
- the order of `State.close`.
