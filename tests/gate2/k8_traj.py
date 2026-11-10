"""A K8 loop's render time along the run: first decile and median per segment, and the steady level.

Usage: python -I k8_traj.py [--steady=N] [--context=K] LABEL=FILE.json ...

FILE.json is an output of k8-cli.sh (one document per context; --context picks the K-th, default the last).
The first decile is the level of the machine's fast mode (issue 101): the box's cores run at two speeds, so a
median over a window mixes the engine's state with the share of the window spent in the slow mode.
Steady = the last N renders (default 1000); "above 1.5x p10" is the share of them in the slow mode (or in a GC
pause). The peak is the cgroup's memory.peak from FILE.err.
"""
import json
import os
import statistics
import sys

EDGES = [0, 500, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000, 11000, 12000]


def documents(path):
    text = open(path, encoding='utf-8', errors='replace').read()
    decoder = json.JSONDecoder()
    docs = []
    at = text.find('{"engine"')
    while at >= 0:
        doc, end = decoder.raw_decode(text, at)
        docs.append(doc)
        at = text.find('{"engine"', end)
    return docs


def peak(path):
    err = os.path.splitext(path)[0] + '.err'
    if not os.path.exists(err):
        return ''
    for line in reversed(open(err, encoding='utf-8', errors='replace').read().splitlines()):
        if line.startswith('peak '):
            return f'{int(line.split()[1]) / 2**20:.0f}'    # MiB, as k8_report.py and k8_cli_report.py
    return ''


def decile(values):
    s = sorted(values)
    return s[len(s) // 10]


def main(argv):
    steady = 1000
    context = -1
    runs = []
    for arg in argv:
        if arg.startswith('--steady='):
            steady = int(arg.split('=')[1])
        elif arg.startswith('--context='):
            context = int(arg.split('=')[1]) - 1
        else:
            runs.append(arg.split('=', 1))
    used = 0
    table = []
    for label, path in runs:
        docs = documents(path)
        d = [r[0] / 1e6 for r in docs[context]['iterations']]
        cells = []
        for a, b in zip(EDGES, EDGES[1:]):
            w = d[a:b]
            cells.append(f'{decile(w):.1f} / {statistics.median(w):.1f}' if len(w) >= (b - a) * 0.9 else '')
        used = max(used, sum(1 for c in cells if c))
        last = d[-steady:]
        fast = decile(last)
        slow = 100 * sum(1 for x in last if x > 1.5 * fast) / len(last)
        table.append((label, cells, len(d), fast, statistics.median(last), statistics.mean(last), slow, min(d),
                      sum(d) / 1e3, peak(path)))
    heads = [f'{a}-{b}' for a, b in zip(EDGES, EDGES[1:])][:used]
    print('| run | ' + ' | '.join(heads) + f' | renders | last {steady}: p10 | median | mean | above 1.5x p10 | best render | loop s | peak MB |')
    print('|---|' + '---|' * used + '---|---|---|---|---|---|---|---|')
    for label, cells, n, p10, med, mean, slow, best, total, pk in table:
        print(f'| {label} | ' + ' | '.join(cells[:used])
              + f' | {n} | {p10:.2f} | {med:.2f} | {mean:.2f} | {slow:.0f}% | {best:.2f} | {total:.0f} | {pk} |')


if __name__ == '__main__':
    main(sys.argv[1:])
