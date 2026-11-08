# Roadmap after the correctness round (2026-11-03)

The order the owner set on 2026-11-03, with the reason for each step's place.

## 1. Merge the open correctness PRs
- **Merged:** #33 (objects and calls), #34 (lifetime, generators), #41 (split policy), and the cloud sessions'
  #36 (missing ext/standard functions), #37 (nullsafe, pipe, closures), #38 (wrong built-in output), #39 (types).
- **Open, in this order:** #40 (enums, readonly), #42 (property hooks, lazy objects), #35 (compile-time checks,
  `$$name`, built-in arity, warnings).
- **Process:** cloud PRs are merged as they come, without a local check per PR. Each branch merges `origin/main`
  again after every merge, keeping both sides' fixes. The one performance check is step 3.
- **Each pair of these PRs conflicts.** Several sessions rewrote the same built-ins and runtime classes
  (`CoreFunctions`, `Dump`, `PhpMain`, `PropNode`, `Translator`, `PhpClass`). #40 and #42 overlap the most, so
  they go last among the cloud PRs.

## 2. Bytecode only ([D16](decisions.md))
- Move file top-level code (`FileRootNode`), `ThunkRootNode` and `eval` to bytecode, with `RunOnceCode` and
  `DefinedNode` (PR #26's rules).
- Close the remaining `throw new Unsupported` sites in `BytecodeTranslator` (49 on 2026-11-03, some of them
  compile errors or dead cases).
- Delete `parser/Translator.java`'s body translation, the tree-only nodes, the `php.Interpreter` option and the
  dual-mode tests.

**Why here:** everything after this touches every store path. On two interpreters it would be done twice.

## 3. One performance check against the pre-batch main
Measure Gate 1, Gate 2 (warm, cold, CPU per thread class, compile times) and WooCommerce, with memory.peak. Use
CPUs 14–21, interleaved, reversing the order each round.

- **The baseline is `c7cd6dc`**, main before #26, #32 and the correctness batch, none of which was measured.
- **Unmeasured changes to watch:** #33's visibility checks, #34's PCRE /u cache, #39's type checks on every call,
  #42's hook and lazy checks on property access.
- **On a regression:** bisect by PR.

## 4. Performance that doesn't touch the store paths
- The tree's `Intrinsics` on bytecode (most of Gate 1's `hooks` and `kses` gap).
- The callee as a PE constant at `SendVar` ([bytecode-dsl.md](bytecode-dsl.md), "What a bytecode compilation
  costs", proposal 2).
- The steady-state gaps in `escape`, `slugs`, `blocks` and `wpautop`.
- The cold visit (10.9 s against the tree's 5.7 s) and libgraal's compile bursts behind memory.peak.

## 5. Destructors ([D17](decisions.md), [destructors.md](destructors.md))
1. A counting-only prototype and its A/B against thresholds.
2. The full design: release cascades, the cycle collector, the shutdown order, object handles reused like
   php-src's, ordered globals.
3. A census oracle in test mode.

The same work closes `tla-cow.md`'s F3 and F4.

**Status 2026-11-08:**
- Step 1 is built on `destructors-merged` (1183d27) with destructors at zero behind a flag. Its A/B exceeds every
  threshold (destructors.md, work order item 7); a re-measure on a quiet machine is pending.
- Step 3: the reachability check at zero, the count audit (in-degree against count) and the static release diff
  against php-src's opcodes are built; the audit's WordPress findings are in issue 036, item 8.
- The work order is in [destructors.md](destructors.md#status-2026-11-06).

## 6. Store-path tuning, with refcounts in place
Ownership moves instead of increment/decrement pairs, and borrowed read-only parameters (destructors.md, P2).

**Why after 5:** the reference counts rebuild exactly these paths (variable, property and element stores, function
epilogues). Tuning them earlier would be thrown away, and measuring them together would hide what destructors cost.

## 7. Native image
Native image removes the HotSpot compilation of the interpreter itself from the cold visit (H9/H10: cold code
2.8× the tree on the JVM, 1.11× natively). Steady state on native was 0.59× the JVM on Gate 1 (PR #6). That gap is
measured again here.

**GraalVM 25.5** ([graalvm-25-5.md](graalvm-25-5.md)) adds two things for this step. Its auxiliary engine cache
(now in Community Edition) with Native Image's auxiliary images lets a training run store bytecode, compiled code
and a pre-initialized context, so the cold visit starts warm. It also adds an unboxed calling convention for
compiled Truffle-to-Truffle calls. The upgrade to 25.5 goes first, as its own PR; a probe of the cache comes before
this step.
