"""The checks' results store: one JSON object per line in target/check/results.jsonl (wiki/checks.md).

A record: {"t": ISO time, "sha": build, "level": L0|L1|L2, "check": NAME, "metric": NAME, "value": NUMBER,
"unit": ..., "names": [...] (the failing tests, when the metric counts them), "base": build compared with (ratios),
"iqr": [low, high] (ratios), "host": {...} (preflight's conditions)}. Records are only appended: the report takes the
latest per (sha, check, metric) (per base for ratios).

Each subcommand reads one existing tool's output and appends its records:
  smoke SHA FILE              tests/smoke/run.sh's output: the FAIL lines
  phpt SHA DIR                tests/phpt/run.sh's report.txt and failures.txt
  fixtures SHA FILE           bench/destructors/fixtures.sh's output: lines matching neither PHP behaviour
  surefire SHA DIR            target/surefire-reports: failed tests per class (CompilationTest: IR budgets)
  wp SHA FILE                 tests/wp-phpunit/compare.py --frozen's output: tests that newly differ
  gate1 SHA BASE DIR          bench/gate1/ab.sh's runs (DIR/INDEX-ROUND.txt, BASE index 0, SHA index 1): per kernel
                              the steady-state ms and the per-round ratio to BASE (median, IQR); peak memory; with an
                              index 2 (CONTROL: BASE again) the A/A ratios as check "gate1 control"
  gate2 SHA BASE DIR [LOGS]   tests/gate2/native.sh's runs (DIR/jvm-java.VARIANT-ROUND.json): warm CPU-s and wait per
                              visit (last 50), the cold visit, memory.peak, as values and per-round ratios; with LOGS
                              (the rounds' directories with engine-VARIANT.log) the compilations done
Common options: --level L, --host JSON (preflight's last line).

Usage: python -I results.py SUBCOMMAND ARGS... [--level L] [--host JSON]
"""
import datetime
import glob
import json
import os
import re
import statistics
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
STORE = os.environ.get('CHECK_STORE') or os.path.join(ROOT, 'target', 'check', 'results.jsonl')
WINDOW = 50


def append(records, level, host):
    os.makedirs(os.path.dirname(STORE), exist_ok=True)
    now = datetime.datetime.now().astimezone().isoformat(timespec='seconds')
    with open(STORE, 'a', encoding='utf-8') as out:
        for r in records:
            r = {'t': now, 'level': level, **r}
            if host:
                r['host'] = host
            out.write(json.dumps(r) + '\n')
            print(f"{r['sha']} {r['check']} {r['metric']} = {r['value']}"
                  + (f" (vs {r['base']}, IQR {r['iqr'][0]:.2f}-{r['iqr'][1]:.2f})" if 'iqr' in r else '')
                  + (f" [{len(r['names'])} names]" if r.get('names') else ''))


def read(path):
    return open(path, encoding='utf-8', errors='replace').read()


def iqr(values):
    values = sorted(values)
    q = len(values) // 4
    return [values[q], values[-1 - q]]


def ratio_records(sha, base, check, metric, unit, ours, theirs):
    """Per-round values of both builds (paired by round): the medians, and the ratio's median and IQR."""
    pairs = [(a, b) for a, b in zip(theirs, ours) if a and b]
    records = [{'sha': s, 'check': check, 'metric': metric, 'value': round(statistics.median(values), 4),
                'unit': unit, 'rounds': len(values)} for s, values in ((base, theirs), (sha, ours)) if values]
    if pairs:
        ratios = [b / a for a, b in pairs]
        records.append({'sha': sha, 'base': base, 'check': check, 'metric': metric + ' ratio',
                        'value': round(statistics.median(ratios), 4), 'unit': 'x',
                        'iqr': [round(v, 4) for v in iqr(ratios)], 'rounds': len(pairs)})
    return records


def smoke(sha, path):
    names = sorted({m.group(1) for m in re.finditer(r'^FAIL (\S+)', read(path), re.M)})
    return [{'sha': sha, 'check': 'smoke', 'metric': 'failing', 'value': len(names), 'unit': 'tests',
             'names': names}]


def phpt(sha, directory):
    report = read(os.path.join(directory, 'report.txt'))
    m = re.search(r'cases compared: (\d+), passing: (\d+) \([\d.]+%\) strict, (\d+)', report)
    names = sorted({line.split('\t')[0] for line in read(os.path.join(directory, 'failures.txt')).splitlines()
                    if line.strip()})
    return [{'sha': sha, 'check': 'phpt', 'metric': 'passing strict', 'value': int(m.group(2)), 'unit': 'cases',
             'of': int(m.group(1))},
            {'sha': sha, 'check': 'phpt', 'metric': 'passing lenient', 'value': int(m.group(3)), 'unit': 'cases'},
            {'sha': sha, 'check': 'phpt', 'metric': 'failing', 'value': len(names), 'unit': 'cases',
             'names': names}]


def fixtures(sha, path):
    text = read(path)
    neither = sum(int(n) for n in re.findall(r'(\d+) as neither', text))
    names = re.findall(r'^(\S+): (?:line counts differ|.*[1-9]\d* as neither)', text, re.M)
    return [{'sha': sha, 'check': 'fixtures', 'metric': 'lines matching neither PHP', 'value': neither,
             'unit': 'lines', 'names': sorted(set(names))}]


