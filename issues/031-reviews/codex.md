Read-only review; no files were changed and no reproductions were run. **Reproduction-confirmed findings: none.** The findings below distinguish high-confidence code analysis from suspected failures requiring reproduction. P1 denotes hangs, corruption, or serious resource exhaustion; P2 denotes cumulative or diagnostic-only problems.

**High-confidence findings — reasoned, ranked by severity**

1. **P1 — Suspending inside curl callbacks can exhaust every carrier and deadlock.**  
   Locations: [CurlFunctions.java:888](../../src/main/java/pl/codeloom/php/builtins/CurlFunctions.java:888), [FiberGate.java:42](../../src/main/java/pl/codeloom/php/runtime/FiberGate.java:42).

   Start fibers whose curl write callbacks call `Fiber::suspend()`. Each parks with the FFM/native call still underneath it, retaining its carrier. Once all carriers are occupied, the request starts another fiber and waits for it; that fiber cannot execute, so the caller never reaches the code that resumes the earlier fibers. This can affect other contexts using the same scheduler. Java 25 still pins across native/foreign frames. [Java 25 documentation](https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html).

   **Fix:** use a native-call/callback bridge that permits suspension without retaining a carrier, or platform-backed execution for such stacks. Increasing carrier counts only moves the threshold.

2. **P1 — PCRE scratch buffers are reused while suspended fibers still own them.**  
   Locations: [PcreFunctions.java:188](../../src/main/java/pl/codeloom/php/builtins/PcreFunctions.java:188), [PcreFunctions.java:205](../../src/main/java/pl/codeloom/php/builtins/PcreFunctions.java:205).

   Fiber A suspends inside `preg_replace_callback`, holding scratch slot 0. B does likewise, holding slot 1. Resume A to completion: `release()` decrements the shared depth from 2 to 1. A third preg call now overwrites slot 1, although B still references it. Resuming B matches the replacement bytes instead of its original subject. For example, overwriting B’s `"bb"` with `"cc"` after its first `/b/` callback can produce `"zb"` instead of `"zz"`.

   **Confidence:** high; allocation assumes stack-order release, while fibers permit arbitrary completion order. **Fix:** lease scratch slots individually and release the actual slot; use occupancy tracking or per-fiber scratch storage.

3. **P1 — Hard-link aliases bypass exclusive file locks.**  
   Locations: [FileFunctions.java:628](../../src/main/java/pl/codeloom/php/builtins/FileFunctions.java:628), [FileFunctions.java:731](../../src/main/java/pl/codeloom/php/builtins/FileFunctions.java:731).

   Request A locks path X exclusively. Request B opens hard-link Y to the same file. Canonical paths remain different, creating separate Java `Lock` objects. B’s OS lock raises `OverlappingFileLockException`; the handler returns `LOCKED`. Both requests consequently enter their supposedly exclusive sections, allowing lost updates or file corruption.

   **Confidence:** high; the overlap exception is explicitly converted to success without establishing exclusion. **Fix:** identify locks by underlying file identity and treat overlap as contention, preserving blocking and `LOCK_NB` behavior.

