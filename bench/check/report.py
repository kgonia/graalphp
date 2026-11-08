"""A build against its base from the checks' results store (results.py), as one Markdown report (wiki/checks.md).

Every check gets one verdict:
- ratios (gate1, gate2) against the thresholds below. "worse" when the median ratio is over the threshold and the
  IQR's lower end is over 1 (the slowdown shows in most rounds), "unclear" when the median is over it but the IQR
  reaches below 1 (noise: more rounds), "ok" otherwise. The thresholds are wiki/destructors.md's: Gate 1 ≤3%,
  Gate 2's warm CPU ≤5%.
- name sets (smoke, phpt, unit tests, fixtures): a ratchet. Names failing on the build and not on the base are
  "regressed" (the verdict is "worse"), names fixed are listed too; a count without the base's is reported as is.
- wp-phpunit's newly differing tests and the fixtures' lines matching neither PHP must be 0.

Usage: python -I report.py SHA BASE [--out FILE]
"""
import collections
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from results import STORE  # noqa: E402

THRESHOLDS = {
    ('gate1', 'peak memory ratio'): 1.05,
    ('gate2', 'warm CPU-s per visit ratio'): 1.05,
    ('gate2', 'warm wait per visit s ratio'): 1.05,
    ('gate2', 'cold visit CPU-s ratio'): 1.10,
    ('gate2', 'cold visit wait s ratio'): 1.10,
    ('gate2', 'memory.peak warm MB ratio'): 1.05,
    ('gate2', 'compilations ratio'): None,  # shown, no verdict: it explains the CPU
}
GATE1_KERNEL = 1.03
MUST_BE_ZERO = {('wp-phpunit', 'newly differing'), ('fixtures', 'lines matching neither PHP'),
                ('gate2', 'failed visits')}
NAME_SETS = {('smoke', 'failing'), ('phpt', 'failing'), ('fixtures', 'lines matching neither PHP')}


def latest(sha, base):
    """The latest record per (check, metric) of sha (ratios: against base)."""
    found = {}
    if not os.path.exists(STORE):
        return found
    for line in open(STORE, encoding='utf-8'):
        r = json.loads(line)
        if r['sha'] != sha or ('base' in r and r['base'] != base):
            continue
        found[(r['check'], r['metric'])] = r
    return found


def threshold(key):
    if key in THRESHOLDS:
        return THRESHOLDS[key]
    if key[0] == 'gate1' and key[1].endswith(' ms ratio'):
        return GATE1_KERNEL
    return None


def ratio_verdict(r, limit):
    if limit is None:
        return ''
    if r['value'] <= limit:
        return 'ok'
    return 'worse' if r['iqr'][0] > 1 else 'unclear'


def main():
    args = [a for a in sys.argv[1:] if a != '--out']
    out = sys.argv[sys.argv.index('--out') + 1] if '--out' in sys.argv else None
    if out:
        args.remove(out)
    sha, base = args[0], args[1]
    ours, theirs = latest(sha, base), latest(base, None)
    lines = [f'# Checks: {sha} against {base}', '']
    verdicts = collections.Counter()
    by_check = collections.defaultdict(list)
    for key, r in ours.items():
        by_check[key[0]].append((key, r))
    for check in sorted(by_check):
        lines += [f'## {check}', '', f'| metric | {base} | {sha} | ratio (IQR) | limit | verdict |',
                  '|---|---:|---:|---:|---:|---|']
        notes = []
        metrics = sorted({m[:-len(' ratio')] if m.endswith(' ratio') else m for (_, m), _ in by_check[check]})
        for metric in metrics:
            key = (check, metric)
            ratio = ours.get((check, metric + ' ratio'))
            r = ours.get(key) or {'metric': metric, 'value': None}
            mine = r['value']
            other = theirs.get(key, {}).get('value')
            limit = threshold((check, metric + ' ratio')) if ratio else None
            control = ours.get(('gate1 control', metric + ' ratio')) if check == 'gate1' else None
            if control and limit:
                # two identical builds differed this much in the same run: no smaller difference is a finding
                limit = round(max(limit, 1 + abs(control['value'] - 1)), 2)
            verdict = ''
            cell = ''
            if ratio:
                verdict = ratio_verdict(ratio, limit) if check != 'gate1 control' else ''
                cell = f"{ratio['value']:.2f} ({ratio['iqr'][0]:.2f}–{ratio['iqr'][1]:.2f}, {ratio['rounds']} rounds)"
            if key in MUST_BE_ZERO:
                verdict = 'ok' if mine == 0 else 'worse'
            if key in NAME_SETS or (check == 'unit' and 'names' in r):
                if key in theirs and 'names' in theirs[key]:
                    new = sorted(set(r.get('names', [])) - set(theirs[key]['names']))
                    fixed = sorted(set(theirs[key]['names']) - set(r.get('names', [])))
                    verdict = verdict if verdict == 'worse' else ('worse' if new else 'ok')
                    if new:
                        notes.append(f"- **{r['metric']}, newly failing ({len(new)}):** " + ', '.join(new[:40])
                                     + (' …' if len(new) > 40 else ''))
                    if fixed:
                        notes.append(f"- {r['metric']}, fixed ({len(fixed)}): " + ', '.join(fixed[:40])
                                     + (' …' if len(fixed) > 40 else ''))
                elif r.get('names'):
                    notes.append(f"- {r['metric']} ({len(r['names'])}): " + ', '.join(r['names'][:40])
                                 + (' …' if len(r['names']) > 40 else ''))
            fmt = (lambda v: '' if v is None else f'{v:.3f}' if isinstance(v, float) else str(v))
            lines.append(f"| {metric} | {fmt(other)} | {fmt(mine)} | {cell} "
                         f"| {'' if limit is None else limit} | {verdict} |")
            if verdict:
                verdicts[verdict] += 1
        lines += [''] + notes + ([''] if notes else [])
        tools = {r['tools'] for _, r in by_check[check] if 'tools' in r}
        tools |= {theirs[k]['tools'] for k, _ in by_check[check] if k in theirs and 'tools' in theirs[k]}
        if len(tools) > 1:
            lines += [f"**Measured with different tools ({', '.join(sorted(tools))}): rerun one side.**", '']
        elif tools:
            lines += [f'Tools: {tools.pop()}', '']
        hosts = {json.dumps(r['host'], sort_keys=True) for _, r in by_check[check] if 'host' in r}
        if hosts:
            lines += [f'Host: {h}' for h in sorted(hosts)] + ['']
    summary = ', '.join(f'{n} {v}' for v, n in sorted(verdicts.items())) or 'no verdicts'
    lines.insert(2, f'**{summary}.**\n')
    text = '\n'.join(lines)
    if out:
        open(out, 'w', encoding='utf-8').write(text)
    sys.stdout.reconfigure(encoding='utf-8')
    print(text)
    sys.exit(1 if verdicts.get('worse') else 0)


if __name__ == '__main__':
    main()
