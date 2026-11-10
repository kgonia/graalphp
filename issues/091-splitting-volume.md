# 091: Splitting copies a third of WordPress's compiled nodes; turning its two biggest sources off is slower

**State:** Open, measured: the obvious fix loses. Written 2026-11-09 on `fastcgi` (bytecode, JVM, Oracle GraalVM
25.4.4.1.1), from `tests/gate2/native.sh diag` (TraceSplitting, CompilationStatistics) and `native.sh load` through
nginx + FastCgiMain, k6 readers + admin, CPUs 14-21.

**What happens:** under Gate 2's load the compilation settles after ~10 minutes: 7975 compilations, 1736 s of
compiler threads' time, 75% of it tier 2. Splitting made 1986 copies (+33.6% nodes); 2338 of the compilations are
copies (15% of the compile time). The two biggest sources:

- `CallableDispatchNode` (`@ReportPolymorphism`) in `WP_Hook::apply_filters`: the callee is too big to split
  (119 > SplittingMaxCalleeSize 100), so Truffle splits the caller instead, `apply_filters` at each `apply_filters()`
  call site: 824 copies, 1107 compilations, 98 s, 11.5 MB of code.
- `ToBooleanNode`'s reports (the bytecode's `ToBool`, 305 splits) and `ToStrNode`'s (`CastString`, 222: esc_attr
  134, esc_html 45).

**Tried:** both reports off (`target/split-off.patch` locally; the diff drops `@ReportPolymorphism` from the two
classes). Diag: splits 1986 → 1062 (CallableDispatch off) → 445 (both), compilations 7975 → 6870 → 6344, settling
still at ~10-11 min (compile wall time within noise). Timed, 12 min warm-up, 2 rounds each, interleaved:

| variant | 1 user pages/s, CPU-ms/page | 8 users | 16 users | memory.peak |
|---|---|---|---|---|
| base | 13.3, 86 | 69.1 (61.4-76.7), 101 | 56.4 (53.3-59.5), 121 | 2021 MB |
| CallableDispatch off | 12.4, 103 | 55.8 (51.0-60.7), 125 | 51.6 (50.2-52.9), 132 | 1996 MB |
| both off | 13.6, 68 | 60.1 (55.4-64.8), 115 | 56.4 (56.2-56.7), 120 | 2056 MB |

The fewer compilations don't buy throughput or memory: the copies of `apply_filters` keep each hook's callback
dispatch monomorphic, so the steady state needs them, and the volume costs only during the warm-up. Not committed.

**What's left:** the warm-up's cost, not the steady state's: a split decided by the call site's hook name instead of
the dispatch's polymorphism, or a cheaper copy (the dispatch's cache alone, not all of `apply_filters`), and the
compiler's budget (thresholds, threads: issue 089's family). The 8-user range (61-77 pages/s for one build) says a
12-minute warm-up is the minimum; a decision on a few % needs more rounds.

**Test:** the measurements (`native.sh diag`, `native.sh load` with `WARM=720s`).
