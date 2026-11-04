"""The tables of bench/perf-check/runs.sh's runs that the earlier reports don't print (wiki/roadmap.md, step 3).

gate1 DIR PHP.txt VARIANT...: Gate 1's runs (DIR/INDEX-ROUND.txt, bench/gate1/ab.sh's files merged by runs.sh gate1).
  Per kernel: each variant's steady state (each run's median of its last quarter of iterations, the median over
  rounds, the range), the median and IQR of the later variants' per-round ratios to the first, the warm-up curve
  (median over rounds of single iterations) and whether every iteration's checksum equals PHP 8.5's; then the peak
  memory (cgroup memory.peak).
threads DIR KIND VARIANT...: CPU per thread class (bench/bc-compile-cost/cpusplit.py's classes) and the
  compilations by tier from the engine's TraceCompilation log. KIND g2: Gate 2's runs (DIR = target/pc-g), the cold
  visit and the warm window (visits 151-200) per visit; KIND woo: the WooCommerce runs (DIR = target/pc-t), the
  server's life and the measured 120 s per page.

Usage: python -I report.py gate1 DIR PHP.txt VARIANT... | threads DIR g2|woo VARIANT...
"""
import collections
import glob
import json
import os
import re
import statistics
import sys
from datetime import datetime, timezone

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'tests', 'gate2'))

CURVE = [1, 2, 3, 5, 10, 20, 50, 100, 200, 300, 400]


def med(values, fmt='{:.2f}'):
    values = [v for v in values if v is not None]
    if not values:
        return '-'
    m = statistics.median(values)
    if len(values) == 1:
        return fmt.format(m)
    return f'{fmt.format(m)} ({fmt.format(min(values))}-{fmt.format(max(values))})'


def iqr(values):
    values = sorted(values)
    q = len(values) // 4
    return f'{statistics.median(values):.3f} (IQR {values[q]:.3f}-{values[-1 - q]:.3f})'


# ---------------------------------------------------------------------------------------------------- Gate 1

def gate1_run(path):
    times, sums, memory = collections.defaultdict(list), collections.defaultdict(set), None
    for line in open(path, encoding='utf-8', errors='replace'):
        parts = line.split()
        if len(parts) >= 4 and parts[1].isdigit():
            times[parts[0]].append(int(parts[2]) / 1e6)
            sums[parts[0]].add(parts[3])
        elif len(parts) == 2 and parts[0] == 'peak_memory':
            memory = int(parts[1]) / 2**20
    return times, sums, memory


