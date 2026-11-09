# Concurrency review: fibers and the shared-engine runtime (fable)

Branch `fiber` at e9cdaab. Code read; reproductions in `target/review-fable/t*.php` (run with
`-Dpolyglot.php.Parser=java`), plus one `BatchMain` run with `--timeout=4` (`list.txt`, `out/`).

## Confirmed by reproduction (all behaved correctly)

| Script | Scenario | Result |
|---|---|---|
| t1 | `exit(3)` in main with a suspended fiber | fiber's `finally` runs, exit 3, 1 s |
| t2 | fatal error in main with a suspended fiber | fiber abandoned (no `finally`), exit 255 |
| t3 | fatal error inside a fiber, another fiber suspended | bailout reaches main's `finally`, exit 255, no hang |
| t4 | fiber B drops the last reference to suspended fiber A | A unwinds on B's thread, B continues, order matches PHP |
| t5 | `exit(3)` inside a fiber (the known open issue) | worked here: other fiber's `finally` ran, exit 3, no hang |
| t6 | 3000 fibers suspended then resumed, then 200 leaked suspended | correct sum, 2 s, clean end |
| t7 | nested fibers, resume from main while inner is suspended inside outer | `previous` restored correctly at each hop |
| t8/t9 | `BatchMain` cancel of an infinite loop in main / in a fiber, with a second fiber suspended | both `timeout` at 4.0 s, the next script runs, no deadlock |

So the three fixes in the brief hold in these runs: the request end joins its fibers' threads
(`PhpContext.destroyFibers` 984-998), the global admit counter is incremented before the thread is created and
decremented once per context in `disposeContext`, and `BatchMain`'s CAS keeps one closer.

I found no data race in the hand-over itself. Everything below is reasoned, not reproduced.

## Suspected defects, by severity

### 1. A fiber thread that fails to enter the context leaves its caller parked forever (hang)

`PhpFiber.start` 133-138: `enter()`, `thread.start()`, `awaitFiber()`. The fiber body `run()` is the only thing
that opens `toCaller`. Truffle runs the body inside `PolyglotLanguageContext.enterThread` (Truffle 25.4, lines
529-563): it throws `closedException("The Context is already closed.")` when `finalizingEmbedderThreads` is set or
the context is closing/invalid, and `PolyglotThreadTask.run` rethrows that on the new thread. `run()` is never
called, so:

1. PHP code running late in the request (an output handler in `endRequest` inside `finalizeContext`, a destructor
   reached from `abandonFibers`, or anything after a cancel has been initiated) calls `Fiber::start`.
2. The new thread dies in `enterThread`; its exception goes to the thread's uncaught handler.
3. The caller sits in `toCaller.await()` with `currentFiber` pointing at a fiber that never ran and the pending
   lists swapped.

Effect: a hang. `BatchMain` recovers by cancel; `HttpMain` has no cancel, so the pool thread is lost for good
(starvation after `--threads` such requests). The same shape applies if `thread.start()` itself throws: `enter()`
has already run, so the context is left with a wrong `currentFiber`.

Fix: before `thread.start()`, `thread.setUncaughtExceptionHandler((t, e) -> { error = e; threw = true;
status = DEAD; toCaller.open(); })`; do `enter()` only after `start()` succeeded, or undo it in a `catch`. Also
consider refusing `Fiber::start` once the context is shutting down (php-src's `zend_fiber_switch_block`, step 3).

### 2. `run()` swallows Truffle's cancel/exit throwables and runs destructors under them

`PhpFiber.run` 315-326 catches `Throwable`, so Truffle's `CancelExecution`/exit exceptions (`ThreadDeath`
subclasses) delivered to a parked fiber (via `setBlockedThreadInterruptible`) are stored in `error`, then `end()`
336 runs `Refcount.releasePending` (PHP destructors) on a thread Truffle is cancelling, and finally the caller
rethrows the fiber's `ThreadDeath` on its own thread through `sneaky` (296), ahead of its own safepoint action.

