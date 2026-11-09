# Concurrency review: Fiber implementation and HttpMain (opus)

Branch `fiber` at e9cdaab. The harness refused file writes, so this report was returned as a message and saved here
by the main session. The reproductions are in [`repro/`](repro/):
- `Harness.java` uses one shared engine with a context per script, closed the way HttpMain closes it.
- `Cancel.java` calls `close(true)` from another thread.
- `Heap.java` runs N requests and prints the live heap after a GC.
- The PHP scripts.

## Confirmed

### 1. High: a fiber suspended inside a native frame pins its carrier; once all carriers are pinned, every later fiber in the JVM hangs

`PhpFiber.java:135` starts each fiber as a virtual thread. `FiberGate.java:42-51` parks it. Curl callbacks
(CurlFunctions upcalls, `ffi/Upcall.java:46`) run PHP code below `curl_exec`'s native frame.

Scenario:
1. A fiber's `CURLOPT_WRITEFUNCTION` calls `Fiber::suspend()`. PHP allows this, and streaming HTTP clients do it.
2. The virtual thread parks with a native frame on its stack. JDK 25 can't unmount it, so the carrier stays blocked
   until the fiber is resumed.
3. Carriers are shared by every request in the JVM; there are as many as cores by default. Once all of them are
   pinned this way, a new fiber's thread never gets one.
4. The code that started the fiber waits forever in `toCaller.await()`. HttpMain has no timeout, so the request
   never ends.

Reproduction: `pin.php N` with `-Djdk.virtualThreadScheduler.parallelism=2 -Djdk.virtualThreadScheduler.maxPoolSize=2`.
- With N=2 it prints `done`.
- With N=3 it hangs until killed at 30 s.
- Control: `nopin.php`, 50 fibers that suspend outside native frames, completes on the same 2 carriers.

The same mechanism also causes starvation without a deadlock. A fiber blocked in `curl_exec`, `curl_multi_select`,
`proc_close` (`waitpid`) or a long PCRE match holds a carrier for the whole call. The JDK doesn't add a carrier to
make up for it.

