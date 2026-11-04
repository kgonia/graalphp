# `continueAt`: the bytecode interpreter's own warm-up on the JVM

This page gathers what we know about `continueAt`. The measurements are in [cold-visit.md](cold-visit.md) and
[bytecode-dsl.md](bytecode-dsl.md) (H8-H11); this page explains them and records what follows from them.

## What it is
The Bytecode DSL generates one Java method from `PhpBytecodeRootNode`, `CachedBytecodeNode.continueAt`, plus the
part it splits off, `continueAt_1`. It is the interpreter loop:

```java
Object continueAt(Frame frame, int bci) {
  while (true) {
    switch (bytecodes[bci]) {
      case LOAD_LOCAL: ...; bci += 2; break;
      case ADD:        ...; bci += 1; break;
      case CALL:       ...
      // a few hundred PHP operations, most in several quickened variants
    }
  }
}
```

One method runs every PHP function that Truffle hasn't compiled yet, so on a cold visit it runs almost all of
WordPress's code. It is 127 KB of Java bytecode. Truffle-compiled PHP doesn't go through it: partial evaluation
turns each hot PHP function into its own machine code. So `continueAt` matters for cold and lukewarm code only,
not for the steady state.

## What the JVM does with it
`continueAt` is ordinary Java, so HotSpot compiles it the way it compiles any method, in tiers:

| tier | compiler | what it is |
|---|---|---|
| 0 | HotSpot's interpreter | runs the Java bytecode of `continueAt` itself |
| 3 | C1 | quick compile with full profiling counters: slow code |
| 4 | Graal (libgraal) | optimized from the profile, speculative |

Tier 4 speculates. A branch the profile never saw (a `switch` case for an operation WordPress hasn't run yet, a
type that hasn't come by) isn't compiled; Graal puts an uncommon trap there (`RemoveNeverExecutedCode`). Graal also
assumes the class hierarchy it sees: a class with one loaded subclass is devirtualized to it.

A cold visit breaks both assumptions all the time, so the method goes round this loop (round 1 of the diagnosis,
cold-visit.md, "Why the bytecode's execution is slow"):

```
1.2 s   tier 3 (C1)
1.3 s   tier 4, 617 ms to compile  -> uncommon trap at 2.0 s (an operation never profiled) -> back to tier 3
2.1 s   tier 4, 1,026 ms           -> not installed: IndirectCallNode$1 loaded meanwhile ("dependencies failed")
3.3 s   tier 4, 1,869 ms           -> not installed
5.2 s   tier 4, 2,048 ms           -> uncommon trap at 7.4 s
7.6 s   tier 4, 2,459 ms           -> installed at 10.2 s, as the visit ends
```

- The optimized `continueAt` was installed for **0.14-4.3 s of a 9-15 s cold visit**. For the rest of the visit, PHP ran
  on C1's profiling tier 3.
- Each tier-4 compilation of the 127 KB method takes 0.6-3.5 s on a loaded host, and the cold visit has time for only a
  handful, so the method never settles before the visit ends.
- HotSpot's compilers spend 25 CPU-s on the bytecode's cold visit against the tree's 10.
- `continueAt_1` does the same on a smaller scale: 15 tier-4 compilations of 150-320 ms and 10 deoptimizations.
- HotSpot's own interpreter (tier 0) runs `continueAt` for only about 1% of the execution samples. bytecode-dsl.md's H10
  named that tier as the cost ("the host compiles the 127 KB `continueAt` in 336 ms"), but that was the first C1
  compile and not where the time goes.

HotSpot stops speculating at a bytecode index after enough traps there, so the method would settle eventually.
That takes longer than a cold visit, and every round of it costs a multi-second compilation.

## Why the tree didn't have this
The tree interpreter is thousands of small `execute` methods, one per node class. HotSpot compiles them
separately, in milliseconds each. A new node kind or an unseen branch deoptimizes one small method, and the rest
stays optimized. The host JIT also specializes those methods for the program's own nodes (H2). The bytecode puts
the whole interpreter into one method. That makes the interpreter smaller and its PHP compilations cheaper (the
reasons for D16), but on HotSpot it turns every unseen branch into a recompilation of the whole interpreter.

## Is it a bug?
No. Speculation with uncommon traps and class-hierarchy assumptions is how HotSpot and Graal compile every Java
method; it costs this much here because the method is 127 KB and a cold visit is exactly code reaching branches for
the first time. It affects every Bytecode DSL language on HotSpot, not only ours. The Truffle team's answer is
native image (below).

**What could be worth taking upstream, and only with numbers:** if `RemoveNeverExecutedCode=false` holds over full
rounds (now 0.88 of the cold visit in 2 rounds), the proposal would be that the DSL's generated interpreter method
(`@BytecodeInterpreterSwitch`) is compiled by the host without unreached-code speculation. Today the option applies
to every host compilation of the JVM, though only this method needs it. Before writing anything we would check
Truffle's master for the same idea. Nothing has been reported; an upstream issue is an outward-facing step that
needs the owner's consent.

## How GraalPy lives with it
GraalPy is the Truffle team's own large Bytecode DSL language. Its `PBytecodeDSLRootNode` (source as of
2026-11-03) uses:

