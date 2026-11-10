# 101: The benchmark box runs at two speeds: a 2× level shift in a timing is the machine until shown otherwise

**State:** Open (a property of the box; what is open is the measurement rule and a box that holds its frequency).
Found 2026-11-10 during [wiki/beat-php.md](../wiki/beat-php.md): 18–28% of a steady K8 loop's renders took twice
the others' time, in runs of ≈ 125 ms every ≈ 0.3 s, and the median of a thousand renders moved by up to 1.7×
between runs of one build. It was first read as an engine effect (a "lottery" of compiled states, deoptimization,
the garbage collector). It is the CPU.

**What happens:** the box is a laptop (Intel Core Ultra 9 275HX: 8 P-cores, 16 E-cores, power plan "high
performance", on AC). Under load its cores run near their base frequency with turbo in bursts.

- **On the host, no Truffle, no WSL, no allocation.** A plain Java loop (`x = (x * 31 + j) & 0xFFFFFFF`,
  2,000,000 iterations per chunk, after 200 warm-up calls), pinned to one P-core (`start /affinity 800`) while a
  server benchmark loaded the VM's eight cores:

  | | chunks | first decile | median | 90th percentile | max | slow chunks (> 1.4× first decile) | slow runs: length, period |
  |---|---|---|---|---|---|---|---|
  | one P-core | 4592 in 12 s | 1.67 ms | 2.94 ms | 3.10 ms | 5.9 ms | 67% of chunks, 77% of time | 0.24 s, one every 0.405 s |
  | all eight P-cores (unpinned) | 4375 in 12 s | 1.72 ms | 3.09 ms | 3.10 ms | 3.5 ms | 75%, 83% | 0.21 s, one every 0.385 s |

  Two levels, 1 : 1.8, and a ramp between them (the histogram: 0.9–1.0× 13–16% of the chunks, 1.1–1.7× 3–5% each,
  1.8–1.9× 50–62%). The longest chunk is 2–3.5× a fast one: the thread is not preempted, it runs slower.
- **Windows' counters at that time** (`Win32_PerfFormattedData_Counters_ProcessorInformation`; the counter names of
  `Get-Counter` are localized, the WMI class's are not): `PercentProcessorPerformance` 100–128% on every core
  (P-cores' base 2700 MHz, E-cores' 2100 MHz), `PercentPerformanceLimit` 100, total CPU 54–58%. No sustained turbo.
- **In the VM, with nothing else running in it** (`tests/gate2/calib.php` in php-cli with its JIT, 45 s per
  cpuset, under the CPU lock):

  | cpuset | iterations per chunk | first decile | median | 90th percentile | max | slow chunks (> 1.4× first decile) | slow runs: length, period, slowdown | mean over first decile |
  |---|---|---|---|---|---|---|---|---|
  | 14-21 | 640,000 | 0.837 ms | 0.848 ms | 1.635 ms | 8.1 ms | 16.2% of chunks, 28.2% of time | 0.120 s, one every 0.362 s, ×1.97 | 1.19 |
  | 17 | 2,560,000 | 3.369 ms | 3.393 ms | 5.710 ms | 9.5 ms | 13.1%, 24.0% | 0.116 s, one every 0.367 s, ×2.29 | 1.16 |
  | 22-23 | 1,280,000 | 1.684 ms | 1.698 ms | 3.919 ms | 9.1 ms | 16.6%, 30.4% | 0.124 s, one every 0.344 s, ×2.33 | 1.22 |

  One busy thread in an idle VM spends a quarter of its time at half speed, in runs of 0.12 s every 0.35 s. The
  three cpusets give the same 1.31 ns an iteration in the fast mode: the guest's CPU number does not select a kind
  of host core.
- **The same loop on our engine** (`calib.sh NAME CPUS SECONDS jvm`), one run after the other on CPU 17:

  | engine, cpuset | fast mode, ns per iteration | slow chunks | slow runs: length, period, slowdown | mean over first decile |
  |---|---|---|---|---|
  | php-cli with its JIT, 17 | 1.315 | 15.9% of chunks, 29.1% of time | 0.119 s, one every 0.368 s, ×2.34 | 1.21 |
  | our engine, 17 | 0.872 | 15.8%, 32.0% | 0.124 s, one every 0.350 s, ×2.37 | 1.25 |
  | our engine, 14-21 | 0.872 | 8.7%, 17.7% | 0.125 s, one every 0.367 s, ×2.38 | 1.16 |

  Both engines see the same slow runs and the same share. (The engine's compiled loop is 1.5× faster than PHP's
  JIT here.)
- **How many cores are busy decides how slow.** `calib.php` on several of the benchmark CPUs at once (one php-cli
  per CPU, 30 s, nothing else in the VM), and what each trace does to 20 ms of fast-mode work, a hot render's
  size (the chunks summed to 20 ms of work at 1.315 ns an iteration, over that: `tests/gate2/calib_par.py`):

  | busy CPUs | first decile of a chunk, ns per iteration | mean, ns per iteration | million iterations a second per CPU | slow chunks (> 1.4× the fast level), share of the time | 20 ms of work: first decile / median / mean / 90th percentile, × the fast level |
  |---|---|---|---|---|---|
  | 1 (CPU 17; three runs, before, between and after the others) | 1.307–1.317 | 1.584–1.635 | 611–631 | 15–20% of chunks, 29–34% | 1.00 / 1.02 / 1.20–1.24 / 1.89–2.04 |
  | 2 (16, 17) | 1.315–1.317 | 1.745–1.746 | 573 | 26–29%, 43–45% | 1.00 / 1.04–1.05 / 1.33 / 2.34 |
  | 4 (14–17) | 1.315 on each | 1.888–1.890 | 529 | 36–37%, 54–55% | 1.01 / 1.23–1.24 / 1.44 / 2.34 |
  | 5 (14–18) | 1.315–1.318 | 1.870–1.871 | 534–535 | 39–46%, 55–59% | 1.01–1.02 / 1.31–1.32 / 1.42 / 2.09–2.12 |
  | 6 (14–19) | 1.307–1.320 | 2.067–2.070 | 483–484 | 46–60%, 64–72% | **1.12–1.15** / 1.44–1.45 / 1.57 / 2.34–2.35 |
  | 7 (14–20) | 1.307–1.363 | 2.090–2.094 | 477–478 | 48–67%, 66–76% | **1.30** / 1.45–1.46 / 1.59 / 2.34 |
  | 8 (14–21) | 1.315–1.323 on five, 1.55–1.56 on three | 2.196–2.203 | 454–455 | 54–76%, 71–83% | **1.40** / 1.52–1.53 / 1.67–1.68 / 2.34 |

  The fast mode's level does not move; the time spent out of it does. Up to five busy cores a stretch of 20 ms
  still fits into the fast mode often enough for the first decile to hold (1.00–1.02×); with six it is 1.12–1.15×,
  with seven 1.30×, with eight 1.40×, the same on every CPU. The median moves from the second busy core on.
  Eight busy CPUs do 5.9 times one CPU's work. (Four and eight were measured an hour before two, five, six and
  seven.)