Fix: run fibers on platform Truffle threads, with `stackSize` taken from `fiber.stack_size`. That also makes the
setting real (php-src's default is 2 MB). Measure the start cost. Raising `maxPoolSize` doesn't help, because the JDK
never adds carriers for threads pinned by native frames.

### 2. High: `destroyFibers` skips a fiber when a fiber started during the loop prunes the list

`PhpContext.java:972-974` (`fibers.removeIf(ended)` in `fiberStarted`) runs while `PhpContext.java:986-993` walks
`fibers` by index.

Scenario:
1. Fiber k's finally block, run by `destroy()`, starts a fiber.
2. The list is at `fibersPruneAt`, so the prune removes fiber k-1, which has ended. The indices shift, and the loop
   never visits fiber k+1.
3. Fiber k+1 stays SUSPENDED. `join` skips it because it isn't DEAD, and `fibers.clear()` forgets it.
4. `finalizeContext`'s `abandonFibers` then finds an empty list.

Reproduction: `prune.php` through `Harness` (127 suspended fibers; each finally block starts a fiber). On every run:
- `fin 2` is missing from the output: a finally block didn't run, so the PHP output is wrong.
- The close fails with Truffle's internal error "The language did not complete all polyglot threads but should
  have: [VirtualThread…/waiting]".
- `engine.close()` fails with the same error, which breaks HttpMain's `closeAfter`.
- The parked thread keeps the whole request heap alive.

`prune-exit.php` adds `exit(1)` at the end. The fiber left behind makes `closeExited` run while another thread is
active, which is the Truffle cleanup-thread path the join fix avoids. It didn't deadlock in 2 runs.

Reasoned, not reproduced: `abandonFibers` (`PhpContext.java:1006`) iterates with a for-each. An abandoned fiber's
`end()` (`PhpFiber.java:336-339`) releases pending values, which can run a `__destruct` that starts a fiber. That
throws `ConcurrentModificationException` out of `finalizeContext` before `builtinState().close()`
(`PhpLanguage.java:361-366`). Then these leak:
- streams;
- flock locks, which are JVM-wide;
- curl handles;
- PCRE native memory;
- SQL connections.

Fix:
- Don't prune while destroying: use a flag, or compact the list only outside `destroyFibers`.
- Keep the list until disposal, and have `abandonFibers` walk it by index and abandon every fiber that isn't DEAD.
- Move `abandonFibers` inside the `try`, so `builtinState().close()` always runs.

### 3. Medium: eval'd code grows language-level state on every request (shared engine)

`Heap.java`, 40 sequential contexts on one engine, live heap after GC:

| Script | Live heap (start to 40 requests) | Growth |
|---|---|---|
| `evalclass2.php` (500 eval'd class declarations, same code each request) | 17 to 49 MB | about 1.1 MB per request, linear |
| `evallit.php` (unique literals in eval) | 9 to 14 MB | slow |
| `evalplain.php` (plain evals) | 7 MB | flat |

The growth is confirmed. The holder is suspected, not proven: most likely `PhpLanguage.classKeys`
(`PhpLanguage.java:134`). Each eval is translated again (`SourceFiles.java:71-102`), and `ClassKey.Id` holds the new
`ClassTemplate` (`PhpClass.java:1134`), which keeps the eval'd AST alive. The literal growth would come from
`literals`/`literalsByKey` (`PhpLanguage.java:136-142`). A file rewritten while the server runs keeps its old templates
the same way. php-fpm frees all of this at the request's end.

Fix: don't intern class keys or literals from translations that aren't cached, or hold them weakly.

## Suspected (reasoned from the code)

### 4. Medium: HttpMain can be starved; there is no execution time limit

`max_execution_time` is stored but not enforced (`Ini.java:315`). HttpMain uses `newFixedThreadPool`, with as many
threads as cores and an unbounded queue (`HttpMain.java:133`).

A few runaway requests take the server down for good:
- an infinite loop;
- the hang from finding 1;
- a blocking `flock` on a lock leaked by a request that ended abnormally.

Queued requests keep their sockets open meanwhile. PHP's web SAPIs instead raise the fatal "Maximum execution time
of 30 seconds exceeded", and shutdown functions run.

Fix:
- Use a timer that submits a Truffle `ThreadLocalAction`. It raises that fatal error in the request's thread, or in
  the running fiber's thread.
- Bound the queue and answer 503.

### 5. Low-medium: PCRE caches

`PcreFunctions.java:92,428-447`.
- The shared cache keeps the first 4096 patterns forever, one-off patterns included.
- After that, every new pattern is compiled with JIT into the context's `regexes`, which has no bound until the
  request ends.
- A request building many patterns (`preg_quote` in a loop) grows native memory without limit.
- Hot patterns seen after the shared cache filled are compiled again on every request.

php-src keeps 4096 entries and evicts. Fix: make both LRU, free on eviction only when `matchDataInUse` is false, and
reference-count shared code.

### 6. Low: `FileLocks.lockOs` blocks in `FileChannel.lock` while holding the `Lock` monitor

`FileFunctions.java:634,712-724`. When another process holds the OS lock, the holder blocks without a safepoint, and
every JVM thread that wants that file blocks entering the monitor. Neither can be cancelled; BatchMain reports them as
`hung`. Fix: poll with `tryLock` inside `setBlockedThreadInterruptible`, outside the monitor.

### 7. Low: `proc_open` children are reaped only by `proc_close`

`ExecFunctions.java:706`. Nothing reaps them at the request's end, so zombies pile up in a long-running server.
php-src's destructor reaps children that have already exited (WNOHANG). Fix: track processes in `BuiltinState` and
reap them with WNOHANG in `close()`.

### 8. Low: hygiene

- `Census` uses static `HashMap`/`IdentityHashMap` and plain counters (`Census.java:40,204,218,411,414,429`).
  `-Dphp.rc.verify` or `-Dphp.rc.audit` under a multi-threaded HttpMain corrupts them.
- `FiberGate.await` leaves `waiter` set when the park throws on cancel or exit. Harmless today.
- `PhpFiber.start` calls `enter()` before `thread.start()`. If start throws, `currentFiber` and the pending lists
  stay swapped.

## Checked and found sound

- **FiberGate handshake.** `open` and `waiter` are volatile, and the Dekker order rules out a lost wake-up. A stale
  `unpark` is absorbed by the `while (!open)` loop. `open` is reset before the other side can open the gate again.
  The values handed over (value, error, status, `currentFiber`, the pending lists) are made visible by the volatile
  write and read pair. Truffle's `setBlocked` loop retries after interrupts from outside.
- **Cancel with fibers** (`Cancel.java`): `close(true)` returns in 35 ms on a busy loop with a suspended fiber, 3 ms
  on `sleep(30)`, and 2 ms on `sleep(30)` inside a fiber. A cancelled parked fiber unwinds to DEAD before
  finalization.
- **Stack overflow or Java heap exhaustion with a suspended fiber** (`soe.php`, `oom.php`): Truffle cancels, the
  close succeeds, and the next context runs.
- **The fixes the brief lists:**
  - Joining at the request's end is sound for every fiber that reaches DEAD; finding 2 is the gap.
  - The admission counter is incremented before the fiber's thread enters, admits Truffle's cleanup threads, and is
    decremented once per disposed context. Because it is global, it also admits threads into contexts without
    fibers, but only an embedder could do that.
  - BatchMain's CAS gives exactly one close per context.
- **Thread-local sharing** (Phases, the ByteBuilder pool, curl's persistent shares) is safe because one thread of a
  context runs at a time. The ByteBuilder pool is a free list, so a builder borrowed across a suspend isn't handed
  out twice. PCRE match data is guarded by `matchDataInUse`, and the JIT stack is used by one thread at a time. Curl's
  Cleaner passes handles through a `ConcurrentLinkedQueue`, and they are freed on the context's thread.
- **Language-level and static state:**
  - The translation cache (`FutureTask`) and `NameIds` (a concurrent map plus a lock) are safe.
  - These are bounded: `Str` names (65536), `ZONE_CACHE` (4096), Syslog idents (ini only).
  - These are filled once in static initialisers: Registry, TzTables, LANGINFO, SslSocketStream.
  - curl's `EASIES`, `MULTIS` and `MIME_SOURCES` are concurrent maps, and entries are removed when freed.

Not re-tested, since the brief lists them as known: `exit()` inside a fiber, and the engine-wide single-thread
assumption.
