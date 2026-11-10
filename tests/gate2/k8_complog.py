"""Compilations finished along a run. Usage: python -I k8_complog.py ENGINE.log [BUCKET_S]"""
import re, sys, datetime
path = sys.argv[1]
bucket = float(sys.argv[2]) if len(sys.argv) > 2 else 20
rows = []
for line in open(path, encoding='utf-8', errors='replace'):
    if 'opt done' not in line:
        continue
    m = re.search(r'\|Tier (\d)\|Time\s+(\d+)\(.*?UTC (\S+?)\|', line)
    if not m:
        continue
    t = datetime.datetime.fromisoformat(m.group(3))
    name = line.split('id=')[1].split(None, 1)[1].split('|')[0].strip()
    rows.append((t, int(m.group(1)), int(m.group(2)), name))
t0 = rows[0][0]
buckets = {}
for t, tier, ms, name in rows:
    b = int((t - t0).total_seconds() // bucket)
    c = buckets.setdefault(b, [0, 0, 0, 0])
    c[tier - 1] += 1
    c[tier + 1] += ms
print(f'first {t0.time()} last {rows[-1][0].time()} total {len(rows)}')
print('t s | tier1 done | tier2 done | tier1 compile s | tier2 compile s')
for b in sorted(buckets):
    c = buckets[b]
    print(f'{b*bucket:5.0f} | {c[0]:4d} | {c[1]:4d} | {c[2]/1e3:6.1f} | {c[3]/1e3:6.1f}')