def gate1(directory, php, variants):
    _, php_sums, php_memory = gate1_run(php)
    runs = []
    for i in range(len(variants)):
        files = sorted(glob.glob(os.path.join(directory, f'{i}-*.txt')), key=lambda f: int(f.rsplit('-', 1)[1][:-4]))
        runs.append([gate1_run(f) for f in files])
    kernels = list(runs[0][0][0])
    steady = [[{k: statistics.median(t[k][3 * len(t[k]) // 4:]) for k in kernels} for t, _, _ in rs] for rs in runs]
    print(f'rounds: ' + ', '.join(f'{v} {len(rs)}' for v, rs in zip(variants, runs)))
    print()
    head = ' | '.join(f'{v} ms' for v in variants) + ' | ' + ' | '.join(f'{v} / {variants[0]}' for v in variants[1:])
    print(f'| kernel | {head} | checksums = PHP |')
    print('|---|' + '---|' * (2 * len(variants) - 1) + '---|')
    for k in kernels:
        cells = [med([s[k] for s in st]) for st in steady]
        ratios = [iqr([b[k] / a[k] for a, b in zip(steady[0], st)]) for st in steady[1:]]
        same = all(sums[k] == php_sums.get(k) for rs in runs for _, sums, _ in rs)
        print(f'| {k} | ' + ' | '.join(cells + ratios) + f' | {"yes" if same else "NO"} |')
    mems = [[m for _, _, m in rs if m] for rs in runs]
    ratios = [iqr([b / a for a, b in zip(mems[0], m)]) for m in mems[1:]]
    print(f'| peak memory MB | ' + ' | '.join([med(m, '{:.0f}') for m in mems] + ratios) + ' | |')
    if php_memory:
        print(f'\nPHP 8.5: peak memory {php_memory:.0f} MB')
    print('\nWarm-up curve: the median over rounds of iteration N, ms\n')
    print('| kernel | variant | ' + ' | '.join(str(n) for n in CURVE) + ' |')
    print('|---|---|' + '---|' * len(CURVE))
    for k in kernels:
        for v, rs in zip(variants, runs):
            cells = []
            for n in CURVE:
                values = [t[k][n - 1] for t, _, _ in rs if len(t[k]) >= n]
                cells.append(f'{statistics.median(values):.1f}' if values else '-')
            print(f'| {k} | {v} | ' + ' | '.join(cells) + ' |')


# ---------------------------------------------------------------------------------------------- thread split

HEADER = re.compile(r'## usage_usec (\d+) clk_tck (\d+) time ([\d.]+)')
DONE = re.compile(r'^\[engine\] opt done .*?\|Tier (\d)\|Time\s+(\d+)\(\s*(\d+)\+(\d+)\s*\)ms.*?\|UTC ([0-9T:.-]+)')
CLASSES = ['requests', 'Truffle compiler', 'HotSpot compiler', 'GC and VM', 'other', 'not seen']


def snapshots(path):
    block = None
    for line in open(path, encoding='utf-8', errors='replace'):
        m = HEADER.match(line)
        if m:
            if block:
                yield block
            block = {'usage': int(m[1]) / 1e6, 'tick': int(m[2]), 'time': float(m[3]), 'threads': {}}
            continue
        parts = line.rstrip('\n').split('\t')
        if block and len(parts) == 3 and len(parts[2].split()) == 2:
            utime, stime = (int(x) for x in parts[2].split())
            block['threads'][parts[0]] = (parts[1], utime + stime)
    if block:
        yield block


def group(name):
    if name.startswith('Truffle'):
        return 'Truffle compiler'
    if name.startswith(('JVMCI', 'C1 Compiler', 'C2 Compiler')):
        return 'HotSpot compiler'
    if name.startswith(('GC Thread', 'G1 ', 'VM Thread', 'VM Periodic', 'Native Heap Tri', 'Monitor Deflati')):
        return 'GC and VM'
    if name.startswith(('pool-', 'HTTP-', 'req-rsp')):
        return 'requests'
    return 'other'


def split(snaps, start, end):
    """CPU-s per thread class from the last snapshot at or before START to the first at or after END."""
    first = max((s for s in snaps if s['time'] <= start), key=lambda s: s['time'], default=snaps[0])
    last = min((s for s in snaps if s['time'] >= end), key=lambda s: s['time'], default=snaps[-1])
    seen = {}
    for s in snaps:
        if first['time'] <= s['time'] <= last['time']:
            seen.update(s['threads'])
    groups = dict.fromkeys(CLASSES, 0.0)
    for tid, (name, ticks) in seen.items():
        groups[group(name)] += (ticks - first['threads'].get(tid, (name, 0))[1]) / first['tick']
    groups['not seen'] = (last['usage'] - first['usage']) - sum(groups.values())
    groups['total'] = last['usage'] - first['usage']
    return groups


def compilations(log, start=None, end=None):
    """Per tier: compilations finished in [START, END) (all without a window) and their compile time, s."""
    tiers = {'1': [0, 0.0], '2': [0, 0.0]}
    if not os.path.exists(log):
        return None
    for line in open(log, encoding='utf-8', errors='replace'):
        m = DONE.match(line)
        if not m:
            continue
        if start is not None:
            t = datetime.fromisoformat(m[5].replace('Z', '+00:00')).replace(tzinfo=timezone.utc).timestamp()
            if not start <= t < end:
                continue
        tier = tiers.setdefault(m[1], [0, 0.0])
        tier[0] += 1
        tier[1] += int(m[2]) / 1000
    return tiers


def threads(directory, kind, variants):
    windows = {'g2': ['cold visit', 'warm window (visits 151-200)', 'whole run'],
               'woo': ['server life', 'measured 120 s']}[kind]
    unit = {'g2': 'per visit', 'woo': 'per page'}[kind]
    table = collections.defaultdict(list)
    comp = collections.defaultdict(list)
    for v in variants:
        if kind == 'g2':
            for f in sorted(glob.glob(os.path.join(directory, 'runs', f'jvm-java.{v}-[0-9]*.json'))):
                r = re.search(r'-(\d+)\.json$', f).group(1)
                tf = os.path.join(directory, 'watch', f'jvm-java.{v}-{r}.threads.txt')
                if not os.path.exists(tf):
                    continue
                visits = json.load(open(f))
                snaps = list(snapshots(tf))
                ends = [visits['cold']['t']] + [w['t'] for w in visits['warm']]
                n = len(visits['warm'])
                spans = [(snaps[0]['time'], ends[0], 1), (ends[n - 50], ends[n], 50), (snaps[0]['time'], ends[n], n + 1)]
                log = os.path.join(directory, 'out', f'r{r}', f'engine-{v}.log')
                for w, (a, b, count) in zip(windows, spans):
                    g = split(snaps, a, b)
                    table[(v, w)].append({k: x / count for k, x in g.items()})
                    c = compilations(log, a, b)
                    if c:
                        comp[(v, w)].append(c)
        else:
            for k6 in sorted(glob.glob(os.path.join(directory, 'load', f'{v}-r[0-9]*-1-16.k6.json'))):
                name = k6[:-len('.k6.json')]
                run = os.path.basename(name)[:-len('-1-16')]
                container = open(name + '.id').read().split()[0]
                tf = os.path.join(directory, 'watch', container + '.threads.txt')
                if not os.path.exists(tf):
                    continue
                import woo_report
                pages = woo_report.numbers(name)['pages_s'] * json.load(open(k6))['state']['testRunDurationMs'] / 1000
                snaps = list(snapshots(tf))
                a = os.path.getmtime(name + '.dbstatus.before')
                b = os.path.getmtime(name + '.dbstatus.after')
                log = os.path.join(directory, 'abl', run, 'engine.log')
                for w, (x, y, count) in zip(windows, [(snaps[0]['time'], snaps[-1]['time'], 1), (a, b, pages)]):
                    g = split(snaps, x, y)
                    table[(v, w)].append({k: z / count for k, z in g.items()})
                    c = compilations(log, x, y + 1 if w == 'server life' else y)
                    if c:
                        comp[(v, w)].append(c)
    fmt = '{:.1f}' if kind == 'woo' else '{:.2f}'
    print(f'CPU-s {unit} by thread class (woo: the server life in CPU-s), the median over rounds (range)\n')
    print('| variant | window | rounds | total | ' + ' | '.join(CLASSES) + ' | tier 1 done (s) | tier 2 done (s) |')
    print('|---|---|---|---|' + '---|' * len(CLASSES) + '---|---|')
    for w in windows:
        for v in variants:
            rows = table[(v, w)]
            f = '{:.1f}' if w in ('server life', 'cold visit') else '{:.3f}' if kind == 'woo' else fmt
            cells = [med([r['total'] for r in rows], f)] + [med([r[k] for r in rows], f) for k in CLASSES]
            cs = comp[(v, w)]
            tiers = [f"{med([c[t][0] for c in cs], '{:.0f}')} ({med([c[t][1] for c in cs], '{:.0f}')})" for t in '12']
            print(f'| {v} | {w} | {len(rows)} | ' + ' | '.join(cells + tiers) + ' |')


# ---------------------------------------------------------------------------------------- load-adjusted Gate 2

def solve(a, b):
    """Gaussian elimination for the normal equations (small, well-conditioned)."""
    n = len(b)
    m = [row[:] + [b[i]] for i, row in enumerate(a)]
    for c in range(n):
        p = max(range(c, n), key=lambda r: abs(m[r][c]))
        m[c], m[p] = m[p], m[c]
        for r in range(n):
            if r != c and m[c][c]:
                f = m[r][c] / m[c][c]
                m[r] = [x - f * y for x, y in zip(m[r], m[c])]
    return [m[i][n] / m[i][i] for i in range(n)]


def g2_runs(directory, variants):
    """Per Gate 2 run: variant, round, the host's median load, warm wait and CPU, request threads' CPU, peak."""
    rows = []
    for v in variants:
        # a run's name during a series is VARIANT-xROUND (runs.sh g2 renames it at the end)
        for f in sorted(glob.glob(os.path.join(directory, 'runs', f'jvm-java.{v}-*.json'))):
            m = re.search(r'-x?(\d+)\.json$', f)
            if not m:
                continue
            r = m.group(1)
            loads = sorted(int(x) for x in open(f[:-5] + '.host').read().split())
            visits = json.load(open(f))
            warm = visits['warm'][-50:]
            row = {'variant': v, 'round': int(r), 'host': loads[len(loads) // 2],
                   'wait': statistics.median(w['wait'] for w in warm),
                   'cpu': statistics.median(w['cpu'] for w in warm),
                   'cold': visits['cold']['wait'], 'coldcpu': visits['cold']['cpu'],
                   'peak': visits['memory'].get('end', visits['memory'].get('visit200', {})).get('peak')}
            tf = os.path.join(directory, 'watch', f'jvm-java.{v}-{r}.threads.txt')
            if os.path.exists(tf):
                snaps = list(snapshots(tf))
                ends = [visits['cold']['t']] + [w['t'] for w in visits['warm']]
                n = len(visits['warm'])
                g = split(snaps, ends[n - 50], ends[n])
                row['requests'] = g['requests'] / 50
                row['compiler'] = g['Truffle compiler'] / 50
            rows.append(row)
    return rows


def fit(directory, variants):
    """Per metric, y = b0 + b1 * host load + one offset per variant (the first is the reference), least squares
    over every run: each variant's offset against the first at the same host load, in percent of the first's mean."""
    rows = g2_runs(directory, variants)
    print('| run | host % | cold wait s | cold CPU-s | warm wait s | warm CPU-s | requests CPU-s |'
          ' Truffle compiler CPU-s | memory.peak MB |')
    print('|---|---|---|---|---|---|---|---|---|')
    for x in sorted(rows, key=lambda x: (x['round'], variants.index(x['variant']))):
        print(f"| {x['variant']}-{x['round']} | {x['host']} | {x['cold']:.2f} | {x['coldcpu']:.1f} |"
              f" {x['wait']:.3f} | {x['cpu']:.2f} |"
              f" {x.get('requests', float('nan')):.2f} | {x.get('compiler', float('nan')):.2f} |"
              f" {x['peak'] or float('nan'):.0f} |")
    print()
    print('Load-adjusted offsets against ' + variants[0] + ' (least squares over the runs: the metric against the'
          ' host load, a shift per build), in % of ' + variants[0] + "'s mean:\n")
    print('| metric | slope per 10 % host load | ' + ' | '.join(variants[1:]) + ' |')
    print('|---|---|' + '---|' * (len(variants) - 1))
    for metric in ('cold', 'coldcpu', 'wait', 'cpu', 'requests', 'compiler', 'peak'):
        data = [x for x in rows if x.get(metric) is not None]
        k = len(variants) + 1
        xs = [[1.0, x['host']] + [1.0 if x['variant'] == v else 0.0 for v in variants[1:]] for x in data]
        ys = [x[metric] for x in data]
        ata = [[sum(r[i] * r[j] for r in xs) for j in range(k)] for i in range(k)]
        aty = [sum(r[i] * y for r, y in zip(xs, ys)) for i in range(k)]
        b = solve(ata, aty)
        ref = statistics.mean(x[metric] for x in data if x['variant'] == variants[0])
        cells = [f'{100 * d / ref:+.1f} %' for d in b[2:]]
        print(f'| {metric} | {10 * b[1]:+.3f} | ' + ' | '.join(cells) + ' |')


if __name__ == '__main__':
    if sys.argv[1] == 'gate1':
        gate1(sys.argv[2], sys.argv[3], sys.argv[4:])
    elif sys.argv[1] == 'fit':
        fit(sys.argv[2], sys.argv[3:])
    else:
        threads(sys.argv[2], sys.argv[3], sys.argv[4:])
