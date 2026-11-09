# 085: Native resources outlive their request: a dropped socket's selector, `proc_open` children

**State:** Open. Found 2026-11-09 by the concurrency reviews of the fiber work (031: Codex, Opus, Fable;
reports in `issues/031-reviews/`); reasoned from the code, not reproduced.

**Symptoms:** in a long-running server:
1. A request opens a socket and drops the resource without `fclose`. If a GC runs before the request ends, the
   socket's `Selector` is never closed: its native handles, descriptors and poll arrays leak, a few per request.
2. A `proc_open` child that the script doesn't `proc_close` is never reaped: zombies pile up. php-src's resource
   destructor reaps a child that has exited (`WNOHANG`).

**Cause:**
1. `BuiltinState.openStreams` (`BuiltinState.java:50`) holds streams weakly, so a stream collected before the end
   isn't closed there. `PhpResource` has no cleanup action. The channel's own cleanup doesn't close the selector,
   which OpenJDK frees only in `implClose()` (`SocketStream.java:56`).
2. Only `proc_close` waits for the child (`ExecFunctions.java:706`). Nothing tracks children per request.

**Fix:** to do.
1. Keep an owned cleanup record per stream until it closes, or a `Cleaner` action that closes the selector and the
   channel without holding the PHP resource.
2. Track children in `BuiltinState`, and reap the exited ones with `WNOHANG` when a process resource is freed and
   in `close()`, as php-src does.

**Test:** none yet.
