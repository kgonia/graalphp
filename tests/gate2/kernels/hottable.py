"""Kernel table: median of the steady rounds (the last third) of hot.php per engine, and its ratio to the first.
Usage: python -I hottable.py DIR NAME.ENGINE [NAME.ENGINE...]   (files DIR/NAME.ENGINE.out, lines: kernel round ns/op checksum)
"""
import collections
import math
import statistics
import sys

d = sys.argv[1]
names = sys.argv[2:]
data = {}
order = []
for name in names:
    rows = collections.defaultdict(list)
    for line in open(f'{d}/{name}.out', encoding='utf-8', errors='replace'):
        p = line.split()
        if len(p) >= 3 and p[1].lstrip('-').isdigit():
            rows[p[0]].append((int(p[1]), float(p[2])))
            if p[0] not in order:
                order.append(p[0])
    data[name] = rows


def steady(rows):
    rows = sorted(rows)
    if not rows:
        return None
    tail = [v for _, v in rows[len(rows) * 2 // 3:]] or [rows[-1][1]]
    return statistics.median(tail)


print('| kernel | ' + ' | '.join(names) + ' |')
print('|---|' + '---:|' * len(names))
logs = collections.defaultdict(list)
for k in order:
    cells = []
    ref = steady(data[names[0]].get(k, []))
    for name in names:
        v = steady(data[name].get(k, []))
        if v is None:
            cells.append('-')
        elif name == names[0] or not ref:
            cells.append(f'{v:.0f}')
        else:
            cells.append(f'{v:.0f} ({v / ref:.2f})')
            logs[name].append(math.log(v / ref))
    print(f'| {k} | ' + ' | '.join(cells) + ' |')
print('| geomean | - | ' + ' | '.join(f'{math.exp(sum(logs[n]) / len(logs[n])):.2f}' if logs[n] else '-' for n in names[1:]) + ' |')
