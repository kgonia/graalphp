"""The CPU sampler's histograms summed (-Dpolyglot.cpusampler=true, Output=histogram, ShowTiers=true,
SummariseThreads=true). The server prints one histogram per context, so per request; this sums their self time by
PHP function and by tier: the interpreter (T0), tier 1, tier 2.

Prints the share of the requests' sampled time in each tier, the functions by self time with their tier split, and
the functions with the most interpreted self time (code that ran in the interpreter: not yet compiled, or failed).

Usage: python -I tests/gate2/sampler_report.py CPUSAMPLER_TXT [--top N]
"""
import collections
import re
import sys

ROW = re.compile(r'^ (.+?)\s+\|\|\s+(\d+)ms\s+[\d.]+%\s+\|\s+([\d.]+)%\s+\|\s+([\d.]+)%\s+\|\s+([\d.]+)%\s+\|\|'
                 r'\s+(\d+)ms\s+[\d.]+%\s+\|\s+([\d.]+)%\s+\|\s+([\d.]+)%\s+\|\s+([\d.]+)%\s+\|\|\s+(.*)$')


def main():
    path = sys.argv[1]
    top = int(sys.argv[sys.argv.index('--top') + 1]) if '--top' in sys.argv else 30
    self_ms = collections.Counter()
    tiers = collections.defaultdict(lambda: [0.0, 0.0, 0.0])
    where = {}
    histograms = 0
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            if line.startswith('Sampling Histogram.'):
                histograms += 1
                continue
            m = ROW.match(line)
            if not m:
                continue
            name = m.group(1).strip()
            ms = int(m.group(6))
            if not ms:
                continue
            self_ms[name] += ms
            t = tiers[name]
            for i in range(3):
                t[i] += ms * float(m.group(7 + i)) / 100
            where[name] = m.group(10).strip()
    total = sum(self_ms.values()) or 1
    by_tier = [sum(t[i] for t in tiers.values()) for i in range(3)]
    print(f'{histograms} histograms, {total / 1000:.1f} s of sampled self time; interpreter {100 * by_tier[0] / total:.1f}%,'
          f' tier 1 {100 * by_tier[1] / total:.1f}%, tier 2 {100 * by_tier[2] / total:.1f}%')
    builtin = sum(ms for name, ms in self_ms.items() if where.get(name, '').startswith('<builtin>'))
    print(f'built-ins (<builtin>) {100 * builtin / total:.1f}% of self time')
    print(f'\nfunctions by self time ({top}): ms, share, interpreter / tier 1 / tier 2')
    for name, ms in self_ms.most_common(top):
        t = tiers[name]
        print(f'  {ms:8d} {100 * ms / total:5.1f}%  {100 * t[0] / ms:5.1f} / {100 * t[1] / ms:5.1f} / {100 * t[2] / ms:5.1f}  {name}')
    print(f'\nfunctions by interpreted self time ({top}): ms interpreted, share of all, its own share interpreted')
    for name, t in sorted(tiers.items(), key=lambda kv: -kv[1][0])[:top]:
        print(f'  {t[0]:8.0f} {100 * t[0] / total:5.1f}%  {100 * t[0] / self_ms[name]:5.1f}%  {name}  {where.get(name, "")[-60:]}')


if __name__ == '__main__':
    main()
