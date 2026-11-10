"""Settled value (first decile of the last half) and timeline per kernel.
Usage: python -I tl.py STEP PHPFILE(.out or -) FILE.out [FILE.out ...]"""
import collections, sys
def load(f):
    rows = collections.defaultdict(list); order = []
    for line in open(f, encoding='utf-8', errors='replace'):
        p = line.split()
        if len(p) >= 3 and p[1].isdigit():
            if p[0] not in rows: order.append(p[0])
            try: rows[p[0]].append(float(p[2]))
            except ValueError: pass
    return rows, order
def settled(v):
    t = sorted(v[len(v) // 2:]); return t[len(t) // 10]
step = int(sys.argv[1])
php = load(sys.argv[2])[0] if sys.argv[2] != '-' else {}
for f in sys.argv[3:]:
    rows, order = load(f)
    print(f)
    for k in order:
        v = rows[k]; s = settled(v)
        x = f"{s / settled(php[k]):6.2f}" if k in php else '     -'
        print(f"  {k:17}{s:7.2f}{x}   " + ' '.join(f"{min(v[i:i + step]):.2f}" for i in range(0, len(v), step)))
