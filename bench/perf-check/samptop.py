"""Sums Truffle CPU sampler histograms (one per request context) by function: self and total ms. Compares two.
Usage: python samptop.py A/cpu.txt B/cpu.txt [N]"""
import collections, re, sys
ROW = re.compile(r'^ (.+?)\s+\|\|\s+(\d+)ms\s+[\d.]+%\s+\|\|\s+(\d+)ms\s+[\d.]+%\s+\|\|')
def load(p):
    self_, total, n = collections.Counter(), collections.Counter(), 0
    for line in open(p, encoding='utf-8', errors='replace'):
        m = ROW.match(line)
        if m:
            name = m[1].strip().replace('/srv/target/gate2/wordpress/', '')
            total[name] += int(m[2]); self_[name] += int(m[3])
    return self_, total
a, at = load(sys.argv[1]); b, bt = load(sys.argv[2])
sa, sb = sum(a.values()), sum(b.values())
print(f'self time total: A {sa} ms, B {sb} ms ({sb / sa:.3f})')
top = int(sys.argv[3]) if len(sys.argv) > 3 else 40
print('-- largest self-time increases B - A (ms), A, B')
for k in sorted(set(a) | set(b), key=lambda k: -(b[k] - a[k]))[:top]:
    print(f'{b[k] - a[k]:+7d} {a[k]:7d} {b[k]:7d}  {k[:100]}')
print('-- largest decreases')
for k in sorted(set(a) | set(b), key=lambda k: (b[k] - a[k]))[:15]:
    print(f'{b[k] - a[k]:+7d} {a[k]:7d} {b[k]:7d}  {k[:100]}')
print('-- top self in B')
for k, v in b.most_common(25):
    print(f'{v:7d} {a[k]:7d}  {k[:100]}')
