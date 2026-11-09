"""Aggregates rc-audit groups (-Dphp.rc.audit.out) across requests by kind, class and where the object was made.
Usage: python bench/destructors/audit_groups.py AUDIT_FILE [KINDS, comma separated: EARLY,FREED,LATE,EXTRA]"""
import collections
import re
import sys

text = open(sys.argv[1], encoding='utf-8', errors='replace').read().splitlines()
groups = collections.OrderedDict()
section = None
pattern = re.compile(r'  (\d+) x (LATE|EXTRA|EARLY|FREED while held|zero, not freed)( [+-]\d+)?  (.*?)  made in (.*?)'
                     r'(  \(at (\d+) points\))?$')
for i, line in enumerate(text):
    if line.startswith('rc-audit ('):
        section = 'points' if 'points' in line else 'end'
        continue
    m = pattern.match(line)
    if not m:
        continue
    n, kind, cls, site = int(m.group(1)), m.group(2).split(' ')[0], m.group(4), m.group(5)
    site = re.sub(r'[A-Z]:[^ ]*[\\/]', '', site)
    example = text[i + 1].strip() if i + 1 < len(text) else ''
    g = groups.setdefault((kind, cls, site), {'objects': 0, 'requests': 0, 'sections': set(), 'example': example})
    g['objects'] += n
    g['requests'] += 1
    g['sections'].add(section)
order = {'EARLY': 0, 'FREED': 1, 'LATE': 2, 'EXTRA': 3, 'zero,': 4}
print('groups by kind:', dict(collections.Counter(k[0] for k in groups)))
wanted = sys.argv[2].split(',') if len(sys.argv) > 2 else None
for (kind, cls, site), g in sorted(groups.items(), key=lambda kv: (order.get(kv[0][0], 9), -kv[1]['objects'])):
    if wanted and kind not in wanted:
        continue
    print(f"{kind:6} {g['objects']:5} obj {g['requests']:3} req {'/'.join(sorted(g['sections'])):10} {cls}  @ {site}")
    print(f"        {g['example'][:300]}")
