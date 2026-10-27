"""Comparison table of bench/graalphp/run.sh's runs: per program the steady state of each engine (median of the
last third of the iterations, the same window for both), the speed-up, the first iteration of each, the time of all
iterations together (warm-up included), whether the outputs match without the timing lines, and each run's peak
memory.

The programs print a timing line per iteration, "name;N;iters;iter;ms;" (or "name N/iters/iter/val;N;iters;iter;ms;").
Usage: python summarize.py <dir> <program>...
"""
import statistics
import sys


def load(path):
    times, output, peak = [], [], None
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.rstrip("\n")
        fields = line.split(";")
        if len(fields) >= 6 and fields[3].isdigit():
            times.append(float(fields[4]))
        elif line.startswith("peak_memory "):
            peak = int(line.split()[1]) / 2**20
        elif not line.startswith("SLF4J"):
            output.append(line)
    return times, output, peak


out = sys.argv[1]
print(f"{'program':34} {'php ms':>8} {'truffle':>8} {'speedup':>7} {'1st php':>8} {'1st tr':>8} {'all php':>8} "
      f"{'all tr':>8} {'all x':>6}  {'output':6} {'php MB':>6} {'tr MB':>6}")
for name in sys.argv[2:]:
    tp, op, mp = load(f"{out}/{name}.php.txt")
    tt, ot, mt = load(f"{out}/{name}.truffle.txt")
    if not tp or not tt:
        error = next((line for line in op + ot if "error" in line.lower()), "")
        print(f"{name:34} FAILED (timings: php {len(tp)}, truffle {len(tt)}) {error[:110]}")
        continue
    sp = statistics.median(tp[-len(tp) // 3:])
    st = statistics.median(tt[-len(tt) // 3:])
    print(f"{name:34} {sp:8.1f} {st:8.1f} {sp / st:7.2f} {tp[0]:8.1f} {tt[0]:8.1f} {sum(tp) / 1000:7.1f}s "
          f"{sum(tt) / 1000:7.1f}s {sum(tp) / sum(tt):6.2f}  {'same' if op == ot else 'DIFFER':6} "
          f"{mp or 0:6.0f} {mt or 0:6.0f}")
