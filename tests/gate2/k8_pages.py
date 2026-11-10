"""Per page: PHP against the JVM, one context, steady = the last N renders. Usage: python -I k8_pages.py DIR PHP_PREFIX JVM_PREFIX [N]
Reads DIR/PREFIX{1..5}.1.json (k8-cli.sh's outputs) and .err."""
import math
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))    # python -I leaves the script's directory out
from k8_traj import documents, decile, peak

directory, php, jvm = sys.argv[1:4]
steady = int(sys.argv[4]) if len(sys.argv) > 4 else 1000
pages = ['/', '/?p=1', '/?page_id=2', '/?cat=1', '/?s=hello']
print('| page | PHP p10 / median / mean ms | JVM p10 / median / mean ms | JVM over PHP: p10 | median | mean | best JVM render | renders | same output | peak MB PHP / JVM |')
print('|---|---|---|---|---|---|---|---|---|---|')
ratios = [[], [], []]
for i, page in enumerate(pages, 1):
    a = os.path.join(directory, f'{php}{i}.1.json')
    b = os.path.join(directory, f'{jvm}{i}.1.json')
    if not (os.path.exists(a) and os.path.exists(b) and os.path.getsize(a) and os.path.getsize(b)):
        continue
    da = documents(a)[-1]['iterations']
    db = documents(b)[-1]['iterations']
    ta = [r[0] / 1e6 for r in da][200:200 + steady]    # PHP's level drifts up along the loop: its early renders are its best
    tb = [r[0] / 1e6 for r in db][-steady:]
    sa = (decile(ta), statistics.median(ta), statistics.mean(ta))
    sb = (decile(tb), statistics.median(tb), statistics.mean(tb))
    # the same render, iteration by iteration (the pages carry counters, so iteration i is compared with iteration i)
    n = min(len(da), len(db))
    equal = sum(1 for x, y in zip(da[:n], db[:n]) if (x[2], x[3]) == (y[2], y[3]))
    lengths = sum(1 for x, y in zip(da[:n], db[:n]) if x[2] == y[2])
    same = f'{equal}/{n}' + ('' if equal == n else f' (lengths {lengths}/{n})')
    r = [y / x for x, y in zip(sa, sb)]
    for k in range(3):
        ratios[k].append(r[k])
    print(f'| `{page}` | {sa[0]:.2f} / {sa[1]:.2f} / {sa[2]:.2f} | {sb[0]:.2f} / {sb[1]:.2f} / {sb[2]:.2f} | {r[0]:.2f} | {r[1]:.2f} | {r[2]:.2f} '
          f'| {min(x[0] for x in db) / 1e6:.2f} | {len(da)} / {len(db)} | {same} | {peak(a)} / {peak(b)} |')
if ratios[0]:
    g = [math.exp(sum(math.log(x) for x in rs) / len(rs)) for rs in ratios]
    print(f'| geometric mean | | | {g[0]:.2f} | {g[1]:.2f} | {g[2]:.2f} | | | | |')
