"""Summarizes tregex-ab.sh's runs: per variant, the median over rounds of each kernel's and each pattern's TRegex
time over PCRE2 time (both measured in the same JVM), of the adaptive choice's regex time over PCRE2 alone, and of
the run's peak memory.
Usage: python bench/gate1/tregex-ab.py target/tregex-ab label..."""
import glob
import os
import re
import statistics
import sys


def parse(path):
    """The kernel rows, per-pattern rows, adaptive rows and peak memory of one replay output."""
    kernels, patterns, adaptive, peak = {}, {}, {}, None
    section = 'kernels'
    for line in open(path, encoding='utf-8', errors='replace'):
        if line.startswith('per pattern'):
            section = 'patterns'
        elif line.startswith('per kernel'):
            section = None
        elif line.startswith('adaptive choice'):
            section = 'adaptive'
        elif line.startswith('peak_memory'):
            peak = int(line.split()[1])
        elif section == 'kernels':
            m = re.match(r'(\w+)\s+(\d+)\s+(\d+)\s+(\d+)\s+\S+%\s+(\d+)\s+(\d+)\s+([\d.]+)\s+(\d+)', line)
            if m and int(m[3]) > 0:
                kernels[m[1]] = (int(m[5]), int(m[6]))
        elif section == 'patterns':
            m = re.match(r'(\w+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+[\d.]+\s+(\S+)\s+(.*)', line)
            if m:
                patterns[(m[1], int(m[2]))] = (int(m[5]), int(m[6]), m[3], m[4], m[7], m[8].strip())
        elif section == 'adaptive':
            m = re.match(r'(\w+)\s+(\d+)\s+(\d+)\s+(\d+)\s+[\d.]+\s+[\d.]+\s+(\d+)\s+(\d+)', line)
            if m and int(m[2]) > 1000:
                adaptive[m[1]] = (int(m[2]), int(m[3]), int(m[4]), int(m[5]), int(m[6]))
    return kernels, patterns, adaptive, peak


def median(values):
    return statistics.median(values) if values else float('nan')


def main():
    out, labels = sys.argv[1], sys.argv[2:]
    runs = {label: [parse(p) for p in sorted(glob.glob(os.path.join(out, label + '-*.txt')))] for label in labels}
    print('rounds: ' + ', '.join('%s %d' % (label, len(runs[label])) for label in labels))

    print('\nper kernel, TRegex / PCRE2 time over the covered matches (median of rounds)')
    kernels = list(dict.fromkeys(k for label in labels for run in runs[label] for k in run[0]))
    print('%-12s ' % 'kernel' + ' '.join('%10s' % label for label in labels))
    for k in kernels:
        cells = [median([run[0][k][1] / run[0][k][0] for run in runs[label] if k in run[0]]) for label in labels]
        print('%-12s ' % k + ' '.join('%10.2f' % c for c in cells))

    print('\nadaptive choice and per-pattern oracle: regex time over PCRE2 alone, with the patterns on TRegex'
          ' (median of rounds)')
    kernels = list(dict.fromkeys(k for label in labels for run in runs[label] for k in run[2]))
    print('%-12s %10s ' % ('kernel', 'pcre ns') + ' '.join('%30s' % label for label in labels))
    for k in kernels:
        cells = []
        for label in labels:
            rows = [run[2][k] for run in runs[label] if k in run[2]]
            cells.append('adapt %5.2f (%2d) oracle %5.2f (%2d)' % (
                median([r[1] / r[0] for r in rows]), median([r[3] for r in rows]),
                median([r[2] / r[0] for r in rows]), median([r[4] for r in rows])))
        ns = median([run[2][k][0] for run in runs[labels[0]] if k in run[2]])
        print('%-12s %10.0f ' % (k, ns) + ' '.join('%30s' % c for c in cells))

    print('\nper pattern: PCRE2 ns of one kernel iteration (first variant), TRegex / PCRE2 per variant (median)')
    keys = list(dict.fromkeys(k for label in labels for run in runs[label] for k in run[1]))
    first = labels[0]

    def pcre_ns(key):
        values = [run[1][key][0] for label in labels for run in runs[label] if key in run[1]]
        return median(values)

    keys.sort(key=lambda key: -pcre_ns(key))
    print('%-11s %3s %6s %4s %9s ' % ('kernel', 'id', 'calls', 'hit%', 'pcre ns')
          + ' '.join('%9s' % label for label in labels) + '  features  pattern')
    for key in keys:
        info = next(run[1][key] for label in labels for run in runs[label] if key in run[1])
        ns = median([run[1][key][0] for run in runs[first] if key in run[1]])
        cells = []
        for label in labels:
            ratios = [run[1][key][1] / run[1][key][0] for run in runs[label] if key in run[1]]
            cells.append('%9.2f' % median(ratios) if ratios else '%9s' % '-')
        print('%-11s %3d %6s %4s %9.0f ' % (key[0], key[1], info[2], info[3], ns) + ' '.join(cells)
              + '  %-9s %s' % (info[4], info[5][:60].replace('\t', ' ')))

    print('\nper pattern: TRegex ns of each variant over the first variant\'s PCRE2 ns (medians; for a native-image'
          ' variant against PCRE2 on the JVM)')
    print('%-11s %3s %9s ' % ('kernel', 'id', 'pcre ns') + ' '.join('%9s' % label for label in labels))
    for key in keys:
        ns = median([run[1][key][0] for run in runs[first] if key in run[1]])
        cells = []
        for label in labels:
            tregex = [run[1][key][1] for run in runs[label] if key in run[1]]
            cells.append('%9.2f' % (median(tregex) / ns) if tregex and ns == ns else '%9s' % '-')
        print('%-11s %3d %9.0f ' % (key[0], key[1], ns) + ' '.join(cells))
    print('%-11s %3s %9s ' % ('kernel', '', 'pcre ns') + ' '.join('%9s' % label for label in labels))
    for k in dict.fromkeys(key[0] for key in keys):
        ns = sum(median([run[1][key][0] for run in runs[first] if key in run[1]]) for key in keys if key[0] == k)
        cells = []
        for label in labels:
            total = sum(median([run[1][key][1] for run in runs[label] if key in run[1]]) for key in keys
                        if key[0] == k)
            cells.append('%9.2f' % (total / ns))
        print('%-11s %3s %9.0f ' % (k, 'all', ns) + ' '.join(cells))

    print('\npeak memory (MB, median of rounds): ' + ', '.join(
        '%s %.0f' % (label, median([run[3] / 2 ** 20 for run in runs[label] if run[3]])) for label in labels))


if __name__ == '__main__':
    main()
