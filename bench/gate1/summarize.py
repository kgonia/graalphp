"""Gate 1 comparison table from run.php outputs.

Usage: python summarize.py <php.txt> <truffle.txt> [<native.txt>]

The run files have lines "kernel iteration ns checksum", and "peak_memory <bytes>" when run with PEAK_MEMORY=1. Steady state is the median of the second half of the PHP
iterations and of the last quarter of the Truffle ones (Truffle warms up longer). native.txt is pcre-replay's output,
"kernel matches median-ns min-ns": one iteration's PCRE2 work replayed in C, the same for both engines. Without it
the PHP-code column equals end to end. PHP-code speedup = (T_php - T_native) / (T_truffle - T_native).
"""
import collections
import math
import statistics
import sys


def load(path):
    rows = collections.defaultdict(list)
    for line in open(path, encoding="utf-8", errors="replace"):
        parts = line.split()
        if len(parts) >= 4 and parts[1].isdigit():
            rows[parts[0]].append((int(parts[2]), int(parts[4]) if len(parts) > 4 else 0, parts[3]))
    return rows


def peak_memory(path):
    """The run's peak memory in MB ("peak_memory <bytes>", from PEAK_MEMORY=1), or None."""
    for line in open(path, encoding="utf-8", errors="replace"):
        if line.startswith("peak_memory "):
            return int(line.split()[1]) / 2**20
    return None


def geomean(values):
    return math.exp(sum(map(math.log, values)) / len(values)) if values else float("nan")


php, truffle = load(sys.argv[1]), load(sys.argv[2])
native = {}
if len(sys.argv) > 3:
    for line in open(sys.argv[3], encoding="utf-8"):
        parts = line.split()
        if len(parts) == 4:
            native[parts[0]] = int(parts[2]) / 1e6
print(f"{'kernel':12} {'php ms':>8} {'truffle':>8} {'native':>7} {'share':>6} {'e2e':>6} {'code':>6}  checksums")
e2e, code = [], []
for kernel in php:
    tp = statistics.median(r[0] for r in php[kernel][len(php[kernel]) // 2:]) / 1e6
    tt = statistics.median(r[0] for r in truffle[kernel][3 * len(truffle[kernel]) // 4:]) / 1e6
    tn = native.get(kernel, 0.0)
    c = (tp - tn) / (tt - tn) if tp > tn and tt > tn else float("nan")
    e2e.append(tp / tt)
    if c == c:
        code.append(c)
    same = {r[2] for r in php[kernel]} == {r[2] for r in truffle[kernel]}
    print(f"{kernel:12} {tp:8.2f} {tt:8.2f} {tn:7.2f} {tn / tp:6.0%} {tp / tt:6.2f} {c:6.2f}  {'same' if same else 'DIFFER'}")
print(f"geomean end-to-end {geomean(e2e):.2f}  PHP-code {geomean(code):.2f}")
mp, mt = peak_memory(sys.argv[1]), peak_memory(sys.argv[2])
if mp and mt:
    print(f"peak memory: php {mp:.0f} MB, truffle {mt:.0f} MB ({mt / mp:.1f}x)")
