"""Summary of bench/bcrypt/ab.sh's runs.

Usage: python summarize.py <dir> <variant>...   (the runs are <dir>/<variant index>-<round>.txt)

Per kernel and variant, in ms: the first call, and the steady state (the median of the last quarter of a run's
iterations) as wall time and as the calling thread's CPU time, each the median over rounds with the rounds' range;
then the median per-round ratio of the steady CPU time to the first variant's (above 1 is slower). Peak memory is
each run's cgroup memory.peak, the median over rounds. Every run of a kernel must give the same checksum.
"""
import collections
import glob
import os
import statistics
import sys


def load(path):
    wall, cpu, sums, memory = collections.defaultdict(list), collections.defaultdict(list), collections.defaultdict(set), None
    for line in open(path, encoding="utf-8", errors="replace"):
        parts = line.split()
        if len(parts) >= 4 and parts[1].isdigit():
            wall[parts[0]].append(int(parts[2]) / 1e6)
            cpu[parts[0]].append(int(parts[4]) / 1e6 if len(parts) > 4 else float("nan"))
            sums[parts[0]].add(parts[3])
        elif len(parts) == 2 and parts[0] == "peak_memory":
            memory = int(parts[1]) / 2**20
    return {"wall": wall, "cpu": cpu, "sums": sums, "memory": memory}


def steady(values):
    return statistics.median(values[3 * len(values) // 4:])


def cell(values):
    return f"{statistics.median(values):7.1f} ({min(values):.1f}-{max(values):.1f})"


def main():
    directory, variants = sys.argv[1], sys.argv[2:]
    runs = {i: [load(f) for f in sorted(glob.glob(os.path.join(directory, f"{i}-*.txt")))] for i in range(len(variants))}
    kernels = list(runs[0][0]["wall"])
    width = max(len(v) for v in variants)
    for k in kernels:
        sums = set().union(*(r["sums"][k] for rs in runs.values() for r in rs))
        print(f"{k}{'' if len(sums) == 1 else '  CHECKSUMS DIFFER: ' + ' '.join(sorted(sums))}")
        print(f"  {'variant':<{width}}  {'first call, wall':<24}  {'steady, wall':<24}  {'steady, CPU':<24}  CPU vs first")
        base = [steady(r["cpu"][k]) for r in runs[0]]
        for i, v in enumerate(variants):
            first = [r["wall"][k][0] for r in runs[i]]
            wall = [steady(r["wall"][k]) for r in runs[i]]
            cpu = [steady(r["cpu"][k]) for r in runs[i]]
            ratio = statistics.median(c / b for c, b in zip(cpu, base))
            print(f"  {v:<{width}}  {cell(first):<24}  {cell(wall):<24}  {cell(cpu):<24}  x{ratio:.2f}")
    print("peak memory (cgroup memory.peak), MB")
    for i, v in enumerate(variants):
        print(f"  {v:<{width}}  {statistics.median(r['memory'] for r in runs[i] if r['memory']):8.0f}")


if __name__ == "__main__":
    main()
