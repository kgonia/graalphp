# WordPress's cold visit: where the bytecode interpreter's first visit goes

## Question
Gate 2's cold visit (the six pages of the first visit after the server starts) took 10.9 s on the bytecode
interpreter on a clean host, against the tree interpreter's 5.7 s ([bytecode-dsl.md](bytecode-dsl.md), "What a
bytecode compilation costs"). The known suspects were the generated interpreter's 127 KB `continueAt` (run by
HotSpot's own interpreter until compiled), translation (about 3 times the tree's), and the host JIT's per-program
specialization of the tree. This page measures the phases, finds what costs the time, tries the JDK 25 AOT cache,
and fixes what is cheap.

**Short answer (2026-11-04).**
- **Where the bytecode's cold visit goes:** parse 13%, translation 9%, includes and native calls 2%, execution 76%.
  Against the tree (6.96 s with the diagnosis on, 5.72 s without), the extra 5 s are almost all execution, spent in
  the interpreter's host-compiled Java, and 15 more CPU-s on HotSpot's compiler threads.
- **Why:** libgraal compiles the generated interpreter's 127 KB `continueAt` again and again (0.6-3.5 s each):
  each tier-4 version is thrown away by an "unreached" uncommon trap, or fails to install because a class
  (`IndirectCallNode$1`) loaded mid-visit, so the visit runs `continueAt` mostly as C1's profiling tier 3. HotSpot's
  own interpreter runs `continueAt` for only about 1% of the samples; translation is a tenth of the gap.
- **The JDK 25 AOT cache works with libgraal** once `--add-modules=jdk.internal.vm.ci` is on both command lines
  (otherwise it silently keeps no linked classes and no profiles): the bytecode's cold visit falls to 0.58 of itself
  (8.20 against 10.89 s, 5 rounds), memory.peak after the cold visit rises from 848 to 1,312 MB, the cache is 66 MB
  and has to be trained again for every build. It doesn't reach the tree (5.72 s), which gains from its own cache too.
- **The benchmark cores aren't pinned to host CPUs:** the VM's vCPUs float, Windows puts busy ones on the 8 P-cores,
  and host LPs 14-21 are E-cores. Our "on 14-21" isolation is the VM's only.
- **Fixes:** loading `IndirectCallNode$1` up front removes the failed installs but not the slowdown (1.00, 5
  rounds; not committed); `-Djdk.graal.RemoveNeverExecutedCode=false` gave 0.85-0.90 in 2 rounds (needs more).
- main's default against bytecode-only: 0.98 timed, 1.13 in diagnosis; the step-2 verification's 8% isn't
  reproduced. memory.peak's excess over RssAnon is a 1-2 s compilation burst, as before.

## The benchmark cores: a container's cpuset is not a set of host CPUs
The benchmarks run in Docker inside the WSL2 VM (`docker-desktop`), pinned with `--cpuset-cpus 14-21`. That pins
to the VM's virtual CPUs 14-21, not to the host's logical processors 14-21.

**The host's CPUs.** The Core Ultra 9 275HX has 8 P-cores and 16 E-cores, no SMT. Windows numbers the P-cores 0, 1,
10-13, 22 and 23 (efficiency class 1 in `GetSystemCpuSetInformation`, `bench/cold-visit/cpusets.ps1`); **LPs 14-21
are all E-cores**.

**The check** (`bench/cold-visit/runs.sh burn`, under the CPU lock, 2026-11-04 08:06): a container running 8 busy
loops pinned to 14-21, then 4 pinned to 14-17, 25 s each; the VM's per-vCPU load from `/proc/stat`
(`vmload.sh`, in a container on 6-13) beside the host's per-LP load (`lpload.ps1`, Windows' processor counters):

| burner | VM: vCPUs 14-21 (14-17) | host: LPs 14-21 | host: the 8 P-cores | host: the 16 E-cores |
|---|---|---|---|---|
| 8 loops on 14-21 | 100% each | 17% | **100%** | 20% |
| 4 loops on 14-17 | 100% each | 8% (LPs 14-17: 5%) | 61% | 11% |

