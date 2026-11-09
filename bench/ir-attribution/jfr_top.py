"""Sums a JFR recording's execution samples (jfr print --json --events jdk.ExecutionSample) per Java method:
self (the sampled frame), and inclusive (once per sample for every method on the stack), with the share of samples.
Usage: python -I jfr_top.py SAMPLES.json [TOP]
"""
import collections
import json
import sys

PROJECT = 'pl/codeloom/php/'


def name(frame):
    m = frame['method']
    cls = m['type']['name']
    cls = cls[len(PROJECT):] if cls.startswith(PROJECT) else cls
    return f"{cls.replace('/', '.')}.{m['name']}"


def main(path, top):
    events = json.load(open(path))['recording']['events']
    own, inclusive, project_own = collections.Counter(), collections.Counter(), collections.Counter()
    n = 0
    for e in events:
        frames = e['values']['stackTrace']['frames'] if e['values'].get('stackTrace') else []
        if not frames:
            continue
        n += 1
        own[name(frames[0])] += 1
        # the innermost frame of the project: which runtime code the time is under
        project = next((f for f in frames if f['method']['type']['name'].startswith(PROJECT)), None)
        project_own[name(project) if project else '(outside the project)'] += 1
        for k in {name(f) for f in frames}:
            inclusive[k] += 1
    print(f'{n} samples')
    for label, counter in (('self (sampled frame)', own), ('innermost project method', project_own),
                           ('inclusive', inclusive)):
        print(f'-- {label}')
        for k, c in counter.most_common(top):
            print(f'{100 * c / n:5.1f}%  {k}')


if __name__ == '__main__':
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 30)
