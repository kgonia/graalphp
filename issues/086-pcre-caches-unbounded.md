# 086: PCRE caches never evict: one-off patterns stay for good, and a context's grow without bound

**State:** Open. Found 2026-11-09 by the concurrency reviews of the fiber work (031: Codex, Opus, Fable;
reports in `issues/031-reviews/`); reasoned from the code, not reproduced.

**Symptom:**
- The engine-wide pattern cache keeps the first 4,096 patterns compiled for good, one-off ones included.
- After that, each new pattern is compiled (with JIT) into the context's own `regexes` (`PcreFunctions.java:92`),
  which has no bound until the request ends.

A request building many patterns (`preg_quote` in a loop) grows native memory without limit. A hot pattern first
seen after the shared cache filled is compiled again in every request. php-src keeps 4,096 entries per process and
evicts the oldest.

**Cause:** `PcreFunctions.java:81` (`LIMIT`) and 428-447: the shared cache only fills, and the per-context map only
grows.

**Fix:** to do. Make both caches LRU, as php-src's is:
- free an evicted pattern's code only when no match uses it (`matchDataInUse`);
- count references to the shared code, since contexts on other threads may be matching with it.

**Test:** none yet.
