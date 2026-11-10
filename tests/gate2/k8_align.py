"""Render p10 per time bucket beside compilations. Usage: python -I k8_align.py OUT.json ENGINE.log [BUCKET_S]"""
import datetime, os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))    # python -I leaves the script's directory out
from k8_traj import documents, decile
out, log = sys.argv[1], sys.argv[2]
bucket = float(sys.argv[3]) if len(sys.argv) > 3 else 30
it = documents(out)[-1]['iterations']
s0 = it[0][5]
rb = {}
for i, r in enumerate(it):
    b = int((r[5] - s0) / 1e9 // bucket)
    rb.setdefault(b, []).append((i, r[0] / 1e6))
ev = {}
t0 = None
for line in open(log, encoding='utf-8', errors='replace'):
    m = re.search(r'opt (done|inval\.|deopt|reprof)', line)
    u = re.search(r'UTC (\S+?)\|', line)
    if not m or not u:
        continue
    t = datetime.datetime.fromisoformat(u.group(1))
    if t0 is None:
        t0 = t
    b = int((t - t0).total_seconds() // bucket)
    k = m.group(1)
    if k == 'done':
        k = 'T' + re.search(r'\|Tier (\d)\|', line).group(1)
    e = ev.setdefault(b, {})
    e[k] = e.get(k, 0) + 1
print('t s | renders (first..last) | p10 ms | T1 | T2 | inval | deopt | reprof')
for b in sorted(set(rb) | set(ev)):
    r = rb.get(b, [])
    e = ev.get(b, {})
    rr = f'{r[0][0]}..{r[-1][0]}' if r else ''
    p = f'{decile([x[1] for x in r]):.1f}' if len(r) >= 5 else ''
    print(f'{b*bucket:5.0f} | {rr:>12} | {p:>6} | {e.get("T1",0):4d} | {e.get("T2",0):4d} | {e.get("inval.",0):3d} | {e.get("deopt",0):3d} | {e.get("reprof",0):3d}')
