# 080: Recursion a few hundred calls deep overflows the Java stack

**State:** Open. Found 2026-11-09 while measuring fibers (031); confirmed on PHP 8.5, on main (394acbc's build)
and on the fiber branch.

**Symptom:** `function d($n) { return $n == 0 ? 0 : d($n - 1) + 1; }`, then `d(900)`, on the JVM's default thread
stack (1 MB on Linux x64):

| | Result |
|---|---|
| PHP 8.5 | `900`. `d(100000)` works too. Infinite recursion ends with "Allowed memory size of ... exhausted" (memory_limit), a fatal error with a stack trace |
| Truffle PHP | JVM error, `NoClassDefFoundError: Could not initialize class OptimizedCallTarget$ReturnProfile` after a `StackOverflowError` (as 057's was) |
| Truffle PHP in a fiber (031) | "PHP Fatal error: Resource exhausted: Stack overflow" from 2000 calls on |

With `-Xss64m`, 20,000 calls work on the main thread and in a fiber. A virtual thread's stack can grow as far as
`-Xss` allows.

**Cause:** php-src runs a call between user functions without recursing in C (the VM's frames live on its own VM
stack), so the depth is bound by memory_limit only. Here each PHP call nests Java frames. The interpreter's frames
are large, so cold code overflows a 1 MB stack in under 900 calls. A `StackOverflowError` that hits a class's
initialization leaves the class unusable, and the error that surfaces names that class, not the overflow.

**Fix:** to do.
- Run PHP code on threads with a large stack: the launchers' request threads (`-Xss` or a thread with an explicit
  stack size), and the fibers' threads, which follow `-Xss`. A thread's stack is reserved, not committed, so a large
  one costs address space only.
- Turn a `StackOverflowError` into PHP's fatal error, with its message and stack trace, where the runtime can catch
  it: at a call boundary, before any class initialization it might break.
- Compare the depth reached with PHP's at the same memory_limit.

**Test:** none yet (`d(20000)` on the main thread and in a fiber).
