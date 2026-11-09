# 087: Waits a cancel can't reach; diagnostics' shared state isn't thread-safe

**State:** Open. Found 2026-11-09 by the concurrency reviews of the fiber work (031: Codex, Opus, Fable;
reports in `issues/031-reviews/`); reasoned from the code, not reproduced.

**Symptoms:**
1. Cancelling a context (`close(true)`, `BatchMain`'s timeout, a future `max_execution_time`: 082) waits out any of
   these, so `sleep(30)` in a `BatchMain` test becomes `hung` and halts the JVM:
   - `sleep()`/`usleep()` (`CoreFunctions.sleepNanos`, `CoreFunctions.java:611`: `Thread.sleep`);
   - a translation another context is doing (`PhpLanguage.CachedFile.root`, `PhpLanguage.java:100`:
     `FutureTask.get`);
   - `flock` (084).
2. With `-Dphp.rc.audit` or `-Dphp.rc.verify`, the census's static maps (`Census.java:40`, 204, 218, 411, 414, 429:
   `HashMap`, `IdentityHashMap`, `LinkedHashMap`) are changed by every request thread at once under `HttpMain`. The
   result is lost or misattributed findings, or a `ConcurrentModificationException` out of a request. The
   statistics counters in `Refcount` and `NativeClock` lose updates. Diagnostics only, never in production.

**Cause:**
1. These waits aren't under `TruffleSafepoint.setBlockedThreadInterruptible`, so a safepoint action never runs in
   them.
2. Per-request audit state kept in statics.

**Fix:** to do.
1. Run each wait under `setBlockedThreadInterruptible`, `sleep` as an interruptible `Thread.sleep` of what is left.
2. Move the per-request audit state onto `PhpContext`, and use atomic counters for the statistics.

Fixed on the way (branch `fiber`): `PhpGenerator`'s static `unlinkVersion`, which every request thread incremented
unsynchronized. A lost increment could bring an old version back and pass a delegation-root cache from before an
unlink as current. It is an `AtomicInteger` now, so versions never repeat.

**Test:** none yet (`sleep(30)` under `BatchMain --timeout=2`).
