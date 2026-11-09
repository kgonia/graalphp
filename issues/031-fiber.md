# 031: No Fiber class

**State:** Open. Plan written 2026-11-09 (below); nothing implemented yet.

**Symptom:** `new Fiber(fn() => ...)` is "Class "Fiber" not found". With named arguments, `Fiber::start(b: 'B')`
passes the names on to the fiber's function (Z_PARAM_VARIADIC_WITH_NAMED; `tests/smoke/named-args-fiber.php`,
waiting for this).

**Cause:** fibers (PHP 8.1, `Zend/zend_fibers.c`) aren't implemented: `Fiber`, `Fiber::start/resume/suspend/
throw/getReturn/isStarted/isSuspended/isRunning/isTerminated/getCurrent`, `FiberError`,
`ReflectionFiber`.

## Design

A fiber suspends its whole PHP stack, from any depth below its function. The Bytecode DSL's `yield` (generators)
suspends one frame (wiki/bytecode-dsl.md); suspending a stack with it would make every call a suspension point and
cost all code, fibers or not. Truffle has no continuations for guest languages. So each fiber runs on its own
**virtual thread** entered into the request's context (`env.newTruffleThreadBuilder(body).virtual(true)`, in
Truffle 25.4), as TruffleRuby's fibers do:

- **Hand-over:** `start`, `resume`, `throw` and `suspend` park the running thread and unpark the other, passing a
  transfer (a value or a throwable, php-src's `zend_fiber_transfer`). Exactly one thread of a context runs at any
  time, and park/unpark orders memory, so the request's state needs no locks.
- **Shared state:** what php-src keeps in `EG` beside the VM state stays on `PhpContext`, shared by the fibers:
  output buffers, globals, functions and classes, error handlers, the object store.
- **Switched state:** what `zend_fiber_capture_vm_state` and `_restore_vm_state` swap moves with the fiber.
  `error_reporting` is the observable one (the `@` level). A fiber starts with the `error_reporting` ini value,
  not the caller's current level (`zend_fiber_execute`).
- **Cost when unused:** the thread is created at `start()`, so a script without fibers keeps the context
  single-threaded.
- **Fallback:** platform threads, if virtual threads fail step 1's gate. The hand-over is the same.

## Plan

One commit per step, each with L0 and its own tests.

**Step 0: groundwork, no behaviour change.**
- `ThreadLocal`s become what a fiber's thread must see the same as its request's thread:
  - `Phases.CURRENT`: a `ScopedValue` bound for the request (`RequestPhases`, the launchers).
  - `CurlFunctions.PERSISTENT_SHARES`: a `ScopedValue` bound for the worker's lifetime. `HttpMain`'s pool gets a
    `ThreadFactory` that binds it and frees the share handles in `finally`, as php-src's module shutdown does.
    `PhpMain` and `BatchMain` bind it in `main`; an embedder that binds nothing gets one per context. The binding
    code lives in the runtime, and the launchers call it.
  - `ByteBuilder.POOL` stays a `ThreadLocal`: it is a cache on a hot path, also used outside requests. A fiber's
    thread sets it to its request thread's pool, safe because one thread runs at a time.
- Multi-threaded contexts:
  - `PhpLanguage.isThreadAccessAllowed` returns true.
  - The launchers set `allowCreateThread(true)`. Without it `isCreateThreadAllowed()` is false and a fiber can't
    start. Embedders must set it too; document that.
- Gate: L0 passes and L2 doesn't regress. Truffle keeps its single-thread assumption until a second thread
  enters, so no change is expected, but it must be measured.

**Step 1: tracer bullet, the go/no-go gate.**
- A minimal `Fiber`: `start`, `suspend`, `resume`, `getReturn` on a virtual Truffle thread, with the hand-over.
- Measure against PHP 8.5, time and peak memory:
  - switch and creation cost;
  - 10,000 suspended fibers;
  - deep recursion inside a fiber (stack depth, the stack-overflow error);
  - code compiled by libgraal running on a virtual thread.
- The gate keeps virtual threads or switches to platform threads.

**Step 2: the full API as an explicit state machine.**
- States as an enum: php-src's INIT, RUNNING, SUSPENDED, DEAD, with its flags THREW, BAILOUT and DESTROYED (the
  fiber was force-closed).
- `FiberError` with php-src's messages:
  - "Cannot start a fiber that has already been started";
  - "Cannot resume a fiber that is not suspended";
  - "Cannot suspend outside of a fiber";
  - "Cannot suspend in a force-closed fiber";
  - "Cannot switch fibers in current execution context";
  - "Cannot call constructor twice";
  - "Cannot get fiber return value: " + "The fiber has not been started", "The fiber has not returned",
    "The fiber threw an exception" or "The fiber exited with a fatal error".
- An exception a fiber's function throws reaches the code that started or resumed it, and `throw()` raises one at
  the `suspend()`.
- `getCurrent`, the `is*` predicates, `getReturn` by reference (`return-by-ref.phpt`).
- `start()` forwards its named arguments as `call_user_func` does (`BuiltinSignature`'s forwarding list names it
  already). `named-args-fiber` passes.

**Step 3: the switched state.**
- Classify every `PhpContext` field as shared or per-stack, starting from:
  - the `@` level;
  - `innermostRefIteration` (the foreach-by-ref chain);
  - `lastCall`;
  - `RecursionGuard`'s depth.
- Swap the per-stack ones on each hand-over, as `zend_fiber_restore_vm_state` does.
- Block switching where php-src calls `zend_fiber_switch_block()`: after the request's shutdown
  (`zend_fiber_shutdown`) and in `pcntl`'s signal handlers.

**Step 4: destroying a fiber.**
- A suspended fiber is destroyed when its last reference goes (destructors are on by default) or at request end.
- php-src's `zend_fiber_object_destroy` sets DESTROYED and resumes it with a graceful exit: no catch takes it,
  finally blocks run, and a `suspend()` there is the force-closed `FiberError`.
- Build the unwinding exception once, shared with 073 (`exit()` unwinds the stack the same way).
- Request end destroys every fiber still alive before the context closes, since Truffle requires its threads to
  have left.
- `exit()` and fatal errors inside a fiber, nested fibers (the `exit-in-fiber`, `fatal-error-*` tests).

**Step 5: backtraces, the hardest part.**
- In php-src a running fiber's bottom frame links to the frame that started or resumed it
  (`stack_bottom->prev_execute_data`). A trace from inside a fiber goes on into that caller's stack:
  `{closure}()`, `Fiber->start()`, then the caller's frames.
- A fiber's thread sees only its own frames. The caller's thread waits in `TruffleSafepoint.setBlocked(...)`, so
  the fiber can ask it for its frames through `env.submitThreadLocal`, only when a trace is needed
  (`debug_backtrace`, an exception's construction). A switch without a trace costs nothing extra.
- `ReflectionFiber` (`getTrace`, `getExecutingFile`, `getExecutingLine`) of a suspended fiber asks the fiber's
  parked thread the same way.
- Measure an exception thrown inside a fiber against step 1's numbers.

**Step 6: tests and closing.**
- `Zend/tests/fibers` (113 tests in PHP 8.5) through `tests/phpt`: all of them, except those needing an extension
  this runtime lacks (`pcntl`'s signal tests).
- Smoke tests for each state transition, the destructor order and the `@` level.
- L1 and L2 for no regression without fibers, and the fiber benchmark from step 1.
- This issue, the wiki, `issues/README.md`.

**Risks:**
- virtual threads under libgraal (step 1 decides);
- the cost of traces inside fibers (step 5);
- a per-stack field missed in step 3, which would leak state between fibers.

**Test:** `tests/smoke/named-args-fiber.php`; the rest as above.
