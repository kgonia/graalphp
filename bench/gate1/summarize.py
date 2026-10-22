"""Gate 1 comparison table from run.php outputs.

Usage: python summarize.py <php.txt> <truffle.txt> [<truffle-native.txt>]

Each input has lines "kernel iteration ns checksum [native-ns]". Steady state is the median of the second half of
the PHP iterations and of the last quarter of the Truffle ones (Truffle warms up longer). The native file comes from
a Truffle run with -Dphp.nativeclock=true (timing every PCRE2 call costs, so it is a separate run); without it the
PHP-code column equals end to end. PHP-code speedup = (T_php - T_native) / (T_truffle - T_native).
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


def geomean(values):
    return math.exp(sum(map(math.log, values)) / len(values)) if values else float("nan")


php, truffle = load(sys.argv[1]), load(sys.argv[2])
native = load(sys.argv[3]) if len(sys.argv) > 3 else {}
print(f"{'kernel':12} {'php ms':>8} {'truffle':>8} {'native':>7} {'share':>6} {'e2e':>6} {'code':>6}  checksums")
e2e, code = [], []
for kernel in php:
    tp = statistics.median(r[0] for r in php[kernel][len(php[kernel]) // 2:]) / 1e6
    tt = statistics.median(r[0] for r in truffle[kernel][3 * len(truffle[kernel]) // 4:]) / 1e6
    runs = native.get(kernel, [])
    tn = statistics.median(r[1] for r in runs[len(runs) // 2:]) / 1e6 if runs else 0.0
    c = (tp - tn) / (tt - tn) if tp > tn and tt > tn else float("nan")
    e2e.append(tp / tt)
    if c == c:
        code.append(c)
    same = {r[2] for r in php[kernel]} == {r[2] for r in truffle[kernel]}
    print(f"{kernel:12} {tp:8.2f} {tt:8.2f} {tn:7.2f} {tn / tp:6.0%} {tp / tt:6.2f} {c:6.2f}  {'same' if same else 'DIFFER'}")
print(f"geomean end-to-end {geomean(e2e):.2f}  PHP-code {geomean(code):.2f}")
