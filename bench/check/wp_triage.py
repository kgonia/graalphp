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

A class run alone leaves out the tests before it in its process, so a difference that depends on what they leave
behind (state, the database, a late destructor) would pass there and look unstable. A test unstable in the class
reruns gets a second stage: its whole suite entries again, unfiltered (a chunk is one PHPUnit process in the full
run too, so the order and state are the full run's), on the same three configurations. It stays "unstable" only if
that rerun gives PHP's outcome as well; otherwise it gets the cause the entry reruns show, with " (in order)".

  select WP_TXT DEVELOP      two lines: a PHPUnit --filter of the classes with a new difference, and run.sh's
                             CHUNKS (the suite's entries that hold them)
  classify WP_TXT REF AGAIN NOOPTS BASE
                             the runs' directories (NOOPTS "-" when there were no options); "CAUSE<TAB>NAME" lines
  entries TRIAGE DEVELOP     run.sh's CHUNKS for the tests classify called unstable (nothing printed: none)
  order TRIAGE REF AGAIN NOOPTS BASE
                             TRIAGE again, the unstable ones classified by the entries' reruns

Usage: python -I wp_triage.py select|classify|entries|order ARGS...
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


def files_of(classes, develop):
    """The suite entries (tests/phpunit/tests/X) whose files declare one of classes."""
    tests = os.path.join(develop, 'tests', 'phpunit', 'tests')
    entries = set()
    pattern = re.compile(r'^\s*(?:abstract\s+|final\s+)?class\s+(?:' + '|'.join(map(re.escape, classes)) + r')\b',
                         re.M | re.I)
    for root, _, files in os.walk(tests):
        for f in files:
            path = os.path.join(root, f)
            if f.endswith('.php') and pattern.search(open(path, encoding='utf-8', errors='replace').read()):
                rel = os.path.relpath(path, tests).replace(os.sep, '/')
                entries.add('tests/phpunit/tests/' + rel.split('/', 1)[0])
    return sorted(entries)


def chunks_regex(entries):
    return '^(' + '|'.join(re.escape(e) for e in entries) + ')$'


def select(wp_txt, develop):
    classes = sorted({n.split('::', 1)[0] for n in new_names(wp_txt)})
    print('/^(?:' + '|'.join(re.escape(c).replace('/', r'\/') for c in classes) + ')::/')
    print(chunks_regex(files_of(classes, develop)))


def cause(name, php, again, noopts, base):
    if name not in again or name not in base or (noopts is not None and name not in noopts):
        return 'not rerun'
    if again[name] == php:
        return 'unstable'
    if base[name] != php:
        return 'base'
    if noopts is not None and noopts[name] == php:
        return 'option'
    return 'build'


def runs(ref, again, noopts, base):
    return outcomes(ref), outcomes(again), outcomes(noopts) if noopts != '-' else None, outcomes(base)


def classify(wp_txt, ref, again, noopts, base):
    ref, again, noopts, base = runs(ref, again, noopts, base)
    for name in new_names(wp_txt):
        print(f'{cause(name, ref.get(name), again, noopts, base)}\t{name}')


def triaged(path):
    return [line.rstrip('\n').split('\t', 1) for line in open(path, encoding='utf-8') if '\t' in line]


def entries(triage, develop):
    classes = sorted({name.split('::', 1)[0] for c, name in triaged(triage) if c == 'unstable'})
    if classes:
        print(chunks_regex(files_of(classes, develop)))


def order(triage, ref, again, noopts, base):
    ref, again, noopts, base = runs(ref, again, noopts, base)
    for c, name in triaged(triage):
        if c == 'unstable':
            c = cause(name, ref.get(name), again, noopts, base)
            c = c if c in ('unstable', 'not rerun') else c + ' (in order)'
        print(f'{c}\t{name}')


COMMANDS = {'select': (select, 2), 'classify': (classify, 5), 'entries': (entries, 2), 'order': (order, 5)}

if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print(__doc__)
        sys.exit(2)
    function, count = COMMANDS[sys.argv[1]]
    function(*sys.argv[2:2 + count])
