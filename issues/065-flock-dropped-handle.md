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

**Fix:** with reference counts for resources (005, 036), release on the last reference. Until then, at the least,
release a context's locks at its request end, and key `FileLocks` by context.

**Test:** `tests/smoke/flock-dropped-handle.php`.
