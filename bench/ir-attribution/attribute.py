"""IR attribution: which code the compiled PHP functions' IR comes from, without comparing builds change by change.

Every node of a Truffle compilation's graph after the Truffle tier (partial evaluation and its cleanups, the IR the
bc-compile-cost logs call "IR after PE") carries its source position when dumped with -Djdk.graal.
TrackNodeSourcePosition=true and -Dpolyglot.compiler.NodeSourcePositions=true: the Java method it was made in and,
through the callers, the chain of methods partial evaluation inlined. Each node is counted
- for its bytecode operation (the interpreter's handleX that the chain passes through; "(dispatch)" for the loop's
  own nodes, "(prologue)" for the root's entry outside any operation),
- for its method: the innermost method of the project (pl.codeloom), else the innermost of any,
- inclusively, once for every project method on its chain.

summarize DIR OUT.json   reads DIR/*.bgv (bench/ir-attribution/dump.sh writes them), writes the counts per tier
report A.json [B.json] [--top N] [--tier 1|2]   the counts of A, or B against A (B - A, by how much each grew)
"""
import collections
import glob
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bgv  # noqa: E402

GRAPH = 'After TruffleTier'
PROJECT = 'pl.codeloom.php.'


def short(method):
    s = f'{method.cls}.{method.name}'
    return s[len(PROJECT):] if s.startswith(PROJECT) else s


def operation(chain):
    for p in chain:
        name = p.method.name if p.method else ''
        if name.startswith('handle') and 'CachedBytecodeNode' in str(p.method.cls):
            return name[len('handle'):].rstrip('_')
    for p in chain:
        if p.method and p.method.name.startswith('continueAt'):
            return '(dispatch)'
    return '(prologue)'


def summarize(directory, out):
    counts = {}
    for path in sorted(glob.glob(os.path.join(directory, '*.bgv'))):
        for groups, title, nodes in bgv.read(path):
            if title != GRAPH or not groups or 'AST' in groups[-1] or 'Call Tree' in groups[-1]:
                continue
            m = re.match(r'TruffleIR\.Tier(\d)\.(.*)', groups[-1])
            if not m:
                continue
            tier, function = m.group(1), m.group(2)
            c = counts.setdefault(tier, {'nodes': 0, 'functions': collections.Counter(), 'ops': collections.Counter(),
                                         'self': collections.Counter(), 'inclusive': collections.Counter(),
                                         'compilations': 0})
            c['compilations'] += 1
            for n in nodes:
                c['nodes'] += 1
                c['functions'][function] += 1
                pos = n.props.get('nodeSourcePosition')
                if not isinstance(pos, bgv.Position):
                    c['ops']['(no position)'] += 1
                    c['self']['(no position)'] += 1
                    continue
                chain = [p for p in pos.chain() if p.method is not None]
                c['ops'][operation(chain)] += 1
                own = next((p for p in chain if str(p.method.cls).startswith(PROJECT)), chain[0] if chain else None)
                c['self'][short(own.method) if own else '?'] += 1
                for name in {short(p.method) for p in chain if str(p.method.cls).startswith(PROJECT)}:
                    c['inclusive'][name] += 1
    with open(out, 'w') as f:
        json.dump(counts, f)
    for tier, c in sorted(counts.items()):
        print(f'tier {tier}: {c["compilations"]} compilations, {c["nodes"]} nodes')


def report(a_path, b_path, top, tiers):
    a = json.load(open(a_path))
    b = json.load(open(b_path)) if b_path else None
    for tier in sorted(a):
        if tiers and tier not in tiers:
            continue
        ca, cb = a[tier], (b or {}).get(tier)
        if cb is None:
            print(f'== tier {tier}: {ca["compilations"]} compilations, {ca["nodes"]} nodes')
        else:
            # the functions both compiled, so the totals compare like with like
            both = set(ca['functions']) & set(cb['functions'])
            na = sum(ca['functions'][f] for f in both)
            nb = sum(cb['functions'][f] for f in both)
            print(f'== tier {tier}: {len(both)} functions compiled by both: {na} -> {nb} nodes '
                  f'({nb / na:.3f}); all: {ca["nodes"]} -> {cb["nodes"]}')
        for key, label in (('ops', 'by bytecode operation'), ('self', 'by method (innermost of the project)'),
                           ('inclusive', 'inclusive (every project method on the chain)')):
            print(f'-- {label}')
            if cb is None:
                total = ca['nodes']
                for name, n in collections.Counter(ca[key]).most_common(top):
                    print(f'{n:9d} {100 * n / total:5.1f}%  {name}')
            else:
                names = set(ca[key]) | set(cb[key])
                rows = sorted(names, key=lambda k: -(cb[key].get(k, 0) - ca[key].get(k, 0)))
                for name in rows[:top]:
                    x, y = ca[key].get(name, 0), cb[key].get(name, 0)
                    print(f'{y - x:+9d}  {x:8d} -> {y:8d}  {name}')
        print()


if __name__ == '__main__':
    args = sys.argv[1:]
    top = 30
    tiers = None
    if '--top' in args:
        i = args.index('--top')
        top = int(args[i + 1])
        del args[i:i + 2]
    if '--tier' in args:
        i = args.index('--tier')
        tiers = {args[i + 1]}
        del args[i:i + 2]
    if args and args[0] == 'summarize':
        summarize(args[1], args[2])
    elif args and args[0] == 'report':
        report(args[1], args[2] if len(args) > 2 else None, top, tiers)
    else:
        print(__doc__)
        sys.exit(2)
