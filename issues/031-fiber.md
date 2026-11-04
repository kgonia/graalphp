# 031: No Fiber class

**State:** Open

**Symptom:** `new Fiber(fn() => ...)` is "Class "Fiber" not found". With named arguments, `Fiber::start(b: 'B')`
passes the names on to the fiber's function (Z_PARAM_VARIADIC_WITH_NAMED; `tests/smoke/named-args-fiber.php`,
waiting for this).

**Cause:** fibers (PHP 8.1, `Zend/zend_fibers.c`) aren't implemented: `Fiber`, `Fiber::start/resume/suspend/
throw/getReturn/isStarted/isSuspended/isRunning/isTerminated/getCurrent`, `FiberError`.

**Fix:** a fiber is a coroutine over the PHP stack; on the JVM a virtual thread or a continuation handed over at
`Fiber::suspend()` and `resume()`, with the context's per-request state (output buffers, error handlers) shared.
`Fiber::start()` takes its extra named arguments as `call_user_func` does (`BuiltinSignature`'s forwarding list
already names it).