- **In the K8 loops:** the steady renders of a loop on the JVM split into a fast mode and one at 2×: 12–23% of
  the last thousand renders above 1.5× the first decile in six runs, 66% in a seventh; for php-cli 6% in a quiet
  thousand, 17% over 10,000. The slow renders come in runs, with and without a collection in them.
- **What is not the machine:** a shift of the first decile itself. One run with JFR and the compilation trace on
  had four minutes (renders 7000–11000) at a first decile of 47–49 ms, then 22 ms to the end. It was first put
  here. The trace says otherwise: no compilation during the four minutes, HotSpot's code-cache sweeper had
  unloaded compiled code as cold before them ("opt reprof" in the trace, a profile reset), and they end with 241
  last-tier compilations. [100](100-warm-up.md).

**Why it matters:**

1. A median or a mean over a window is the engine's level times the window's share of slow time. php-cli's K8 loop
   has the calibration's ratio (mean over first decile 1.11–1.18): the whole distance between PHP's first decile
   and its mean is the machine. The medians of two runs of one build differ by up to 1.7× over a thousand renders
   with the first decile in place. Conclusions drawn tonight from such differences and withdrawn: "the garbage
   collector's concurrent cycles double the render", "a state of the compiled code chosen at random per run".
   (G1's concurrent cycles do cost something: the median falls from 25–37 ms to 22 when they stop, the first
   decile stays; [100](100-warm-up.md). It was the factor of two that was the machine.)
2. The slow share grows with the number of busy cores anywhere on the box (other agents' jobs, and the JVM's own
   compiler and collector threads). A single-threaded PHP process is slowed less than a JVM that is still compiling:
   part of the JVM's mean on this box is the box.
3. `hot.php`'s "steady" value per kernel is the median of its last two rounds: one of them in the slow mode moves
   it by up to 1.8×. The per-kernel tables of [097](097-builtins-behind-a-boundary.md) and
   [098](098-per-call-name-work-in-lookups.md) are one run each and carry that; the geometric means over the 11
   kernels are steadier.

**The rule until the box changes:**

- Report the **first decile** (the fast mode's level) beside the median and the mean: `tests/gate2/k8_traj.py`.
- Interleave the engines, and run `tests/gate2/calib.sh` (`calib.php`: fixed integer work timed chunk by chunk;
  `calib.py`: the two levels, the slow share, the runs' length and period) beside a measurement whose conclusion
  hangs on a level shift.
- A difference between two builds counts when it shows in the first decile, or in the mean of interleaved rounds.
- A shift of the median or the mean with the first decile in place is the machine; a shift of the first decile
  **at an equal number of busy cores, or with at most five busy,** is the engine, and a compilation trace
  (`-Dpolyglot.engine.TraceCompilation=true`) beside the run says what it did.
- Count the busy cores of both sides before comparing: a single-threaded loop against a single-threaded loop is
  the one-core row for both; a JVM with six compiler threads working, or a server at eight users, is not. With
  six or more busy cores the first decile itself carries 12–40% of machine.

**Open:**

- A fixed frequency: turbo off for the benchmark cores (the power plan's maximum processor state at 99%, or the
  firmware's setting) would trade the fast mode for one level. Not tried: it is the owner's machine and its other
  jobs' speed.
- `native.sh`'s reports (k6's medians, `k8_report.py`'s medians of block halves) do not print a first decile.
- What the calibration cannot see: it is integer arithmetic in a register. Whether busy neighbours also cost a
  render through the shared cache and memory was not measured; the 30–45% by which one context's first decile
  rises while its compiler threads work ([100](100-warm-up.md)) is at most a third frequency (four to six busy
  cores: 1–15%).
