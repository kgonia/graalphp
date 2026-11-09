# 084: `flock`: a hard link bypasses an exclusive lock, and a wait for another process can't be cancelled

**State:** Open. Found 2026-11-09 by the concurrency reviews of the fiber work (031: Codex, Opus, Fable;
reports in `issues/031-reviews/`); reasoned from the code, not reproduced.

**Symptoms:**
1. Request A takes `LOCK_EX` on path X. Request B opens Y, a hard link to the same file, and takes `LOCK_EX`. In
   PHP, B waits (or fails with `LOCK_NB`). Here B gets the lock at once, so both run their exclusive sections: lost
   updates, a corrupted file.
2. While another process holds the file's OS lock, a request waiting in `flock` holds the JVM-wide `Lock` monitor
   inside `FileChannel.lock`. Every request waiting for that file blocks on the monitor, outside any safepoint. No
   cancel reaches them, and `BatchMain` reports them `hung`.

**Cause:**
1. `FileLocks` keys its JVM-wide state by canonical path, and a hard link has another path. `FileFunctions.java:731`
   catches the `OverlappingFileLockException` the JVM throws then (it holds that file's OS lock already) and returns
   `LOCKED`, on the comment's assumption that "the JVM-wide state excludes", which it doesn't for another path.
2. `FileFunctions.java:634` and 712-724 block in `FileChannel.lock` under the monitor.

**Fix:** to do.
1. Key the locks by the file's identity (`Files.readAttributes(..., "unix:ino")` with the device, or `fileKey()`),
   and treat an overlap as contention: wait, or fail under `LOCK_NB`.
2. Poll `tryLock` under `TruffleSafepoint.setBlockedThreadInterruptible`, outside the monitor.

**Test:** none yet.
