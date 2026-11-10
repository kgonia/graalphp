# 101: The benchmark box runs at two speeds: a 2× level shift in a timing is the machine until shown otherwise

**State:** Open (a property of the box; what is open is the measurement rule and a box that holds its frequency).
Found 2026-11-10 during [wiki/beat-php.md](../wiki/beat-php.md): 18–28% of a steady K8 loop's renders took twice
the others' time, in runs of ≈ 125 ms every ≈ 0.3 s, and whole minutes of a run sat at twice the level of the
minutes after. It was first read as an engine effect (a "lottery" of compiled states, deoptimization, the garbage
collector). It is the CPU.

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
- **In the K8 loops:** the steady renders of a 10,000-render loop on the JVM split into a fast mode and one at 2×:
  20–23% of the renders above 1.5× the first decile in two runs, 66% in the last thousand renders of a third; 8%
  for php-cli. The slow renders come in runs, with and without a collection in them. One run with JFR and the
  compilation trace on had four minutes (renders 7000–11000) at a first decile of 47–49 ms, then 22 ms to the end.

**Why it matters:**

1. A median or a mean over a window is the engine's level times the window's share of slow time. php-cli's K8 loop
   has the calibration's ratio (mean 11.05 ms over a first decile of 9.40 = 1.18): the whole distance between PHP's
   first decile and its mean is the machine. Two runs of one
   build differ by up to 2× for minutes. Conclusions drawn tonight from such differences and withdrawn: "the
   garbage collector's concurrent cycles double the render", "a state of the compiled code chosen at random per
   run", "renders 7000–11000 wait for the tier-2 threshold".
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

**Open:**

- A fixed frequency: turbo off for the benchmark cores (the power plan's maximum processor state at 99%, or the
  firmware's setting) would trade the fast mode for one level. Not tried: it is the owner's machine and its other
  jobs' speed.
- `native.sh`'s reports (k6's medians, `k8_report.py`'s medians of block halves) do not print a first decile.
- The slow share in the VM with eight workers busy: not measured (the table above is one busy thread in an idle
  VM; the host's 67–75% was measured while a server benchmark loaded the VM's eight CPUs).
