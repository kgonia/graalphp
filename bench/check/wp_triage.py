"""Triage of the WordPress PHPUnit suite's new differences (check.sh l1 with WP=1, wiki/checks.md).

A new difference against the frozen fail-set may come from the build's code, from an option it ran with (e.g.
-Dphp.rc.destructors=true), from the base (main moved since the fail-set was frozen) or from a test that isn't
stable. check.sh runs the tests again, only the classes with a new difference (select), on the same build with the
same options, on the same build without them, and on the base; classify then names a cause per test:

  unstable   the same build and options, run again, give PHP's outcome
  base       the base gives a different outcome from PHP too (fix it on main; it doesn't block the build)
  option     without the options the build gives PHP's outcome (the option's code: the destructors, ...)
  build      the build gives the difference without the options, and the base doesn't
  not rerun  a rerun has no outcome for the test (it didn't run: a crash, a data provider)

  select WP_TXT DEVELOP      two lines: a PHPUnit --filter of the classes with a new difference, and run.sh's
                             CHUNKS (the suite's entries that hold them)
  classify WP_TXT REF AGAIN NOOPTS BASE
                             the runs' directories (NOOPTS "-" when there were no options); "CAUSE<TAB>NAME" lines

Usage: python -I wp_triage.py select ... | classify ...
"""
import os
import re
import sys


def new_names(path):
    text = open(path, encoding='utf-8', errors='replace').read()
    return sorted(set(re.findall(r'^\s+NEW (\S.*?)\t', text, re.M)))


def outcomes(run):
    found = {}
    path = os.path.join(run, 'outcomes.tsv')
    if not os.path.exists(path):
        return found
    for line in open(path, encoding='utf-8', errors='replace'):
        if line.startswith('#'):
            continue
        parts = line.rstrip('\n').split('\t')
        if len(parts) >= 2:
            found[parts[0]] = parts[1]
    return found


def select(wp_txt, develop):
    classes = sorted({n.split('::', 1)[0] for n in new_names(wp_txt)})
    tests = os.path.join(develop, 'tests', 'phpunit', 'tests')
    entries = set()
    pattern = re.compile(r'^\s*(?:abstract\s+|final\s+)?class\s+(' + '|'.join(map(re.escape, classes)) + r')\b',
                         re.M | re.I)
    for root, _, files in os.walk(tests):
        for f in files:
            if not f.endswith('.php'):
                continue
            path = os.path.join(root, f)
            if pattern.search(open(path, encoding='utf-8', errors='replace').read()):
                rel = os.path.relpath(path, tests).replace(os.sep, '/')
                entries.add('tests/phpunit/tests/' + rel.split('/', 1)[0])
    print('/^(?:' + '|'.join(re.escape(c).replace('/', '\\/') for c in classes) + ')::/')
    print('^(' + '|'.join(re.escape(e) for e in sorted(entries)) + ')$')


def classify(wp_txt, ref, again, noopts, base):
    ref, again, base = outcomes(ref), outcomes(again), outcomes(base)
    noopts = outcomes(noopts) if noopts != '-' else None
    for name in new_names(wp_txt):
        php = ref.get(name)
        if name not in again or name not in base or (noopts is not None and name not in noopts):
            cause = 'not rerun'
        elif again[name] == php:
            cause = 'unstable'
        elif base[name] != php:
            cause = 'base'
        elif noopts is not None and noopts[name] == php:
            cause = 'option'
        else:
            cause = 'build'
        print(f'{cause}\t{name}')


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    if len(sys.argv) > 1 and sys.argv[1] == 'select':
        select(*sys.argv[2:4])
    elif len(sys.argv) > 1 and sys.argv[1] == 'classify':
        classify(*sys.argv[2:7])
    else:
        print(__doc__)
        sys.exit(2)