4. **P1 — Shutdown pruning can skip a suspended fiber and discard its cleanup record.**  
   Locations: [PhpContext.java:972](../../src/main/java/pl/codeloom/php/PhpContext.java:972), [PhpContext.java:986](../../src/main/java/pl/codeloom/php/PhpContext.java:986).

   Leave 63 ended fibers followed by suspended F in the 64-entry list. During shutdown, F’s `finally` starts and retains suspended G. `fiberStarted()` prunes the 63 ended entries, moving F/G to indices 0/1. The destruction loop advances its old index past the new list size. G is never destroyed; `join()` ignores suspended fibers, and `fibers.clear()` loses G’s record. A live parked Truffle thread remains, violating the context-close contract. [Truffle finalization contract](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/TruffleLanguage.html#finalizeContext(C)).

   **Fix:** prohibit pruning while draining shutdown work; process newly added fibers through a stable queue and verify termination before clearing records.

5. **P1 — Garbage-collected socket streams leak selector resources.**  
   Locations: [BuiltinState.java:50](../../src/main/java/pl/codeloom/php/builtins/BuiltinState.java:50), [SocketStream.java:56](../../src/main/java/pl/codeloom/php/builtins/SocketStream.java:56).

   Open a socket, drop its PHP resource without `fclose`, then let GC run before request finalization. The weak `openStreams` entry disappears, so finalization cannot call `rawClose()`. `PhpResource` supplies no cleanup action. The socket channel’s cleanup does not close its selector: OpenJDK allocates selector native resources explicitly and releases them in `implClose()`. Repeated requests therefore leak native handles/descriptors and poll-array memory. [Windows selector implementation](https://raw.githubusercontent.com/openjdk/jdk25u/master/src/java.base/windows/classes/sun/nio/ch/WEPollSelectorImpl.java).

   **Fix:** retain independently owned cleanup records until closed, or attach an idempotent cleaner covering both selector and channel without retaining the PHP owner.

6. **P1 — Blocking requests can starve HttpMain indefinitely while its queue grows.**  
   Locations: [HttpMain.java:133](../../src/main/java/pl/codeloom/php/launcher/HttpMain.java:133), [RuntimeFunctions.java:105](../../src/main/java/pl/codeloom/php/builtins/RuntimeFunctions.java:105).

   Occupy every worker with long sleeps, blocking I/O, or nonterminating PHP. Subsequent requests queue without a capacity bound. The 30-second setting limits request reception, not PHP execution; `set_time_limit()` is a no-op. Requests remain unserved and queued exchanges consume memory. The executor’s unbounded queue is specified behavior. [Executors documentation](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Executors.html).

   **Fix:** bounded admission with explicit rejection/backpressure and an administrative request deadline. Cancellation also needs interruptible built-in waits and a strategy for native calls that cannot promptly return.

7. **P2 — Dynamic names permanently inflate engine state and later request allocations.**  
   Locations: [NameIds.java:12](../../src/main/java/pl/codeloom/php/runtime/NameIds.java:12), [PhpContext.java:736](../../src/main/java/pl/codeloom/php/PhpContext.java:736).

   Successive requests define uniquely named constants. Every name remains in the language-wide ID map. Each later request defining another name expands its dense `constantSlots` array to the accumulated global ID, despite defining only one dynamic constant itself. Memory consumption grows with request history and multiplies across concurrent contexts.

   **Confidence:** high; neither IDs nor their map entries are reclaimed. **Fix:** separate persistent compiled-name IDs from request-local dynamic declarations, or use sparse storage for dynamic IDs.

8. **P2 — Census has actual cross-request races when diagnostics are enabled.**  
   Locations: [Census.java:230](../../src/main/java/pl/codeloom/php/runtime/Census.java:230), [Census.java:414](../../src/main/java/pl/codeloom/php/runtime/Census.java:414), [Census.java:721](../../src/main/java/pl/codeloom/php/runtime/Census.java:721).

   Two requests mutate static `HashMap`/`IdentityHashMap` audit structures concurrently. One request can iterate `inDestructor` while another modifies it, or clear `pointGroups`/`traces` while another is collecting them. Effects include lost/misattributed findings and collection exceptions escaping into request execution.

   **Fix:** move request-specific audit structures into `PhpContext`; synchronize only genuinely global aggregation. Plain diagnostic counters in `Refcount` and `NativeClock` also lose concurrent updates.

**Suspected additional P1 failures — medium confidence, reasoned**

9. **Cancellation bypasses the fiber ownership protocol.**  
   Locations: [PhpFiber.java:289](../../src/main/java/pl/codeloom/php/runtime/PhpFiber.java:289), [PhpFiber.java:336](../../src/main/java/pl/codeloom/php/runtime/PhpFiber.java:336).

   Cancel a context containing several suspended fibers. Safepoint exceptions can escape their gate waits without a handoff. Each fiber then unwinds and calls `end()`, draining the same currently installed `context.pendingReleases()`; shared refcounts and the shared builder pool can also be modified concurrently. This threatens double releases, lost releases, and collection corruption. The missing ownership protection is clear; the exact cancellation schedule needs reproduction.

   **Fix:** serialize abort unwinding under context ownership and retain each stack’s cleanup state independently of the currently active stack.

10. **The global generator version counter can resurrect a stale cache.**  
    Locations: [PhpGenerator.java:382](../../src/main/java/pl/codeloom/php/runtime/PhpGenerator.java:382), [PhpGenerator.java:404](../../src/main/java/pl/codeloom/php/runtime/PhpGenerator.java:404).

    B reads version N for `++`, then pauses. A advances it, caches a delegation root at N+1, then unlinks again to N+2. B finally writes N+1. A can now accept its obsolete root, risking incorrect iteration or an internal failure walking the detached chain. This requires a targeted generator reproduction.

    **Fix:** make the version request-local; atomic increment is a less isolated alternative.

**Checked and found sound within normal execution**

The gate’s volatile handshake prevents ordinary lost wake-ups and publishes transferred state; spurious unparks are rechecked. Its bounded spin adds carrier CPU cost but alone establishes no permanent starvation.

Normal shutdown joins ended fibers before `closeExited`. The atomic admission counter remains enabled until disposal, and BatchMain’s CAS permits only one embedder close. Finding 4 limits shutdown coverage; the already-known exit-inside-fiber deadlock and engine-wide assumption invalidation are not new findings.

Translation-cache publication uses `ConcurrentHashMap` plus `FutureTask`, with translation outside the map lock. PCRE duplicate compilation frees the losing allocation. Curl callback maps are concurrent, and cleaners enqueue request-owned native cleanup. Normal HTTP execution keeps the shared fiber thread-local state serialized. HeapProbe uses atomic counters; its forced-GC overhead is intentional.