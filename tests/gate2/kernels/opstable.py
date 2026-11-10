"""ops.php's table: per kernel the first decile of the last half of its rounds (ns per operation) on each run, and
that over the first run's (issue 101: on this machine the first decile is what compares).
Usage: python -I opstable.py DIR NAME.ENGINE [NAME.ENGINE...]   (files DIR/NAME.ENGINE.out: kernel round ns/op checksum)
"""
import collections
import math
import sys

d = sys.argv[1]
names = sys.argv[2:]
data = {}
order = []
for name in names:
    rows = collections.defaultdict(list)
    for line in open(f'{d}/{name}.out', encoding='utf-8', errors='replace'):
        p = line.split()
        if len(p) == 4 and p[1].isdigit():
            rows[p[0]].append(float(p[2]))
            if p[0] not in order:
                order.append(p[0])
    data[name] = rows


def decile(values):
    tail = sorted(values[len(values) // 2:])
    return tail[len(tail) // 10] if tail else None


print('| kernel | ' + ' | '.join(names) + ' |')
print('|---|' + '---:|' * len(names))
logs = collections.defaultdict(list)
for k in order:
    ref = decile(data[names[0]].get(k, []))
    cells = []
    for name in names:
        v = decile(data[name].get(k, []))
        if v is None:
            cells.append('-')
        elif name == names[0] or not ref:
            cells.append(f'{v:.2f}')
        else:
            cells.append(f'{v:.2f} ({v / ref:.2f})')
            logs[name].append(math.log(v / ref))
    print(f'| {k} | ' + ' | '.join(cells) + ' |')
print('| geomean | - | ' + ' | '.join(
    f'{math.exp(sum(logs[n]) / len(logs[n])):.2f}' if logs[n] else '-' for n in names[1:]) + ' |')
