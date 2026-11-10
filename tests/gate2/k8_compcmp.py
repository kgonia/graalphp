"""Two or more compilation traces side by side (-Dpolyglot.engine.TraceCompilation=true in a run's .err): per tier the
compilations, their time, IR and machine code; per trace the calls the last tier inlined and left as calls; and,
for the first two traces, the functions whose last-tier code grew most.

A function's splits are summed under its name: a split's number (`apply_filters <split-1234>`) names another call
site in another run, so comparing splits by number reports growth that isn't there (wiki/ceiling.md).

Usage: python -I k8_compcmp.py OLD.err NEW.err [MORE.err...] [--top N]
"""
import collections
import re
import sys

LINE = re.compile(r'opt done\s+engine=\d+\s+id=(\d+)\s+(.*?)\s*\|Tier (\d)\|Time\s+(\d+)\(\s*(\d+)\+(\d+)\s*\)ms'
                  r'\|AST\s+(\d+)\|Inlined\s+(\d+)Y\s+(\d+)N\|IR\s+(\d+)/\s*(\d+)\|CodeSize\s+(\d+)')
SPLIT = re.compile(r'\s*<split-\d+>')


def load(path):
    rows = []
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            m = LINE.search(line)
            if m:
                rows.append(dict(id=m.group(1), name=m.group(2), tier=int(m.group(3)), time=int(m.group(4)),
                                 pe=int(m.group(5)), inlined=int(m.group(8)), calls=int(m.group(9)),
                                 ir0=int(m.group(10)), ir1=int(m.group(11)), code=int(m.group(12))))
    return rows


def last_by_id(rows, tier):
    """The last compilation of each target at a tier: what is installed at the end."""
    out = {}
    for row in rows:
        if row['tier'] == tier:
            out[row['id']] = row
    return out


def by_name(last):
    out = collections.defaultdict(lambda: dict(code=0, ir1=0, time=0, copies=0))
    for row in last.values():
        entry = out[SPLIT.sub('', row['name'])]
        entry['code'] += row['code']
        entry['ir1'] += row['ir1']
        entry['time'] += row['time']
        entry['copies'] += 1
    return out


args = sys.argv[1:]
top = 20
if '--top' in args:
    at = args.index('--top')
    top = int(args[at + 1])
    del args[at:at + 2]
traces = [(path.replace('\\', '/').split('/')[-1], load(path)) for path in args]

print('| trace | tier | compilations | time s | before the back end s | IR after PE | final IR | code MB | mean ms | mean code B |')
print('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|')
for label, rows in traces:
    for tier in (1, 2):
        r = [x for x in rows if x['tier'] == tier]
        n = max(len(r), 1)
        time = sum(x['time'] for x in r)
        code = sum(x['code'] for x in r)
        print(f"| {label} | {tier} | {len(r)} | {time / 1000:.0f} | {sum(x['pe'] for x in r) / 1000:.0f} "
              f"| {sum(x['ir0'] for x in r)} | {sum(x['ir1'] for x in r)} | {code / 1e6:.1f} | {time / n:.0f} | {code / n:.0f} |")

print()
print('| trace | last-tier targets | calls inlined | left as calls | inlined share | installed last-tier code MB |')
print('|---|---:|---:|---:|---:|---:|')
for label, rows in traces:
    last = last_by_id(rows, 2)
    y = sum(x['inlined'] for x in last.values())
    n = sum(x['calls'] for x in last.values())
    print(f"| {label} | {len(last)} | {y} | {n} | {y / max(y + n, 1):.3f} | {sum(x['code'] for x in last.values()) / 1e6:.1f} |")

if len(traces) >= 2:
    old, new = by_name(last_by_id(traces[0][1], 2)), by_name(last_by_id(traces[1][1], 2))
    both = sorted(set(old) & set(new))
    so, sn = sum(old[n]['code'] for n in both), sum(new[n]['code'] for n in both)
    print(f"\nlast tier, {traces[0][0]} -> {traces[1][0]}: {len(old)} functions -> {len(new)}, {len(both)} in both; "
          f"their code {so / 1e6:.2f} -> {sn / 1e6:.2f} MB ({sn / max(so, 1):.2f}x), "
          f"final IR {sum(old[n]['ir1'] for n in both)} -> {sum(new[n]['ir1'] for n in both)}, "
          f"time {sum(old[n]['time'] for n in both) / 1000:.0f} -> {sum(new[n]['time'] for n in both) / 1000:.0f} s")
    print('\n| function (splits summed) | copies | code B | final IR | time ms |')
    print('|---|---:|---:|---:|---:|')
    for name in sorted(both, key=lambda n: new[n]['code'] - old[n]['code'], reverse=True)[:top]:
        a, b = old[name], new[name]
        print(f"| {name[:70]} | {a['copies']} -> {b['copies']} | {a['code']} -> {b['code']} | {a['ir1']} -> {b['ir1']} "
              f"| {a['time']} -> {b['time']} |")
