"""k8_cycle.py SERVER_LOG: in a server log with -Dpolyglot.engine.TraceCompilation=true, the targets Graal failed with
"Deopt taken too many times" (issues/103-exit-deopt-cycle.md): their events before and after the first K8 request (the
first event of the K8 plugin's file after the warm-up), the totals, the compiler time after that request."""
import io, re, sys
ev = re.compile(r'\[engine\] opt (done|failed|deopt|inval\.|reprof)\s+engine=\d+\s+id=(\d+)\s+(.*?)\s*\|(.*)')
rows = []
for line in io.open(sys.argv[1], encoding='utf-8', errors='replace'):
    m = ev.search(line)
    if not m:
        continue
    kind, tid, name, rest = m.groups()
    t = re.search(r'UTC \d{4}-\d\d-\d\dT(\d\d):(\d\d):(\d\d\.\d+)', rest)
    if not t:
        continue
    sec = int(t.group(1)) * 3600 + int(t.group(2)) * 60 + float(t.group(3))
    ms = re.search(r'Time\s+(\d+)\(', rest)
    rows.append((sec, kind, int(tid), name, int(ms.group(1)) if ms else 0, 'Invalidated  true' in rest or 'Invalidated true' in rest,
                 'Deopt taken too many times' in rest, re.search(r'Tier (\d)', rest).group(1) if 'Tier' in rest else ''))
def hms(s):
    return '%02d:%02d:%04.1f' % (s // 3600, s % 3600 // 60, s % 60)
start = rows[0][0]
plugin = [r for r in rows if r[3].endswith('mu-plugins/k8-plugin.php') and r[0] - start > 600]
t0 = plugin[0][0] if plugin else None
print('trace from', hms(start), 'first K8 request', hms(t0) if t0 else None)
cyc = [r for r in rows if r[6]]
print('totals: done', sum(r[1] == 'done' for r in rows), 'failed', sum(r[1] == 'failed' for r in rows), 'deopt',
      sum(r[1] == 'deopt' for r in rows), 'inval', sum(r[1] == 'inval.' for r in rows), 'reprof', sum(r[1] == 'reprof' for r in rows),
      'cycle failures', len(cyc))
if t0 is None:
    t0 = start + 720
tot_cyc = 0
for f in sorted(cyc, key=lambda r: r[0]):
    mine = [r for r in rows if r[2] == f[2]]
    b = [r for r in mine if r[0] < t0]
    a = [r for r in mine if r[0] >= t0]
    ad = [r for r in a if r[1] == 'deopt']
    ct = sum(r[4] for r in a if r[1] in ('done', 'failed'))
    tot_cyc += ct
    later = [r for r in a if r[0] > f[0]]
    print('%-34s id=%-5d before: %d done (tiers %s), %d deopt (%d inval) | after: first deopt %s, %d deopt (%d inval), %d done, %.1f s, failed %s tier %s (+%.0f s), events after failure %d'
          % (f[3][-34:], f[2], sum(r[1] == 'done' for r in b), ''.join(sorted(set(r[7] for r in b if r[1] == 'done'))),
             sum(r[1] == 'deopt' for r in b), sum(r[1] == 'deopt' and r[5] for r in b),
             hms(ad[0][0]) if ad else '-', len(ad), sum(r[5] for r in ad), sum(r[1] == 'done' for r in a), ct / 1000, hms(f[0]), f[7],
             f[0] - t0, len(later)))
after = [r for r in rows if r[0] >= t0 and r[1] in ('done', 'failed')]
print('compiler time after the first K8 request: the cycle targets %.1f s, all others %.1f s; deopts after it: %d'
      % (tot_cyc / 1000, sum(r[4] for r in after) / 1000 - tot_cyc / 1000, sum(r[1] == 'deopt' and r[0] >= t0 for r in rows)))
print('deopts before the first K8 request:', sum(r[1] == 'deopt' and r[0] < t0 for r in rows))
