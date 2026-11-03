"""The A/B tables of wiki/bytecode-dsl.md's "Which splits pay": per variant the median over rounds (the range in
parentheses).

woo DIR TAG...: woo.sh load runs (bench/bc-split-audit/runs.sh ab) of DIR (target/ba-t), TAG = BUILD-VARIANT, its
  rounds TAG-rN. Per run, the measured 120 s (TAG-rN-1-16): orders/s, pages/s, p50/p99, CPU-s per page, memory.peak
  over the server's life (warm-up included); from the server's TraceCompilation log (DIR/abl/TAG-rN/engine.log):
  compilations and their time over the life and in the measured window, split copies compiled; from the last watch
  snapshot (DIR/watch/ID.txt): the code cache used and the compiler threads' CPU seconds.
g2 DIR VARIANT...: native.sh measure runs (runs.sh g2) of DIR (target/ba-g): compilations over the run
  (DIR/out/rN/engine-VARIANT.log), the code cache and compiler CPU (DIR/watch); the visits' own numbers are
  tests/gate2/visits_report.py's.

Usage: python -I ab_report.py woo DIR TAG... | g2 DIR VARIANT...
"""
import datetime
import glob
import os
import re
import statistics
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'tests', 'gate2'))
import woo_report  # noqa: E402

DONE = re.compile(r'^\[engine\] opt done\s+engine=\d+\s+id=\s*(\d+)\s+(.*?)\s*\|Tier (\d)\|Time\s+(\d+)\(.*?'
                  r'\|CodeSize\s+(\d+)\|.*?\|UTC ([0-9T:.-]+)')
FAILED = re.compile(r'^\[engine\] opt failed')


def compiles(path, start=None, end=None):
    n = ms = code = splits = failed = 0
    wn = wms = 0
    if not os.path.exists(path):
        return None
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            m = DONE.match(line)
            if m:
                n += 1
                ms += int(m.group(4))
                code += int(m.group(5))
                splits += '<split-' in m.group(2)
                if start is not None:
                    t = datetime.datetime.fromisoformat(m.group(6)).replace(tzinfo=datetime.timezone.utc).timestamp()
                    if start <= t <= end:
                        wn += 1
                        wms += int(m.group(4))
            elif FAILED.match(line):
                failed += 1
    return dict(n=n, s=ms / 1000, code_mb=code / 1e6, splits=splits, failed=failed, window_n=wn, window_s=wms / 1000)


def watch(directory, container):
    out = {}
    try:
        text = open(os.path.join(directory, 'watch', container + '.txt')).read()
        out['codecache_mb'] = sum(int(x) for x in re.findall(r"^CodeHeap '[^']+': size=\d+Kb used=(\d+)Kb", text,
                                                              re.M)) / 1024
        out['nmethods'] = int(re.search(r'nmethods=(\d+)', text).group(1))
    except (OSError, AttributeError):
        pass
    for name in (container + '.threads.txt', container + '.txt'):
        try:
            text = open(os.path.join(directory, 'watch', name)).read()
        except OSError:
            continue
        m = re.search(r'^TruffleCompiler \d+ ([\d.]+)', text, re.M)
        if m:
            out['compiler_cpu'] = max(out.get('compiler_cpu', 0), float(m.group(1)))
    return out


def med(values, fmt='{:.2f}'):
    values = [v for v in values if v is not None]
    if not values:
        return '-'
    m = statistics.median(values)
    if len(values) == 1:
        return fmt.format(m)
    return f'{fmt.format(m)} ({fmt.format(min(values))}-{fmt.format(max(values))})'


def woo(directory, tags):
    rows = []
    for tag in tags:
        runs = []
        for k6 in sorted(glob.glob(os.path.join(directory, 'load', f'{tag}-r[0-9]*-1-16.k6.json'))):
            name = k6[:-len('.k6.json')]
            run = os.path.basename(name)[:-len('-1-16')]
            n = woo_report.numbers(name)
            start = os.path.getmtime(name + '.dbstatus.before')
            end = os.path.getmtime(name + '.dbstatus.after')
            c = compiles(os.path.join(directory, 'abl', run, 'engine.log'), start, end) or {}
            container = open(name + '.id').read().split()[0]
            w = watch(directory, container)
            runs.append(dict(n, **{'c_' + k: v for k, v in c.items()}, **w))
        rows.append((tag, runs))
    cols = [('rounds', lambda rs: str(len(rs))),
            ('orders/s', lambda rs: med([r['orders_s'] for r in rs])),
            ('pages/s', lambda rs: med([r['pages_s'] for r in rs], '{:.1f}')),
            ('p50 ms', lambda rs: med([r['p50'] for r in rs], '{:.0f}')),
            ('p99 ms', lambda rs: med([r['p99'] for r in rs], '{:.0f}')),
            ('CPU-s/page', lambda rs: med([r['cpu_per_page'] for r in rs])),
            ('memory.peak MB', lambda rs: med([r['peak'] for r in rs], '{:.0f}')),
            ('compilations (split copies)', lambda rs: f"{med([r.get('c_n') for r in rs], '{:.0f}')}"
                                                     f" ({med([r.get('c_splits') for r in rs], '{:.0f}')})"),
            ('compile s, life / window', lambda rs: f"{med([r.get('c_s') for r in rs], '{:.0f}')} /"
                                                   f" {med([r.get('c_window_s') for r in rs], '{:.0f}')}"),
            ('compiler CPU-s', lambda rs: med([r.get('compiler_cpu') for r in rs], '{:.0f}')),
            ('code compiled MB', lambda rs: med([r.get('c_code_mb') for r in rs], '{:.0f}')),
            ('code cache used MB', lambda rs: med([r.get('codecache_mb') for r in rs], '{:.0f}')),
            ('errors', lambda rs: str(sum(r['php_errors'] + r['orders_failed'] + r['check_fails'] for r in rs)))]
    print('| variant | ' + ' | '.join(c for c, _ in cols) + ' |')
    print('|---|' + '---|' * len(cols))
    for tag, rs in rows:
        print(f'| {tag} | ' + ' | '.join(f(rs) for _, f in cols) + ' |')


def g2(directory, variants):
    print('| variant | rounds | compilations (split copies) | compile s | compiler CPU-s | code compiled MB |'
          ' code cache used MB |')
    print('|---|---|---|---|---|---|---|')
    for v in variants:
        runs = []
        for idf in sorted(glob.glob(os.path.join(directory, 'runs', f'jvm-java.{v}-[0-9]*.id'))):
            r = re.search(r'-(\d+)\.id$', idf).group(1)
            container = open(idf).read().split()[0]
            c = compiles(os.path.join(directory, 'out', f'r{r}', f'engine-{v}.log')) or {}
            runs.append(dict(c, **watch(directory, container)))
        print(f'| {v} | {len(runs)} | {med([r.get("n") for r in runs], "{:.0f}")}'
              f' ({med([r.get("splits") for r in runs], "{:.0f}")}) | {med([r.get("s") for r in runs], "{:.0f}")} |'
              f' {med([r.get("compiler_cpu") for r in runs], "{:.0f}")} | {med([r.get("code_mb") for r in runs], "{:.0f}")} |'
              f' {med([r.get("codecache_mb") for r in runs], "{:.0f}")} |')


if __name__ == '__main__':
    if sys.argv[1] == 'woo':
        woo(sys.argv[2], sys.argv[3:])
    else:
        g2(sys.argv[2], sys.argv[3:])
