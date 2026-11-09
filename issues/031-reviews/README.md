# Concurrency reviews of the fiber work (2026-11-09)

Three reviewers each read the fiber implementation (031) and the runtime under `HttpMain`, looking for races,
deadlocks and resource starvation. All three worked from the same [brief](brief.md) and the code at e9cdaab:
- [codex.md](codex.md): Codex CLI, `gpt-6-astra` at `xhigh`, read-only, no reproductions;
- [opus.md](opus.md): Claude Opus, with reproductions;
- [fable.md](fable.md): Claude Fable, with reproductions.

[`repro/`](repro/) keeps the reproductions that showed a defect, and the controls next to them:
- `pin.php` and `nopin.php`: native-frame pinning on two carriers;
- `prune.php` and `prune-exit.php`: the skipped fiber;
- `pcre-scratch.php`: PCRE's scratch buffers;
- the `eval*.php` scripts and `Heap.java`: the heap growth;
- `Harness.java` and `Cancel.java`: Opus's shared-engine harnesses.

| Finding | Found by | Shown by | Where it went |
|---|---|---|---|
| A fiber suspended under a native frame pins its virtual thread's carrier; deadlock once all are pinned | all three | Opus (`pin.php`) | fixed: platform threads (031, 0effc2b) |
| `destroyFibers` skips a fiber when the list is pruned mid-loop | Codex, Opus | Opus (`prune.php`) | fixed (0effc2b) |
| PCRE scratch buffers released out of order across fibers | Codex | main session (`pcre-scratch.php`) | fixed (0effc2b) |
| A fiber thread that can't enter a closing context leaves its caller waiting | Fable, Opus | reasoned | fixed (0effc2b) |
| Truffle's cancel or exit runs PHP destructors inside a fiber; `Refcount.destroy` skips its counts under an `Error` | Fable | reasoned | fixed (0effc2b) |
| `PhpGenerator`'s static version counter, incremented unsynchronized by every request | Codex | reasoned | fixed (087) |
| `max_execution_time` not enforced; `HttpMain`'s unbounded queue | all three | reasoned | 082 |
| Language-level state grows per request (eval'd classes, literals, run-time names) | Opus, Codex | Opus (`Heap.java`) | 083 |
| `flock` across hard links; OS lock waits under a monitor | Codex, Opus | reasoned | 084 |
| A dropped socket's selector; `proc_open` zombies | Codex, Opus | reasoned | 085 |
| PCRE caches never evict | Opus | reasoned | 086 |
| Waits no cancel reaches; diagnostics' static maps | Fable, Opus, Codex | reasoned | 087 |

Checked and found sound by all three:
- `FiberGate`'s handshake: no lost wake-up;
- the per-stack pending releases;
- joining the fiber threads at the request's end;
- the admission counter;
- `BatchMain`'s single close;
- cancels with fibers suspended.
