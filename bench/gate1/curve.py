"""Warmup curves, steady state and peak memory of bench/gate1/ab.sh runs, one kernel per run directory.

Usage: python curve.py <dir> <variant>...   (<dir>/<kernel>/<variant index>-<round>.txt, as AB_OUT=<dir>/<kernel>
bench/gate1/ab.sh <kernel> ... leaves them)

Per kernel and variant, medians over the rounds of: iterations 1, 2, 3, 5, 10, 20, 40, ... (ms); the first ten
iterations together; the steady state (each run's median of its last quarter of iterations) with the rounds'
interquartile range; the run's peak memory (cgroup memory.peak, MB). Then per later variant, the median and IQR of
its per-round steady-state and peak-memory ratios to the first variant (below 1 is faster or smaller).
"""
import glob
import os
import statistics
import sys


def load(path):
    """Iteration times (ms) of the run's one kernel, and its peak memory (MB, or None)."""
    times, memory = [], None
    for line in open(path, encoding="utf-8", errors="replace"):
        parts = line.split()
        if len(parts) >= 4 and parts[1].isdigit():
            times.append(int(parts[2]) / 1e6)
        elif len(parts) == 2 and parts[0] == "peak_memory":
            memory = int(parts[1]) / 2**20
    return times, memory


def quartiles(values):
    values = sorted(values)
    q = len(values) // 4
    return statistics.median(values), values[q], values[-1 - q]


def steady(times):
    return statistics.median(times[3 * len(times) // 4:])


def fmt(ms):
    return f"{ms:.0f}" if ms >= 100 else f"{ms:.1f}" if ms >= 10 else f"{ms:.2f}"


directory, variants = sys.argv[1], sys.argv[2:]
for kernel in sorted(os.listdir(directory)):
    runs = []
    for i in range(len(variants)):
        files = glob.glob(os.path.join(directory, kernel, f"{i}-*.txt"))
        files.sort(key=lambda f: int(f.rsplit("-", 1)[1][:-4]))
        runs.append([load(f) for f in files])
    n = min(len(t) for t, _ in runs[0])
    points = [p for p in (1, 2, 3, 5, 10, 20, 40, 60, 80, 100, 150, 200) if p <= n]
    print(f"## {kernel} ({len(runs[0])} rounds, {n} iterations)")
    print("| variant | " + " | ".join(f"it {p}" for p in points) + " | first 10 | steady (IQR) | peak MB (IQR) |")
    print("|---" * (len(points) + 4) + "|")
    for variant, rs in zip(variants, runs):
        curve = [fmt(statistics.median(t[p - 1] for t, _ in rs)) for p in points]
        first = fmt(statistics.median(sum(t[:10]) for t, _ in rs))
        s, lo, hi = quartiles([steady(t) for t, _ in rs])
        memory = [m for _, m in rs if m]
        mem = "{:.0f} ({:.0f}-{:.0f})".format(*quartiles(memory)) if memory else "-"
        print(f"| {variant} | " + " | ".join(curve) + f" | {first} | {fmt(s)} ({fmt(lo)}-{fmt(hi)}) | {mem} |")
    for i in range(1, len(variants)):
        pairs = list(zip(runs[0], runs[i]))
        t = "{:.2f} (IQR {:.2f}-{:.2f})".format(*quartiles([steady(b[0]) / steady(a[0]) for a, b in pairs]))
        f10 = "{:.2f}".format(statistics.median(sum(b[0][:10]) / sum(a[0][:10]) for a, b in pairs))
        cells = [f"steady {t}", f"first 10 {f10}"]
        if all(a[1] and b[1] for a, b in pairs):
            cells.append("memory {:.2f} (IQR {:.2f}-{:.2f})".format(*quartiles([b[1] / a[1] for a, b in pairs])))
        print(f"\n{variants[0]} -> {variants[i]}: " + ", ".join(cells))
    print()
