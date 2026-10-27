"""Summary of bench/gate1/ab.sh's runs.

Usage: python ab.py <dir> <variant>...   (the runs are <dir>/<variant index>-<round>.txt)

A run file has lines "kernel iteration ns checksum" and one "peak_memory <bytes>". A kernel's time in a run is the
median of its last quarter of iterations; per variant the median over rounds is printed, and for each later variant
the median and interquartile range of its per-round ratios to the first (below 1 is faster or smaller).
"""
import collections
import glob
import os
import statistics
import sys


def load(path):
    times = collections.defaultdict(list)
    memory = None
    for line in open(path, encoding="utf-8", errors="replace"):
        parts = line.split()
        if len(parts) >= 4 and parts[1].isdigit():
            times[parts[0]].append(int(parts[2]))
        elif len(parts) == 2 and parts[0] == "peak_memory":
            memory = int(parts[1]) / 2**20
    run = {kernel: statistics.median(ns[3 * len(ns) // 4:]) / 1e6 for kernel, ns in times.items()}
    run["memory"] = memory
    return run


def rounds(directory, index):
    files = glob.glob(os.path.join(directory, f"{index}-*.txt"))
    return [load(f) for f in sorted(files, key=lambda f: int(f.rsplit("-", 1)[1][:-4]))]


def ratio_cell(name, ratios):
    ratios = sorted(ratios)
    q = len(ratios) // 4
    return f"{name} {statistics.median(ratios):.2f} (IQR {ratios[q]:.2f}-{ratios[-1 - q]:.2f})"


directory, variants = sys.argv[1], sys.argv[2:]
runs = [rounds(directory, i) for i in range(len(variants))]
kernels = [k for k in runs[0][0] if k != "memory"]
for i, variant in enumerate(variants):
    cells = [f"{k} {statistics.median(r[k] for r in runs[i]):.2f}" for k in kernels]
    memory = [r["memory"] for r in runs[i] if r["memory"]]
    if memory:
        cells.append(f"peak memory {statistics.median(memory):.0f} MB")
    print(f"{variant:8} " + "  ".join(cells))
for i in range(1, len(variants)):
    pairs = list(zip(runs[0], runs[i]))
    cells = [ratio_cell(k, [b[k] / a[k] for a, b in pairs]) for k in kernels]
    if all(a["memory"] and b["memory"] for a, b in pairs):
        cells.append(ratio_cell("memory", [b["memory"] / a["memory"] for a, b in pairs]))
    print(f"{variants[0]}->{variants[i]}: " + "  ".join(cells))
