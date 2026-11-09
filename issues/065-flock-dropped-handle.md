# 065: A lock held by a handle that went out of scope is never released

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** a function opens a file, takes `LOCK_EX` and returns without `fclose`. Then `LOCK_EX | LOCK_NB` on a new
handle (`flock-dropped-handle`):

| | Second lock |
|---|---|
| PHP 8.5 | `true`, would-block `0` |
| Truffle PHP | `false`, would-block `1` |

Without `LOCK_NB` the second `flock` hangs. `FileLocks` is static, so the lock stays across contexts on one engine,
and another request on the server waits on it too.

**Cause:** the lock lives in `FileLocks` (`FileFunctions.java:464`, `606-676`). Only `flock(LOCK_UN)`, `fclose` and
request end release it. php-src releases a stream when its last reference goes, which closes the descriptor and with
it the lock.

**Fix:** to do, and a design decision. The lock lives as long as the stream, so this is reference counting for
resources: php-src frees a stream when its last reference goes, which closes it and releases the lock. Other effects
of that release are observable too:
- `tmpfile()`'s file goes;
- buffered writes are flushed;
- `proc_open`'s pipes close.

The rest is in place:
- request end releases the locks already (`FileLocks`, `openStreams`);
- `FILES` is right to be shared by the contexts, since locks contend across requests as across processes.

What resource counting takes (looked at 2026-11-09):
- a fifth counted kind beside arrays, objects, closures and references, on the P1 store paths (wiki/destructors.md);
  about 40 sites in `Refcount`, `PhpArray`, `Locals`, `PropNode`, `BuiltinRootNode` and the frame epilogue;
- every built-in that keeps a resource internally (a curl handle's `CURLOPT_FILE`, stream contexts, `proc_open`) must
  hold a count, or a stream closes too early;
- the census (`Census`) must know resources.

Its compiled-code cost is of the same kind as the destructors work's (Gate 2), so it is the owner's call. A shared
counted supertype of `PhpClosure` and `PhpResource` would keep the number of type checks per release site as it is.

**Test:** `tests/smoke/flock-dropped-handle.php`.
