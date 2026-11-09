# 031: No Fiber class

**State:** In progress on branch `fiber` (2026-11-09): steps 1, 2 and 4 done in part, `Zend/tests/fibers` at 68
of 103 (Status below).

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

**Step 0 was folded into step 1** (2026-11-09). Each part of it turned out to belong with the fiber thread that
uses it:
- **The `ThreadLocal`s:** a fiber's thread must see its request thread's `Phases`, persistent curl shares and
  `ByteBuilder` pool. The fiber's thread takes them over when it starts, and that is a few lines of the fiber's
  start, not a migration. Binding the persistent shares to the worker through `HttpMain`'s `ThreadFactory` gains
  nothing: the `ThreadLocal` already lives as long as the worker, and a `Cleaner` already frees the handles when the
  thread ends (`PersistentShares`). An embedder's threads bind nothing, so the `ThreadLocal` would stay as the
  fallback anyway.
- **Thread access:** `isThreadAccessAllowed` should allow a second thread only when it is one of the context's
  fiber threads, not any thread. A context stays as single-threaded as PHP for embedders, so it goes in with the
  fibers. The launchers set `allowCreateThread(true)`; embedders must too, which needs documenting.

**Step 1: tracer bullet, the go/no-go gate.**
- A minimal `Fiber`: `start`, `suspend`, `resume`, `getReturn` on a virtual Truffle thread, with the hand-over,
  the thread-access check and the takeover of the request thread's thread-local state.
- Measure against PHP 8.5, time and peak memory:
  - switch and creation cost;
  - 10,000 suspended fibers;
  - deep recursion inside a fiber (stack depth, the stack-overflow error);
  - code compiled by libgraal running on a virtual thread.
- **The engine-wide cost.** The first time any context gets a second active thread, Truffle invalidates
  `PolyglotEngineImpl.singleThreadPerContext`, an assumption of the whole engine, and `HttpMain`'s contexts share
  one engine. Run L2 on a server where one request has used a fiber, against one where none has. If it regresses,
  the options are a separate engine for fiber requests, or keeping the cost.