Before the check, another session's WooCommerce load test (pinned to 14-21, the VM's vCPUs 14-21 at 95-98%) showed
the same: host LPs 14-21 at 11-22%, the P-cores at 88-99%, and when the load test paused, the P-cores dropped.

- **The vCPUs float.** Hyper-V runs each vCPU as a thread of `vmmemWSL`, and Windows schedules those threads on any
  LP. The thread director puts busy ones on the P-cores first. A busy vCPU 14 runs on whatever P-core (or E-core)
  is free; nothing ties it to LP 14.
- **What our numbers mean.** A run on 14-21 gets the P-cores when nothing else wants them, and E-cores (slower per thread,
  at a lower clock; not measured here) as soon as anything else on the host is busy: other containers on
  6-13 or 22-23 (builds, databases, clients, other sessions' servers) and Windows processes alike, because they all
  compete for the same 8 P-cores. Keeping other work "off 14-21" separates nothing physically. A loaded host slows a
  timed run whatever cores the load is pinned to.
- **The Windows-side mask makes it worse.** `start /affinity C03FFF` (LPs 0-13, 22, 23) confines Windows processes
  to the 8 P-cores plus E-cores 2-9: exactly the cores the VM's busy vCPUs want.
- **The CPU lock still matters** (it keeps two timed servers from running at once), and interleaved rounds keep the
  comparisons fair on average; but the spread between rounds follows the whole host's load, which is why
  `hostload.ps1`'s "on 14-21" figure never explained a slow run. `bench/cold-visit/lpload.ps1` now logs the P-cores'
  and the E-cores' load beside every run.

**What could fix it (the owner's settings; nothing was changed):**
1. Pin the other Windows work to E-cores instead (`start /affinity 3FC3FC`: LPs 2-9 and 14-21), so the P-cores stay
   for the VM. It doesn't pin a vCPU, but it removes the largest competitor.
2. There is no per-vCPU affinity for the WSL VM: WSL's `.wslconfig` has `processors=` (a count) but no mask, and the
   WSL VM is not a Hyper-V Manager VM, so `Set-VMProcessor` doesn't apply. `vmmemWSL` is a protected process; its
   affinity can't even be read (`ProcessorAffinity` returns nothing).
3. Run timed servers as Windows processes with `start /affinity` on chosen LPs. That pins truly, but it changes the
   OS under the JVM (and the native libraries: PCRE2, libcurl), so its numbers would not compare with the Linux ones.
4. For our own A/Bs: gate a run on the host's P-core load (`lpload` now records it) rather than on "load on 14-21",
   and keep rounds interleaved.

## The phases (2026-11-04, CPUs 14-21, interleaved, the order reversed every other round)
Builds: this branch (`bc`, `bytecode-only` at `bde2fcd`, two commits past #44's `31719de`: compile errors and the
wiki), `main` at `9ae85cb` with its default (`main`: bytecode functions, the tree's file top levels, constant
expressions and `eval`) and with `php.Interpreter=tree` (`tree`). Each run is a server just started on CPUs 14-21
(the CPU lock held), a database just reset, the cold visit and 5 more visits 1 s apart (tests/gate2/native.sh's
`measure_run`). The diagnosis variants (`treed`, `maind`, `bcd`) add the phase counters (`php.phases`), a JFR
recording with 1 ms execution samples and every host compilation, and TraceCompilation; they ran in rounds 1-3
beside the timed variants. `bench/cold-visit/phase_table.py` joins the counters (on the requests' thread, wall time)
with JfrCold's split of the execution samples (scaled to the execution's on-CPU time) and the threads' CPU.

| s, cold visit (medians of 3 rounds, range) | tree | main's default | bytecode-only |
|---|---|---|---|
| **cold visit wait** | **6.96** (6.53-8.59) | **13.02** (12.71-13.18) | **11.70** (9.22-14.91) |
| parse: file read, file parse, body read | 1.42 (1.31-2.03), 20% | 1.48 (1.42-1.56), 11% | 1.49 (1.03-1.76), 13% |
| translate: file top levels and bodies | 0.69 (0.62-0.86), 10% | 1.28 (1.21-1.32), 10% | 1.07 (0.90-1.55), 9% |
| includes, native calls, the context | 0.23, 3% | 0.25, 2% | 0.21, 2% |
| execution | 4.61 (4.36-5.38), 66% | 9.88 (9.70-10.29), 76% | 8.92 (7.09-11.28), 76% |
| - off-CPU: I/O, the database, waiting for a CPU | 0.70, 10% | 1.11, 9% | 1.07, 9% |
| - on-CPU, Java in HotSpot's interpreter | 0.74, 11% | 0.83, 6% | 0.77, 7% |
| - on-CPU, host-compiled Java (the interpreter, built-ins, runtime) | 2.42, 35% | 7.29, 56% | 6.46, 55% |
| - on-CPU, Truffle-compiled PHP and what it calls | 0.77, 11% | 0.67, 5% | 0.60, 5% |
| execution samples in `continueAt` / of them in HotSpot's interpreter | - | 45% / 1.4% | 44% / 1.1% |
| GC pauses (inside the lines above) | 0.09 | 0.10 | 0.19 |
| requests' thread CPU | 6.24 | 11.90 | 10.62 |
| off the thread, CPU-s: HotSpot's compilers (the JVM's own Java) | 10.4 | 28.9 | 25.1 |
| off the thread, CPU-s: Truffle's compilers (PHP) | 14.6 | 30.3 | 27.2 |
| off the thread, CPU-s: GC and VM threads | 0.4 | 0.5 | 1.7 |
| process CPU-s | 37.7 | 73.3 | 65.3 |
| host compilations on compiler threads, s (JFR) | 15.4 | 30.1 | 26.1 |
| s of the visit with a tier-4 `continueAt` installed | - | 0.29 (0.12-3.30) | 3.75 (0.14-4.34) |
| memory.peak after the cold visit, MB | 944 | 1,236 | 903 |

The timed variants, without the counters and JFR (5 rounds; `report.py`):

| | cold home page (from the server's start) | cold visit wait | cold visit CPU-s | ÷ bytecode-only's wait, per round | memory.peak after the cold visit |
|---|---|---|---|---|---|
| tree | 4.33 s (3.27-5.96) | **5.72 s** (3.90-7.86) | 30.2 | 0.48 (0.36-0.59) | 891 MB |
| main's default (rounds 4-5) | 7.23 s | 11.20 s (10.76-11.64) | 61.8 | 0.98 (0.97-1.00) | 1,139 MB |
| bytecode-only | 6.61 s (5.84-12.28) | **10.89 s** (9.81-21.07) | 61.6 | 1 | 848 MB |

- **The bytecode's extra time is execution, and its CPU is the interpreter's host-compiled Java**: 6.5 s against
  the tree's 2.4 s, while HotSpot's compilers spend 25-29 CPU-s on the JVM's own Java against the tree's 10. The next
  section is why.
- **Parse is the same** on all three (1.4-1.5 s): the same parser.
- **Translation is 9-10% of the visit** on every variant; on the bytecode it takes 1.1-1.3 s against the tree's 0.7
  (1.6-1.9 times), not the 3 times of bench/cold. It is a tenth of the gap.
- **HotSpot's interpreter running `continueAt` is about 1%** of the execution samples: not a phase worth a fix.
- **I/O and the database** are part of the off-CPU 0.7-1.1 s (with waiting for a CPU: the request thread shares 8
  vCPUs with up to 20 busy compiler threads); a warm visit's wall minus its CPU, mostly the database, is about 0.05 s
  a visit ([visit-phases.md](visit-phases.md)).
- **The JFR and the counters cost** about 7% on the bytecode (11.70 against 10.89 s) and 20% on the tree (6.96
  against 5.72), on different rounds' hosts; the shares are what this table is for.
- **The rounds ran on a loaded host**: other sessions' timed runs alternated with these on the CPU lock all day, and
  their untimed work ran beside. The host's P-cores were 24-96% busy during a run (our own server included); round 2's
  bytecode run (21.07 s, P-cores 96%) is the outlier. A Docker Desktop restart at 11:57 (all containers exited)
  ended one attempt; its runs were redone.

## Why the bytecode's execution is slow: its host-compiled interpreter keeps being thrown away
The JFR samples rule out the suspect named first: in the cold visit only about 1% of the execution samples are in
`continueAt` run by HotSpot's bytecode interpreter. HotSpot compiles `continueAt` within 1.2-2 s of the start (C1,
tier 3), and the request thread spends 40-45% of its execution samples in host-compiled `continueAt` and
`continueAt_1` (the part the DSL splits off). What it runs, most of the visit, is that **tier-3 code**: C1's,
with full profiling. The optimized version (tier 4, libgraal) is installed for 0.14-4.34 s of the 9.2-14.9 s of the
three diagnosis rounds (median 3.75 s of 11.7; JfrCold's "tier-4 continueAt installed"). Round 1 (`bcd-1`):

| s after the JVM's start | what happens to `CachedBytecodeNode.continueAt` (`bcd-1`) |
|---|---|
| 1.21 | tier 3 (C1) compiled, 65 KB |
| 1.34-1.96 | tier 4 (libgraal) compiled in 617 ms, 282 KB; deoptimized at 1.96 (`null_assert_or_unreached0`, an instruction the profile never saw) |
| 2.09-3.12 | tier 4 again, 1,026 ms: **not installed** |
| 3.31-5.18 | tier 4 again, 1,869 ms: **not installed** |
| 5.22-7.27 | tier 4 again, 2,048 ms, 594 KB; deoptimized at 7.40 (unreached) |
| 7.55-10.0 | tier 4 again, 2,459 ms, 602 KB: installed at the visit's end (10.15) |

`continueAt_1` went the same way: 15 tier-4 compilations of 150-320 ms each, 10 deoptimizations. Host compilation
took 21 CPU-s of the cold visit on bytecode, against the tree's 9.8 (JFR's `jdk.Compilation`), most of the
difference being these recompilations.

**Why a compilation isn't installed.** `-XX:+PrintCompilation` names both causes (a probe on CPUs 6-13, not timed):
- `COMPILE SKIPPED: Code installation failed: dependencies failed`. `-Xlog:dependencies=debug` names the dependency:
  `abstract_with_unique_concrete_subtype`, context `IndirectCallNode`, class `OptimizedIndirectCallNode`, witness
  `IndirectCallNode$1`. Until the uncached `IndirectCallNode` (an anonymous subclass made by the first
  `IndirectCallNode.getUncached()`, on an uncached call's path) is loaded, `OptimizedIndirectCallNode` is the only
  subclass, and libgraal devirtualizes on it. When the class loads, mid-visit, every compilation that assumed it is
  thrown away: in the probe it invalidated the installed tier-4 `continueAt` and `continueAt_1` at 5.56 s and failed
  the next `continueAt` compilation (started before the load, finished after it).
- `COMPILE SKIPPED: Speculation failed: UnreachedCode`: the compilation used a speculation that failed while it was
  compiling.
- And after an install, `made not entrant: uncommon trap`: the code reached a branch its profile had never seen
  (Graal's `RemoveNeverExecutedCode`), deoptimized and went back to tier 3 to profile again.

A cold visit is exactly the code that keeps reaching instructions and branches for the first time, and every
tier-4 compilation of a 127 KB method takes 0.6-3.5 s on a loaded host. The tree doesn't suffer this: its
`execute` methods are small, each compiles in milliseconds, and a deoptimization costs one small method.

**What that leaves the other suspects:**
- HotSpot's interpreter running `continueAt`: about 1% of the execution samples (H10's 336 ms compile was the first
  C1 compile; it isn't the cost).
- Translation: 10% of the visit on both, twice the tree's time on bytecode (below).
- The tree's per-program specialization: the tree's host-compiled `execute` methods aren't thrown away.

## The JDK 25 AOT cache ("PGO for the JVM")
Leyden's AOT cache keeps the classes a training run loaded, already parsed and linked (JEP 483), and the method
profiles it collected (JEP 515), so a new JVM starts with them. JDK 25 has no AOT-compiled code in the cache.

**Does it work with GraalVM 25.4 and libgraal?** Yes, with one trap.
- `-XX:AOTCacheOutput=FILE` (one step: train, then the JVM writes the cache as it exits) works on Oracle GraalVM
  25.4.4.1.1 with libgraal as the top-tier compiler. The class path must list jars: classes from a directory
  (`target/classes`) aren't cached, so the variants run from the build's jar (`target/app-*.jar`).
- **The trap: JVMCI's module.** JVMCI adds `jdk.internal.vm.ci` to the boot layer by itself, but the cache-creating
  step (`-XX:AOTMode=create`, forked at exit) doesn't, so it logs `Mismatched values for property
  jdk.module.addmods: jdk.internal.vm.ci specified during dump time but not during runtime`, disables the full
  module graph, and with it AOT linking: the cache then holds 5,434 classes, **none AOT-linked, no method profiles
  (`MethodTrainingData = 0`)**, and is barely more than AppCDS. Naming the module on the command line, in the
  training run and in every run that uses the cache (`--add-modules=jdk.internal.vm.ci`), fixes it: 6,303 classes,
  6,301 AOT-linked, 11,315 methods' training data, 15,398 method counters, the full module graph on
  (`Using AOT-linked classes: true` at start). The variants `bcm`/`treem` are that cache.
- **The cache is tied to the build.** The JVM checks the jars' modification times and sizes: a cache made with one
  jar is refused for another (`shared class paths mismatch`, then the JVM runs without it). It must be trained again
  for every build, and for every change of the JVM's options that the cache records (GC, compressed oops, modules).
- **Cost:** the training run is the cold visit and 20 visits (65 s), then 2-3 s to write the cache at exit. The
  cache is 66 MB for the bytecode (`bc-m.aot`, 66,019,328 bytes) and 60 MB for the tree (`tree-m.aot`, 59,699,200
  bytes); the broken one without the module was 46 MB.

**The cold visit with and without it** (the same rounds as the phases; `bcm` is the bytecode from the jar with its
cache, `bcj` the same jar without one, `bc` the class directory; memory.peak is the cgroup's):

| | rounds | cold home page | cold visit wait | ÷ `bc`'s, per round | cold visit CPU-s | memory.peak at start / after the cold visit / after 6 visits |
|---|---|---|---|---|---|---|
| `bc` (class directory) | 5 | 6.61 s | 10.89 s (9.81-21.07) | 1 | 61.6 | 119 / 848 / 1,368 MB |
| `bcj` (jar, no cache) | 2 | 7.05 s | 11.38 s (10.22-12.54) | 1.00 (0.85-1.16) | 64.2 | 119 / 915 / 1,183 MB |
| **`bcm` (jar and cache)** | 5 | **4.59 s** | **8.20 s** (6.34-8.92) | **0.58** (0.42-0.91) | 47.3 | 180 / **1,312** / 1,336 MB |
| `tree` | 5 | 4.33 s | 5.72 s (3.90-7.86) | 0.48 | 30.2 | 120 / 891 / 1,087 MB |
| `treem` (tree, jar and cache) | 1 | 2.94 s | 4.00 s | 0.33 (`tree`: 0.48 in that round) | 22.9 | 125 / 1,095 / 1,210 MB |

- **It works, and it is the largest cold-visit gain found here:** the bytecode's cold visit falls to 0.58 of
  itself per round (0.65 in both rounds against the same jar without the cache), its first page to 0.69, its CPU
  by a quarter, and the next visits too (median early visit 1.10 against 1.85 s). The class path from a jar instead
  of the directory changes nothing by itself (`bcj` against `bc`: 1.00).
- **It doesn't close the gap to the tree:** 8.20 s against 5.72; and the tree gains as much from its own cache
  (4.00 against 5.72 s in the one round both ran).
- **It costs memory early:** the cache adds 60 MB at start (mapped), and memory.peak after the cold visit is 1,312
  against 848 MB. After six visits the two are level (1,336 against 1,368 MB): the cache brings the compilation
  burst forward, it doesn't make it bigger.
- **Why it helps** (a probe with `-XX:+PrintCompilation` on CPUs 6-13, not timed): with the cache the first tier-4
  `continueAt` compilation starts at 1.1 s instead of 1.8-2.1, no compilation fails on a dependency (the cache's
  classes, `IndirectCallNode$1` among them, are loaded and linked at start, so none loads mid-visit), and between
  tier-4 versions `continueAt` runs as C1's tier 2 (no profiling: the profile came with the cache) instead of tier
  3. The thrash itself stays: the tier-4 code still hit uncommon traps at 3.2 and 7.1 s and was compiled again.
  Every other method of the JVM's own code starts the same way, linked and profiled.

## Two cheap remedies, measured
**1. Loading the uncached `IndirectCallNode` up front** (a static initializer in `PhpBytecodeRootNode` calling
`IndirectCallNode.getUncached()`; `bcp`, a build of its own; not committed, the diff is
`target/cv/indirectcall-preload.diff` in the worktree). A probe confirmed that no `continueAt` compilation fails on
a dependency any more, but the uncommon traps remain, and the cold visit didn't move: **1.00 (0.61-1.23) of
`bc`'s, 5 rounds** (11.51 against 10.89 s). Not a fix by the numbers, so not committed.

**2. `-Djdk.graal.RemoveNeverExecutedCode=false`** (a libgraal option: keep code the profile never saw run instead of
an "unreached" trap; it applies to every host compilation). A probe showed 81 deoptimizations in the cold visit
instead of 345, and 1 of `continueAt` instead of 10; traps of other kinds (a null check, a missing exception
handler) and the dependency failure remain.

| per round, ÷ `bc`'s cold visit | rounds | |
|---|---|---|
| `bcn` (the option) | 2 (6, 7) | **0.88** (0.85-0.90) |
| `bcjn` / `bcj` (the option, from the jar) | 1 (4) | 0.90 |
| `bcpn` (the option and the preload) | 1 (4) | 0.74 |
| `bcmn` (the option and the AOT cache) | 2 (4, 6) | 0.61 (0.60-0.62); `bcm` alone 0.58 over 5 rounds |

Too few rounds to call (the coordinator ended the session before rounds 7-8 finished), and its effect on warm
visits, compiled code size and memory isn't measured: it changes every host compilation, not only the
interpreter's. The direction is a 10-15% faster cold visit; with the AOT cache it adds nothing visible.

## Translation (step 3): not the large share
Translation is 9-10% of the cold visit on every variant (1.07 s on bytecode-only, 0.69 s on the tree), so it is
a tenth of the gap, not its cause. Its profile has no hot spot to fix cheaply:
- **In the cold visit** (JFR, `bcd`): 9% of the requests' samples; the largest self entries are 0.3% each
  (`Ast.get`/`Ast.is`, the DSL builder's `beforeChild`/`afterChild`, `PhpLanguage.intern`), the rest spread over
  the translator and the builder.
- **Translation alone** (`bench/cold-visit/translate.sh`: every body of wp-includes and wp-admin, 1,422 files,
  translated cold with `truffle_translate`, 4.7 s): 41% of the samples parse, 41% translate. The largest self
  entries, as shares of translation: the AST's string-keyed maps (`Ast.get`, `Ast.is`) 9%, `PhpLanguage.intern`'s
  `ConcurrentHashMap.putIfAbsent` 4.5% (already lock-free when the literal is its bin's first node, so a `get` first
  would save part of that), the builder's `afterChild`/`beforeChild` 4%, `collectVariables` 2.3%,
  `Translator.containsYield` (a walk of each body) 2.3%. The `failState > String.format` entries are JFR placing
  compiled code at the nearest debug info, not real failures (no translation failed).
- So no fix here: the AST's maps are the parser's representation (not a cheap change), and each cheap candidate is
  a few percent of translation, about 0.05 s or 0.4% of the visit, below what these rounds can measure.

## main against bytecode-only (the step-2 verification's 8%)
The verification measured bytecode-only's cold visit 1.08 times main's (3 rounds). These rounds don't reproduce it:
main's default against bytecode-only is 0.98 (0.97-1.00) on the timed runs (rounds 4-5) and 1.13 (0.87-1.38) on the
diagnosis runs (rounds 1-3); bytecode-only's own spread is 9.2-14.9 s. The phase the top-level port moves is the
file translation: bytecode-only translates a file's top level to bytecode, main to the tree:

| s, cold visit | round 1 | round 2 | round 3 |
|---|---|---|---|
| file translation, main | 0.25 | 0.24 | 0.25 |
| file translation, bytecode-only | 0.25 | 0.33 | 0.44 |
| body translation, main / bytecode-only | 1.03 / 0.65 | 0.96 / 0.74 | 1.07 / 1.11 |
| execution, main / bytecode-only | 9.70 / 7.09 | 10.29 / 8.92 | 9.88 / 11.28 |

- The top level's translation costs up to 0.2 s more on bytecode (about 2% of the visit), and running it (once per
  request, interpreted) is inside execution, which on both is dominated by the interpreter's recompilations (the
  tier-4 `continueAt` installed for 0.12-3.30 s of main's visits and 0.14-4.34 s of bytecode-only's). An 8%
  difference is inside that noise; if it is real, it sits in execution, not in parse or translation.

## memory.peak against RssAnon (the step-2 verification's warm 1.61 times main)
memory.peak is the cgroup's highest memory.current; RssAnon at the three points visits.py reads it is a sample.
One server of each (`runs.sh memdiag`, CPUs 6-13, the cold visit and 60 visits, memwatch.py every 0.1 s, libgraal's
GC log, TraceCompilation; `memattr.py`):

| | memory.peak | the largest anon / RssAnon (1 s samples) / file | anon within 5% of its maximum | anon p90 | RssAnon at the end | the anon maximum |
|---|---|---|---|---|---|---|
| main | 1,635 MB | 1,627 / 1,606 / 0 MB | 0.9 s | 1,153 MB | 977 MB | 107 s, beside libgraal's largest isolate heap of the run (587 MB, 106.8 s) and a file root's compilation of 95,735 nodes |
| bytecode-only | 1,652 MB | 1,618 / 1,618 / 0 MB | 2.2 s | 1,356 MB | 895 MB | 67 s, as `wp-settings.php`'s top level (71,289 nodes, a bytecode root since the port) finished compiling |

- **The peak is the process's own anonymous memory** (file and kernel pages are 0-6 MB), for one to two seconds:
  a burst, not a plateau. RssAnon read before and after misses it, which is why the two disagree.
- **The burst is compilation**, as on the earlier rounds ([bytecode-dsl.md](bytecode-dsl.md), "Which splits pay",
  the patched processor): it lines up with the largest compilations, the file top levels among them. libgraal's
  log shows an isolate's heap only when it collects, so an isolate growing to its peak without a collection isn't
  seen; the timing is the evidence.
- **In this pair the two peaks are level** (1,652 against 1,635 MB). The verification's 2,287 and 2,361 MB on
  bytecode-only came in visits 1-25, when the queue is longest and several big compilations can overlap; one run
  of each can't say how often that happens. Bytecode-only's plateau (anon p90) is 200 MB higher.

## What next
Not done here, in the order I'd do them:
1. **More rounds of `-Djdk.graal.RemoveNeverExecutedCode=false`** (`bc` against `bcn`, at least 5 rounds, also over
   200 visits for warm CPU and memory.peak), and of `bcpn`: the two remedies together showed 0.74 in one round. If
   it holds, it is a launcher flag (HttpMain's JVM options), no PHP-visible change.
2. **Deploy the AOT cache** if the JVM stays the target: a training step per build (`runs.sh aot-train`, 65 s), the
   cache shipped beside the jar, `--add-modules=jdk.internal.vm.ci` in both the training and the serving command
   line. Measure the warm visits and memory with it over 200 visits (here only 6).
3. **Make the interpreter's host compilation settle.** The thrash is libgraal recompiling a 127 KB method because
   the cold visit keeps reaching new instructions and branches. Ways to try: preload every class the interpreter's
   compiled code depends on (the IndirectCallNode case, generalized: `-Xlog:dependencies=debug` lists them); a
   smaller `continueAt` (the DSL's split into `continueAt_1` is one knob) so each recompilation costs less; asking
   upstream (Truffle's Bytecode DSL) whether `@BytecodeInterpreterSwitch` methods should be compiled without
   unreached-code speculation.
4. **Native image (roadmap step 7)** removes all of it: no host JIT, so no recompilation; H9 measured the native
   bytecode's cold pass at 0.35 of the JVM tree's.
5. **The benchmark cores (owner's decision):** pin other Windows work to the E-cores (`3FC3FC`) instead of
   `C03FFF`, and gate timed runs on the host's P-core load.

Measured so far and not: the phases (3 diagnosis rounds of each), the timed cold visit (5 rounds of tree,
bytecode-only, the AOT cache and the preload; 2 of main's default and of the jar alone), the AOT cache's
feasibility, size and cost, the cpuset check, the memory.peak attribution (one server each). Not measured: warm
visits with the AOT cache or the option (each run here had only 5 visits after the cold one), the option over 5
rounds, the tree's cache over more than one round.

## Reproduce
```
# builds: target/classes-bc (this branch), target/classes-tree (main's 9ae85cb), target/app-*.jar (mvn package's jar)
bash bench/cold-visit/runs.sh stack          # cv-mariadb (seeded from wordpress-perfcheck's Gate 2 seed) and cv-site
bash bench/cold-visit/runs.sh burn           # the cpuset check (CPU lock)
AOT_EXTRA=--add-modules=jdk.internal.vm.ci AOT_SUFFIX=-m bash bench/cold-visit/runs.sh aot-train bc   # and tree
QUIET=45 QUIET_MAX=120 bash bench/cold-visit/runs.sh runs 3 5 treed bcd maind tree bc bcm bcp      # rounds 1-3
QUIET=45 QUIET_MAX=120 START=4 bash bench/cold-visit/runs.sh runs 5 5 tree main bc bcj bcm bcp treem bcjn bcmn bcpn
QUIET=45 QUIET_MAX=120 START=6 bash bench/cold-visit/runs.sh runs 8 5 bc bcn bcmn                 # stopped in round 7
python bench/cold-visit/phase_table.py treed maind bcd
python bench/cold-visit/report.py target/cv/runs bc tree main bcj bcm bcp ...
python tests/gate2/phases_report.py target/cv/runs jvm-java.treed jvm-java.maind jvm-java.bcd
bash bench/cold-visit/jfr.sh target/cv/out/r1/bcd.jfr CUTOFF_MS        # the JFR split, deoptimizations, compilations
bash bench/cold-visit/runs.sh memdiag bc 60 1 && python -I bench/cold-visit/memattr.py target/cv/mem bc-1
EXTRA_OPTS="-XX:+PrintCompilation -Xlog:dependencies=debug:file=/out/deps.log" bash bench/cold-visit/runs.sh memdiag bc 1 dp
```
