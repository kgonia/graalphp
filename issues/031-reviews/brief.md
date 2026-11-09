# Review: race conditions, deadlocks and resource starvation

You are reviewing Truffle PHP, a PHP 8.5 runtime on GraalVM Truffle (Bytecode DSL), Java 25, GraalVM 25.4.
Repository: branch `fiber` of `kgonia/graalphp`, at e9cdaab.
The rule for the runtime: PHP code must behave exactly as on PHP 8.5, and the implementation must be fast and
memory-frugal.

## What to find

Find concurrency defects: data races, deadlocks and livelocks, lost wake-ups, unsafe publication, and resource
starvation (threads, carriers, memory, native handles, file descriptors, locks). Look in two places.

**1. The new Fiber implementation** (issue `issues/031-fiber.md` has the design, the plan and the measured state).
A fiber runs on a virtual Truffle thread entered into the request's context. The fiber and its caller hand over
through two gates, and exactly one thread of a context runs PHP code at a time.
- `src/main/java/pl/codeloom/php/runtime/PhpFiber.java`: the states, start, resume, throw, suspend, destroy,
  abandon, join, and the thread-access counter.
- `src/main/java/pl/codeloom/php/runtime/FiberGate.java`: spins for 20 us, then parks under
  `TruffleSafepoint.setBlockedThreadInterruptible`, with a volatile Dekker-style handshake.
- `src/main/java/pl/codeloom/php/builtins/Fibers.java`: the PHP class. It shares the request thread's
  thread-locals with the fiber's thread (`Phases`, the `ByteBuilder` pool, curl's persistent shares).
- `src/main/java/pl/codeloom/php/PhpContext.java`: `currentFiber`, `fibers`, `destroyFibers`, `abandonFibers`,
  `swapPendingReleases`, `endScript`, `exit`.
- `src/main/java/pl/codeloom/php/PhpLanguage.java`: `isThreadAccessAllowed`, `finalizeContext`, `disposeContext`.
- `src/main/java/pl/codeloom/php/runtime/Refcount.java` (`destroy`: a fiber destroyed at refcount zero).
- The launchers in `src/main/java/pl/codeloom/php/launcher/`: `HttpMain` (a fixed thread pool, one shared
  `Engine`, a new `Context` per request), `PhpMain`, `BatchMain` (a timeout cancels with `close(true)`).

Already found and worked around, so verify the fixes rather than re-report the problem:
- Closing a context that had a second thread deadlocked inside Truffle 25.4. `closeExited` after exit or a fatal
  error, and `close(true)` on a timeout, run on a Truffle cleanup thread that takes the context's monitor and its
  cancel lock in the opposite order from the embedder's `Context.close()`. The fixes:
  - the request's end joins its fibers' threads;
  - a context that started a fiber admits any thread until it is disposed (a global counter);
  - `BatchMain` closes each context once (a CAS).
- Known open issues: `exit()` called inside a fiber still closes from the fiber's thread while the caller's
  thread is blocked. Truffle invalidates its engine-wide single-thread assumption once any context gets a second
  thread.

**2. The whole runtime under `HttpMain`.** Many request threads run their own contexts on one shared engine.
Look for:
- state shared across contexts or threads that is not safe: static mutable fields, caches, `ThreadLocal`s,
  `Cleaner`s, language-level state in `PhpLanguage` (the translation cache, PCRE patterns), `CurlFunctions`'
  static maps, file locks (`FileLocks`), streams and sockets (`Network`, `SocketStream`), `HeapProbe`, `Census`,
  `Phases`;
- starvation: request threads blocked in I/O or `sleep`; virtual-thread carriers pinned by native calls (FFM:
  curl, PCRE2) or by spinning fibers (`FiberGate` spins on its carrier); unbounded queues or caches; handles,
  descriptors or native memory that leak when a request ends abnormally (exit, fatal error, cancel).

## How to work

- Read the code. Small reproductions are welcome, but nothing heavy: the machine runs timed benchmarks.
  - Run a PHP file on the host JVM with
    `JAVA_OPTS="-Dpolyglot.php.Parser=java" ./bin-php.sh target/<your-dir>/x.php`, from the repository root, in
    Git Bash. The build in `target/classes` is current; don't rebuild.
  - Keep files under `target/review-<your-name>/`.
  - No Docker, no benchmarks, no load tests, no runs longer than a minute or two.
- Don't edit anything outside your `target/review-<your-name>/` directory: no source changes, no git commands that
  write, no pushes.
- Be concrete. Each finding needs:
  - `file:line`;
  - the interleaving or scenario, step by step;
  - its effect: a wrong PHP result, a hang, a crash, or a leak;
  - your confidence (confirmed by a reproduction, or reasoned), and why;
  - a suggested fix.

  Rank the findings by severity. Separate the confirmed ones from the suspected ones, and say what you checked and
  found sound, so that the next reader doesn't redo it.

## Deliverable

A report in Markdown, at most about 1,500 words. Write it to `target/review-<your-name>/report.md` if you can
write files, and also give it as your final answer.
