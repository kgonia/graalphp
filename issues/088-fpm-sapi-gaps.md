# 088: Under the fpm SAPI, php-fpm's other functions and request handling are missing

**State:** Open. Found 2026-11-09 with launcher.FastCgiMain (D11), which runs requests as php-fpm
(`php_sapi_name()` and `PHP_SAPI` `fpm-fcgi`). `fastcgi_finish_request()` is done (branch `fastcgi`).

**Symptom:** a script that checks the SAPI's name and calls php-fpm's functions, or reads what php-fpm gives it,
differs from php-fpm 8.5.12 behind the same nginx:

| | php-fpm | FastCgiMain |
|---|---|---|
| `apache_request_headers()`, `getallheaders()` | the request's headers, in the FastCGI params' order | "Call to undefined function" |
| `fpm_get_status()` | the pool's status (pool, process manager, start time, accepted conn, listen queue, the processes) | "Call to undefined function" |
| `$_SERVER`'s order | the FastCGI params' order, then `PHP_SELF`, `REQUEST_TIME_FLOAT`, `REQUEST_TIME` | sorted by name (`PhpContext.superglobals` over a `TreeMap`; the polyglot environment keeps no order) |
| a body over `post_max_size` | the script runs: a "POST Content-Length ... exceeds the limit" warning, `$_POST` empty | a 413 without running the script (`MAX_BODY`, as `HttpMain`) |

**Cause:** FastCgiMain shares `CgiSapi` with `HttpMain`; the fpm SAPI adds only its name and
`fastcgi_finish_request()`.

**Fix:** to do.
- `apache_request_headers()`/`getallheaders()`: the `HTTP_*` params in their order, named as fpm's
  `sapi_add_request_header` names them, `CONTENT_TYPE` and `CONTENT_LENGTH` too. The params' order has to reach the
  context: the environment loses it, so it needs a channel of its own (as `CgiSapi.REQUEST_END`).
- `$_SERVER` in the params' order, by the same channel.
- `fpm_get_status()`: FastCgiMain's own pool (threads, accepted connections, the queue, each thread's request) in
  php-fpm's array; the numbers are the server's, so they can't match a php-fpm's, only its shape.
- `post_max_size` in `CgiSapi.installRequest`, for both servers.

**Test:** `tests/http/fpm.sh` once each exists (php-fpm and FastCgiMain behind one nginx).