def surefire(sha, directory):
    records = []
    for path in sorted(glob.glob(os.path.join(directory, '*.txt'))):
        text = read(path)
        m = re.search(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)', text)
        if not m:
            continue
        suite = os.path.basename(path)[:-4].rsplit('.', 1)[-1]
        failed = sorted(set(re.findall(r'^(\S+?)(?:\(.*?\))?\s+Time elapsed.*<<< (?:FAILURE|ERROR)!', text, re.M)))
        budgets = re.findall(r'(\S+: \S+ compiled to \d+ IR nodes, its budget is \d+)', text)
        records.append({'sha': sha, 'check': 'unit', 'metric': f'{suite} failing',
                        'value': int(m.group(2)) + int(m.group(3)), 'unit': 'tests', 'of': int(m.group(1)),
                        'names': failed + budgets})
    return records


def wp(sha, path):
    text = read(path)
    new = sorted(set(re.findall(r'^\s+NEW (\S+)\t', text, re.M)))
    agree = sorted(set(re.findall(r'^\s+AGREES (\S+)\t', text, re.M)))
    records = [{'sha': sha, 'check': 'wp-phpunit', 'metric': 'newly differing', 'value': len(new), 'unit': 'tests',
                'names': new},
               {'sha': sha, 'check': 'wp-phpunit', 'metric': 'now agreeing', 'value': len(agree), 'unit': 'tests',
                'names': agree}]
    m = re.search(r'in both: (\d+), same outcome (\d+), different (\d+)', text)
    if m:
        records.append({'sha': sha, 'check': 'wp-phpunit', 'metric': 'same outcome as PHP', 'value': int(m.group(2)),
                        'unit': 'tests', 'of': int(m.group(1))})
    return records


def gate1(sha, base, directory):
    sys.argv = ['ab.py', directory]  # ab.py runs its report at import: load its two readers by hand
    scope = {}
    source = read(os.path.join(ROOT, 'bench', 'gate1', 'ab.py'))
    exec(compile(source.split('\ndirectory, variants')[0], 'ab.py', 'exec'), scope)
    theirs, ours = scope['rounds'](directory, 0), scope['rounds'](directory, 1)
    records = []
    for kernel in [k for k in theirs[0] if k != 'memory']:
        records += ratio_records(sha, base, 'gate1', kernel + ' ms', 'ms', [r[kernel] for r in ours],
                                 [r[kernel] for r in theirs])
    records += ratio_records(sha, base, 'gate1', 'peak memory', 'MB', [r['memory'] or 0 for r in ours],
                             [r['memory'] or 0 for r in theirs])
    control = scope['rounds'](directory, 2)  # CONTROL=1: the base again (an A/A arm), as a ratio to the base
    if control:
        for kernel in [k for k in theirs[0] if k != 'memory']:
            r = ratio_records(sha, base, 'gate1 control', kernel + ' ms', 'ms', [c[kernel] for c in control],
                              [t[kernel] for t in theirs])
            records += [x for x in r if 'iqr' in x]
    return records


def runs_of(directory, variant):
    files = sorted(glob.glob(os.path.join(directory, f'jvm-java.{variant}-[0-9]*.json')),
                   key=lambda f: int(re.search(r'-(\d+)\.json$', f).group(1)))
    return [json.load(open(f)) for f in files]


def compilations(logs, variant):
    """Per round, the compilations done (TraceCompilation's "opt done" lines) of engine-VARIANT.log."""
    counts = []
    for d in sorted(glob.glob(os.path.join(logs, 'r[0-9]*')), key=lambda d: int(d.rsplit('r', 1)[1])):
        path = os.path.join(d, f'engine-{variant}.log')
        if os.path.exists(path):
            counts.append(sum(1 for line in open(path, encoding='utf-8', errors='replace') if 'opt done' in line))
    return counts


def gate2(sha, base, directory, logs=None):
    ours, theirs = runs_of(directory, sha), runs_of(directory, base)
    metrics = {
        'warm CPU-s per visit': lambda r: sum(w['cpu'] for w in r['warm'][-WINDOW:]) / len(r['warm'][-WINDOW:]),
        'warm wait per visit s': lambda r: statistics.median(w['wait'] for w in r['warm'][-WINDOW:]),
        'cold visit CPU-s': lambda r: r['cold']['cpu'],
        'cold visit wait s': lambda r: r['cold']['wait'],
        'memory.peak warm MB': lambda r: r['memory']['warm']['peak'],
    }
    records = []
    for metric, of in metrics.items():
        unit = metric.rsplit(' ', 1)[1] if metric.endswith(('MB', ' s')) else 'CPU-s'
        records += ratio_records(sha, base, 'gate2', metric, unit, [of(r) for r in ours], [of(r) for r in theirs])
    failed = sum((not r['cold']['ok']) + sum(not w['ok'] for w in r['warm']) for r in ours)
    records.append({'sha': sha, 'check': 'gate2', 'metric': 'failed visits', 'value': failed, 'unit': 'visits'})
    if logs:
        a, b = compilations(logs, sha), compilations(logs, base)
        if a and b:
            records += ratio_records(sha, base, 'gate2', 'compilations', 'compilations', a, b)
    return records


def main():
    args = sys.argv[1:]
    level = host = None
    if '--level' in args:
        i = args.index('--level'); level = args[i + 1]; del args[i:i + 2]
    if '--host' in args:
        i = args.index('--host'); host = json.loads(args[i + 1]); del args[i:i + 2]
    command, rest = args[0], args[1:]
    parsers = {'smoke': smoke, 'phpt': phpt, 'fixtures': fixtures, 'surefire': surefire, 'wp': wp, 'gate1': gate1,
               'gate2': gate2}
    if command not in parsers:
        print(__doc__)
        sys.exit(2)
    append(parsers[command](*rest), level, host)


if __name__ == '__main__':
    main()