Effects: destructor output after a cancel/exit began; a control exception crossing threads; in
`Refcount.destroy` 438 only `RuntimeException` is caught around `fiber.destroy()`, so a `ThreadDeath` (or a
`StackOverflowError` from a fiber's `finally`) skips `object.decrement()`/`markFreed()`/`fiber.free()` and leaks the
object's holds. Low-medium; it matters on the exit-in-fiber path and under `close(true)`.

Fix: in `run()`, `if (thrown instanceof ThreadDeath)`: set `bailout`, skip `end()`, open the gate, rethrow on the
fiber's thread. In `awaitFiber`, when `error` is a `ThreadDeath`, `TruffleSafepoint.poll` instead of rethrowing.
Widen `Refcount.destroy`'s catch to `Throwable` for the bookkeeping, rethrowing after.

### 3. Native calls inside fibers pin carriers: cross-request stall under `HttpMain`

Fibers are virtual threads; FFM downcalls (`CurlNative` `curl_easy_perform` in `curl_exec`, PCRE2 matching) pin
the carrier for their duration, and the JDK does not compensate for native pinning (it does for `Object.wait`,
file I/O). The scheduler has `availableProcessors` carriers. With N request threads each inside a fiber in a
blocking `curl_exec` and N >= cores, no fiber of any request in the JVM can be scheduled until a transfer
completes: every request using fibers stalls, including ones that never call curl. PCRE2 pins only microseconds.
`sleep()` and socket `select` (`SocketStream.select` 213, a `Selector`) do unmount, so they are fine.

Fix: set `-Djdk.virtualThreadScheduler.parallelism`/`maxPoolSize` above the request-thread count in the
launchers, or run a fiber's long native calls after `TruffleSafepoint.setBlocked` on a platform-thread fallback
(the issue's step 1 fallback) when a request has used curl.

### 4. Blocking calls that no cancel can reach

`CoreFunctions.sleepNanos` 611 (`Thread.sleep`) and `PhpLanguage.CachedFile.root` 100 (`FutureTask.get`) are
not under `setBlockedThreadInterruptible`, so `close(true)` waits them out. `sleep(30)` in a `BatchMain` test
becomes `hung` and halts the JVM; `HttpMain` has no cancel anyway. Low. Fix: wrap both.

### 5. Debug-only unsynchronized statics

`Census` 40, 204, 218, 411, 414, 429: `LinkedHashMap`/`IdentityHashMap` statics mutated from every request thread
when `-Dphp.rc.audit`/`verify` is on. Under `HttpMain` that is a corrupt map or a `ConcurrentModificationException`
in the audit, never in production. Low; note it next to the flags.

## Known and verified open

- `exit()` in a fiber (t5) closes from the fiber's thread with the caller parked; it passed here, but
  `destroyFibers` has already cleared `fibers` when `finalizeContext`'s `abandonFibers` runs, so it depends on
  Truffle's exit handshake reaching the parked caller. Finding 2 is its sharp edge.
- Step 3's per-stack fields (`innermostRefIteration` 128, `lastCall`, `RecursionGuard`) are not swapped; that is
  a wrong-result risk across fibers, not a race (one thread runs at a time).

## Checked and found sound

- **FiberGate**: `open` is a state flag, not an event, so a stale `unpark` permit or spurious wake-up only
  re-checks it; the waiter writes `waiter` before reading `open`, the opener writes `open` before reading
  `waiter`, both volatile, so a lost wake-up is impossible. Truffle's re-entry after a safepoint re-runs
  `park`, which re-checks `open`. The hand-over strictly alternates, so the waiter's `open = false` can't race
  the next `open()`. The volatile gate orders the plain fields (`status`, `value`, `error`, `result`,
  `previous`, `pending`) across the two threads. The 20 us spin is bounded on both sides.
- **Pending releases** are per stack and swapped in `enter`/`awaitFiber`, including on the destroy-from-another-
  fiber path (t4). `end()` drains the fiber's own list before `DEAD`.
- **Closing**: Truffle's normal `Context.close()` excludes polyglot threads from "active other thread"
  (`closeImpl` -> `hasActiveOtherThread(false, false)`, 2865) and waits for alive polyglot threads after
  `finalizeContext` (2397), so a fiber thread still leaving at `close()` cannot make it throw. `join()` is under
  `setBlocked`; `ended()` pruning keeps threads that haven't left.
- **Admit counter**: only ever permissive; a leaked context can't deny access, and `hadFibers` guards the single
  decrement.
- **Shared thread-locals**: `Phases`, the `ByteBuilder` pool (strict `pollFirst`/`addFirst`, so a suspend during
  a borrow can't alias a builder) and `PersistentShares` (cleaner keyed on the object the worker's thread-local
  keeps alive) are the same objects used by one thread at a time.
- **Language-level caches**: `ConcurrentHashMap`s; translations run once in a `FutureTask` with losers waiting
  and failures removed (`PhpLanguage.file` 261-277); shared PCRE patterns use `putIfAbsent` and the loser frees
  its code (`PcreFunctions` 443-447); the per-context match data and JIT stack are used by one thread at a time
  (PCRE2 forbids only concurrent use) and freed in `finalizeContext` after the threads are gone.
- **FileLocks**: JVM-wide map, per-file monitor with `wait` under `setBlocked`, `removed` loop against a racing
  removal, released by `rawClose` -> `unlock` and `BuiltinState.close` closing open streams, OS channel closed
  with the last holder.
- **Curl statics**: concurrent maps of weak references, entries removed on free; `MIME_SOURCES` removed with the
  last clone.
- **HttpMain**: `newFixedThreadPool` with an unbounded queue and a 128 backlog is php-fpm's model; slow clients
  and PHP `sleep`/I/O hold pool threads by design (`maxReqTime` covers only receipt). No defect, but the only
  back-pressure is the queue.