```java
@GenerateBytecode(
    enableUncachedInterpreter = true, defaultUncachedThreshold = "4",
    enableTailCallHandlers = true,
    enableSerialization = true,
    boxingEliminationTypes = {int.class},
    storeBytecodeIndexInFrame = true, ...)
```

| what GraalPy does | effect on `continueAt` | for us |
|---|---|---|
| ships native image as its default `graalpy` (the JVM build is the separate `graalpy-jvm`, documented as warming up slower) | the interpreter is compiled once, ahead of time, without speculation: no recompilation | roadmap step 7 |
| tail-call handlers | a faster interpreter, on native image only | H8/feature 7: 1.32x on native, neutral on the JVM; already on for native |
| the uncached interpreter for a function's first 4 runs | a separate, simpler loop without profiles, so code run a few times stays out of the cached `continueAt` | H11 measured `uncached=16` as slower on cold code by time (3.03 against 2.80x the tree). It wasn't measured for its effect on `continueAt`'s recompilations; worth one more run |
| bytecode serialization (like `.pyc`) | skips parsing and translation on later starts; doesn't touch `continueAt` | parse plus translation are about 2.5 s of 10.9; not measured |

GraalPy's source has no flag, cache or preload aimed at `continueAt`'s recompilation on HotSpot. It doesn't solve the
problem; it ships native image.

## What helps, measured so far
All numbers are the cold visit as a share of bytecode-only's (cold-visit.md, "The JDK 25 AOT cache" and "Two cheap
remedies"). The host was loaded during these runs; the rounds are few where noted.

| remedy | how it acts on `continueAt` | cold visit | status |
|---|---|---|---|
| JDK 25 AOT cache (JEP 483, 515) | classes loaded and linked at start (no mid-visit dependency failures); profiles from a training run, so tier 2 runs between tier-4 versions instead of tier 3; the first tier-4 compile starts at 1.1 s. The traps stay | **0.58** per round (median of 5; the medians' ratio is 8.20 / 10.89 s = 0.75); memory.peak after the cold visit 1,312 against 848 MB, level after 6 visits; 66 MB cache, trained again per build (65 s) | works with `--add-modules=jdk.internal.vm.ci` on both command lines; without it the cache silently holds no profiles |
| `-Djdk.graal.RemoveNeverExecutedCode=false` | no unreached-code traps: `continueAt` deoptimized once instead of 10 times, the whole JVM 81 instead of 345 | **0.88** (0.85-0.90), 2 rounds | needs 5+ rounds and a warm-visit check: it changes every host compilation |
| preloading `IndirectCallNode$1` | removes the "dependencies failed" installs | 1.00, 5 rounds | not committed: the traps alone keep the cost |
| the option plus the preload | both | 0.74, 1 round | to repeat |
| the option plus the AOT cache | both | 0.61, 2 rounds | no gain over the cache alone |
| a smaller `continueAt` (more of it split off) | each recompilation is cheaper | not measured | an idea |
| native image | no host JIT, so no recompilation | the native bytecode's cold pass is 0.35 of the JVM tree's (H9) | roadmap step 7 |

## What we do next
In the order of [cold-visit.md](cold-visit.md)'s "What next", within roadmap step 4:
1. `RemoveNeverExecutedCode=false`, with and without the preload, over 5+ rounds and 200 visits (warm CPU,
   memory.peak).
2. The AOT cache over 200 visits; if it holds, a training step per build and the two flags in the launcher.
3. The uncached interpreter again, counting `continueAt`'s tier-4 compilations and deoptimizations, not only time.
4. Upstream only if 1 holds and Truffle's master has no answer, with the owner's consent.
Native image (step 7) remains the main way out, as it is for GraalPy.