- Virtual threads in Truffle 25.4 (checked 2026-11-09, `PolyglotEngineImpl.validateVirtualThreadCreation`):
  - HotSpot: real virtual threads, experimental. Caller frames on them are readable only (`READ_ONLY`), which is
    all this runtime uses (`Backtrace`, `Callables`, `Census`, `SourceLocation`). Truffle debugging and CPU-time
    limits don't work on them.
  - Native image with JIT: still one platform thread per virtual thread (oracle/graal#7520, open; no change since
    Truffle 24.1). A native-image server pays a platform thread per fiber, so measure step 1's numbers there too.
  - Polyglot isolates refuse virtual threads.
  - One context can be entered by at most 65535 threads (chumer on #7520); check whether ended fiber threads count
    towards it.
- Check `BatchMain`'s timeout, which cancels the context with `close(true)`: Truffle allows cancelling an active
  context only while it is single-threaded.
- The gate keeps virtual threads or switches to platform threads.

**Step 1 results (2026-11-09, commit 064367a).** The tracer bullet runs: `start`, `suspend`, `resume`, `throw`,
`getReturn`, `getCurrent`, the `is*` predicates, named arguments, exceptions both ways, deep stacks, and the
request's end destroying suspended fibers (their finally blocks run). `Zend/tests/fibers`: 58 of 103 pass. The
failures fall into the later steps:
- a fiber destroyed when its last reference goes (`unset`, `= null`; `destructors_*`, `unfinished-fiber-*`,
  `gh9735-*`): step 4;
- traces (`debug-backtrace`): step 5;
- a fiber suspended inside a generator (`gh15108-*`): `yield from` an IteratorAggregate whose iterator is a
  generator never resumes it, without fibers too (081); `gh9916-*` still to look at;
- `new FiberError` must throw, and the `fiber.stack_size` ini (`gh10249`): step 2;
- `register_tick_function` is missing (`ticks`), outside 031.

Measured in containers on the WSL VM (its E-cores), single runs, JVM (GraalVM 25.4): orders of magnitude, not an
A/B.

| | PHP 8.5 | Truffle PHP |
|---|---|---|
| suspend and resume, one fiber in a loop | 52-60 ns | 1.8 us |
| a fiber created, started and finished | 7.7 us | 19-30 us |
| 10,000 fibers suspended at once: start each | 16 us | 290 us |
| the same: resume each to its end | 7 us | 110 us |
| the same: peak memory (cgroup) | 142 MB | 325 MB |

- **Parking on each hand-over cost 49 us a round trip**: waking a parked thread goes through the OS, slow in the VM.
  The waiter now spins for 20 us before it parks (`FiberGate`), which keeps a busy fiber's hand-overs off the OS.
  Without the spin, virtual threads alone took 7 us.
- **Running the request on a virtual thread too didn't help** (1.5 us with the spin), so the launchers keep
  platform threads.
- **A long-suspended fiber still costs two wake-ups** (its carrier's and the caller's), which the 10,000-fiber rows
  show. Truffle also checks every thread of the context each time one is added (`checkAllThreadAccesses`), which
  is linear in the live fibers; `isThreadAccessAllowed` answers from a concurrent set to keep that cheap.
- **Stack depth:** a fiber reaches the depth `-Xss` allows, as the main thread does. On the default 1 MB stack
  both overflow within a few hundred to two thousand calls, a problem without fibers too (080).
- **Decision:** virtual threads stay. Platform threads would have the same wake-ups and a reserved stack each.

Still open from step 1:
- L2 on a server after a request that used a fiber (the engine-wide assumption);
- the same numbers on the native image;
- `BatchMain`'s `close(true)` with a fiber suspended;
- the 65535-thread limit per context.

**Status after step 4's first part (2026-11-09, 2190670).** 68 of 103, the same over repeated runs, with no hangs:
- done: `FiberError::__construct` and `fiber.stack_size` (step 2); a suspended fiber destroyed at its last
  reference (step 4: `unfinished-fiber-*`);
- the 23 about garbage cycles need the cycle collector, which this runtime doesn't have yet (005, 036): an object
  that references itself never gets its destructor, fibers or not (`destructors_*`, `gc-cycle-*`, `gh9735-*`,
  `gh10340-003`, `gh10496-001`, `oss-fuzz-*`);
- 6 are 081 (`gh15108-*`);
- `gh9916-*`: a fiber destroyed while suspended inside generators (an outer generator's finally runs that PHP's
  doesn't, and "Cannot resume an already running generator");
- `throw-in-multiple-destroyed-fibers-after-shutdown`: when two fibers throw while destroyed, which exception
  surfaces differs;
- `debug-backtrace` (step 5) and `ticks` (`register_tick_function`, outside 031).

**Closing a context with fibers, Truffle 25.4.** exit() and a fatal error close the context through
`closeExited`, and a cancel through `close(true)`. While another thread of the context is still active, Truffle
does that on a cleanup thread, and that thread deadlocks against the embedder's own `close()`: the context's
monitor and its cancel lock are taken in opposite orders. A thread dump caught it in `BatchMain`. What avoids it:
- the request's end waits for its fibers' threads to end (`PhpFiber.join`), so `closeExited` closes on the
  calling thread;
- a context that started a fiber admits any thread until it is disposed: Truffle's cleanup thread enters it to
  finish the close. `isThreadAccessAllowed` isn't told which context it is asked about, so while any context has
  fibers, every context admits threads;
- an embedder closes a context once: `BatchMain` from the script's thread or from the timeout's cancel, whichever
  comes first. `HttpMain` and `PhpMain` have no cancel. `exit()` inside a fiber still closes from the fiber's thread
  with the caller's thread blocked: to fix with 073's unwinding exit.

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
- The reference-count census (`Census.roots`) walks the current thread's frames for roots. A suspended fiber's
  locals are roots too, so it must also walk every suspended fiber's frames, which only the fiber's own thread can
  do. That is the same request to a parked thread as step 5's.
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
- the engine-wide single-thread assumption: one request using a fiber may slow every later request on the same
  engine (step 1 measures it);
- the cost of traces inside fibers (step 5);
- a per-stack field missed in step 3, which would leak state between fibers.

**Test:** `tests/smoke/named-args-fiber.php`; the rest as above.
