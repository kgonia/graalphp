# 082: `max_execution_time` isn't enforced, so a few runaway requests starve `HttpMain`

**State:** Open. Found 2026-11-09 by the concurrency reviews of the fiber work (031: Codex, Opus, Fable;
reports in `issues/031-reviews/`); reasoned from the code, not reproduced.

**Symptom:** a web request running longer than `max_execution_time` (30 s by default in a web SAPI):

| | Result |
|---|---|
| PHP 8.5 | "Fatal error: Maximum execution time of 30 seconds exceeded"; the shutdown functions run, the worker takes the next request |
| Truffle PHP | runs until it ends. `set_time_limit()` does nothing |

`HttpMain` (`HttpMain.java:133`) runs requests on `Executors.newFixedThreadPool(threads)`, whose queue is unbounded.
Once every worker is held by such requests, the server is down:
- an infinite loop;
- a long `sleep` or blocking I/O;
- a fiber hang;
- a blocking `flock` on a lock another request leaked.

New requests queue without bound, each holding its socket and its body (up to `MAX_BODY`).
`sun.net.httpserver.maxReqTime` bounds only receiving the request.

**Cause:** `max_execution_time` is stored (`Ini.java:79`, 315) but nothing measures it; php-src arms a timer per
request (`zend_set_timeout`) whose signal sets `EG(timed_out)`, checked at the VM's interrupt points.

**Fix:** to do.
- A timer per request that submits a Truffle `ThreadLocalAction` to the request's thread (or the running fiber's),
  raising php-src's fatal error at the next safepoint, after which the request ends as a fatal error does (shutdown
  functions, output).
- Measure CPU time only where php-src does (Linux: `ITIMER_PROF`, CPU time; `max_input_time` apart).
- `set_time_limit()` restarts the timer.
- Waits that a safepoint can't interrupt (087) need their own handling.
- In `HttpMain`, a bounded queue answering 503 when full, as php-fpm's listen backlog does.

**Test:** none yet (`while (true) {}` with `max_execution_time=1` under the CGI SAPI).
