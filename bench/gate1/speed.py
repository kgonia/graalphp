"""Speed table of bench/k0/ab-each.sh runs: per kernel, each variant's steady state, its speed against a reference
variant, its first iteration, its first ten iterations and its peak memory.

Usage: python speed.py <dir> <reference index> <name>=<index>...
       e.g. python speed.py target/s1-off 1 main=0 tree=1 bytecode=2

The runs are <dir>/<kernel>/<variant index>-<round>.txt. A run's steady state is the median of its last quarter of
iterations; a cell is the median over rounds (interquartile range over rounds in parentheses). Speed is the
reference's steady state divided by the variant's, per round, median and IQR over rounds: above 1 is faster. The
last row is the geometric mean of the speeds. Peak memory is the run's cgroup memory.peak in MB.
"""
import glob
import math
import os
import statistics
import sys


def load(path):
    times, memory = [], None
    for line in open(path, encoding="utf-8", errors="replace"):
        parts = line.split()
        if len(parts) >= 4 and parts[1].isdigit():
            times.append(int(parts[2]) / 1e6)
        elif len(parts) == 2 and parts[0] == "peak_memory":
            memory = int(parts[1]) / 2**20
    return times, memory


def runs(directory, kernel, index):
    files = glob.glob(os.path.join(directory, kernel, f"{index}-*.txt"))
    files.sort(key=lambda f: int(f.rsplit("-", 1)[1][:-4]))
    return [r for r in (load(f) for f in files) if r[0]]


def steady(times):
    return statistics.median(times[3 * len(times) // 4:])


def quartiles(values):
    values = sorted(values)
    k = len(values) // 4
    return statistics.median(values), values[k], values[-1 - k]


def ms(value):
    return f"{value:.0f}" if value >= 100 else f"{value:.1f}" if value >= 10 else f"{value:.2f}"


def cell(values, form):
    m, lo, hi = quartiles(values)
    return f"{form(m)} ({form(lo)}–{form(hi)})"


directory, reference = sys.argv[1], int(sys.argv[2])
variants = [(a.split("=")[0], int(a.split("=")[1])) for a in sys.argv[3:]]
others = [(n, i) for n, i in variants if i != reference]
names = "/".join(n for n, _ in variants)
print("| kernel | " + " | ".join(f"{n} ms" for n, _ in variants) + " | "
      + " | ".join(f"{n} speed" for n, _ in others)
      + f" | first iteration ms ({names}) | first 10 ms ({names}) | peak MB ({names}) |")
print("|---" * (2 + len(variants) + len(others) + 2) + "|")
logs = {n: [] for n, _ in others}
for kernel in sorted(os.listdir(directory)):
    data = {i: runs(directory, kernel, i) for _, i in variants}
    cells = [cell([steady(t) for t, _ in data[i]], ms) for _, i in variants]
    for n, i in others:
        ratios = [steady(a[0]) / steady(b[0]) for a, b in zip(data[reference], data[i])]
        logs[n].append(math.log(quartiles(ratios)[0]))
        cells.append(cell(ratios, lambda r: f"{r:.2f}×"))
    cells.append(" / ".join(ms(statistics.median(t[0] for t, _ in data[i])) for _, i in variants))
    cells.append(" / ".join(ms(statistics.median(sum(t[:10]) for t, _ in data[i])) for _, i in variants))
    cells.append(" / ".join(f"{statistics.median(m for _, m in data[i] if m):.0f}" for _, i in variants))
    print(f"| {kernel} | " + " | ".join(cells) + " |")
geomean = [""] * len(variants) + [f"**{math.exp(sum(v) / len(v)):.2f}×**" for v in logs.values()] + [""] * 3
print("| **geomean** | " + " | ".join(geomean) + " |")
