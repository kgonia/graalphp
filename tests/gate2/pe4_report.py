"""Partial evaluation round 4's diagnosis (wiki/pe-round4-diagnosis.md): reads one engine log of a woo.sh diag run
and prints what the other reports don't.

- compile: the compilations by kind, originals against split copies, with their compile time (TraceCompilation),
  per tier; the targets with the most copies compiled; the largest and slowest compilations.
- splits: why call targets were split (engine.SplittingTraceEvents): the polymorphic events by the node class that
  reported them, the share of them that made a target split, the targets split most; the split decisions
  (engine.TraceSplitting); the engine's summary at its close (engine.TraceSplittingSummary).
- inlining: for the given targets (substrings of the name), Truffle's inlining decisions (compiler.TraceInlining):
  each compilation's inlined PHP callees with their IR nodes, the cut-offs, and the inlined callees summed over
  the target's compilations.
- expansion: for the given targets, the node expansion trees (compiler.TraceNodeExpansion=truffleTier): the IR
  nodes after partial evaluation of each compilation split into the function's own body and each inlined callee
  (by the callee's source line), and by Truffle node class; and the method expansion trees
  (compiler.TraceMethodExpansion=truffleTier) by Java method (self counts).
- deopts: invalidations and deoptimizations by reason and target (TraceCompilation).

Usage: python -I tests/gate2/pe4_report.py LOG compile|splits|deopts [--top N]
       python -I tests/gate2/pe4_report.py LOG inlining|expansion TARGET... [--top N]
"""
import argparse
import collections
import re
import sys

DONE = re.compile(r'^\[engine\] opt done\s+engine=\d+\s+id=\s*(\d+)\s+(.*?)\s*\|Tier (\d)\|Time\s+(\d+)\(\s*(\d+)\+(\d+)\s*\)ms'
                  r'\|AST\s+(\d+)\|Inlined\s+(\d+)Y\s+(\d+)N\|IR\s+(\d+)/\s*(\d+)\|CodeSize\s+(\d+)')
FAILED = re.compile(r'^\[engine\] opt failed\s+engine=\d+\s+id=\s*(\d+)\s+(.*?)\s*\|Tier (\d)\|Time\s+(\d+)\(.*?\|Reason: (.*?)(?:\|UTC|$)')
EVENT = re.compile(r'^\[engine\] opt (inval\.|deopt)\s+engine=\d+\s+id=\s*(\d+)\s+(.*?)\s*\|.*\|Reason (.*)$')
SPLIT = re.compile(r'^\[engine\] split\s+\S+\s+(.*?)\s*\|AST\s+(\d+)\|')
INLINE = re.compile(r'^\[engine\] (Inline start|Inline done|Inlined|Cutoff)\s+(.*?)\s*\|Frequency\s+([\d.,NaN]+)\|'
                    r'Recursion Depth\s+\d+\|IR Nodes\s+(-?\d+)\|.*?\|Depth\s+(\d+)\|')
EXPANSION = re.compile(r'^\[engine\] Expansion tree for (.*) after truffleTier:$')
ROW = re.compile(r'^(\s*)(\S.*?)\s+[\d.]+ \|\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+) \|\s+(\d+)\s+(\d+)'
                 r'\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+) \|(.*)$')
SPLIT_NAME = re.compile(r' <split-[0-9a-f]+>$')


def base(name):
    return SPLIT_NAME.sub('', name)


def lines(path):
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            yield line.rstrip('\n')


def compile_report(path, top):
    done, failed = [], []
    for line in lines(path):
        m = DONE.match(line)
        if m:
            g = m.groups()
            done.append(dict(id=int(g[0]), name=g[1], tier=int(g[2]), ms=int(g[3]), pe=int(g[4]), graal=int(g[5]),
                             ast=int(g[6]), inl=int(g[7]), ir=int(g[9]), ir2=int(g[10]), code=int(g[11])))
            continue
        m = FAILED.match(line)
        if m:
            failed.append((m.group(2), int(m.group(3)), int(m.group(4)), m.group(5)[:160]))
    total = sum(d['ms'] for d in done) or 1
    print(f'compilations done: {len(done)}, {total / 1000:.0f} s of compile time;'
          f' failed: {len(failed)}, {sum(f[2] for f in failed) / 1000:.0f} s')
    for tier in (1, 2):
        rows = [d for d in done if d['tier'] == tier]
        sp = [d for d in rows if '<split-' in d['name']]
        print(f'  tier {tier}: {len(rows)} compilations, {sum(d["ms"] for d in rows) / 1000:.0f} s;'
              f' split copies {len(sp)} ({100 * len(sp) / max(1, len(rows)):.0f}%),'
              f' {sum(d["ms"] for d in sp) / 1000:.0f} s ({100 * sum(d["ms"] for d in sp) / total:.0f}% of all compile time)')
    sp = [d for d in done if '<split-' in d['name']]
    print(f'  all: split copies {len(sp)} of {len(done)}, {sum(d["ms"] for d in sp) / 1000:.0f} s of {total / 1000:.0f} s'
          f' ({100 * sum(d["ms"] for d in sp) / total:.0f}%); their code {sum(d["code"] for d in sp) / 1e6:.0f} MB'
          f' of {sum(d["code"] for d in done) / 1e6:.0f} MB')
    ids = collections.Counter(d['id'] for d in done)
    print(f'  call targets compiled: {len(ids)}, of which compiled more than once: {sum(1 for n in ids.values() if n > 1)}')
    by = collections.defaultdict(lambda: [0, 0, 0, set()])
    for d in done:
        b = by[base(d['name'])]
        b[0] += 1
        b[1] += d['ms']
        b[2] += d['code']
        if '<split-' in d['name']:
            b[3].add(d['id'])
    print(f'\nfunctions by compile time over all their copies ({top}): compilations, split copies, s, MB of code')
    for name, b in sorted(by.items(), key=lambda kv: -kv[1][1])[:top]:
        print(f'  {b[1] / 1000:7.1f} s {b[0]:5d} comp {len(b[3]):5d} splits {b[2] / 1e6:6.1f} MB  {name}')
    print(f'\nfunctions by number of split copies compiled ({top}):')
    for name, b in sorted(by.items(), key=lambda kv: -len(kv[1][3]))[:top]:
        print(f'  {len(b[3]):5d} copies {b[0]:5d} comp {b[1] / 1000:7.1f} s  {name}')
    small = [d for d in sp if d['ast'] <= 100]
    print(f'\nsplit copies of functions of at most 100 AST nodes: {len(small)}, {sum(d["ms"] for d in small) / 1000:.0f} s')
    print(f'\nlargest compilations by IR nodes after PE ({top}): IR(PE), IR after, code KB, time s (PE+Graal), AST, inlined')
    for d in sorted(done, key=lambda d: -d['ir'])[:top]:
        print(f'  {d["ir"]:7d} {d["ir2"]:7d} {d["code"] // 1024:6d} KB {d["ms"] / 1000:6.1f} s ({d["pe"] / 1000:.1f}+'
              f'{d["graal"] / 1000:.1f}) AST {d["ast"]:5d} inl {d["inl"]:3d} T{d["tier"]} {d["name"]}')
    print(f'\nslowest compilations ({top}):')
    for d in sorted(done, key=lambda d: -d['ms'])[:top]:
        print(f'  {d["ms"] / 1000:6.1f} s IR {d["ir"]:7d} code {d["code"] // 1024:6d} KB T{d["tier"]} {d["name"]}')
    if failed:
        print('\nfailed compilations:')
        for name, tier, ms, reason in sorted(failed, key=lambda f: -f[2])[:top]:
            print(f'  {ms / 1000:6.1f} s T{tier} {name}: {reason}')


def splits_report(path, top):
    """The polymorphic events come from the compiler's and the request threads interleaved: an event is matched to
    its target by name, not by order. A target needs a split when a node in it reports polymorphism ("Set needs
    split to true") or when a callee with this one caller does ("... via parent")."""
    events = collections.Counter()           # node class -> polymorphic events
    classes_of = collections.defaultdict(collections.Counter)   # target (base name) -> node class -> events
    needs = collections.Counter()            # direct / via parent
    needs_of = collections.defaultdict(collections.Counter)
    outcome = collections.Counter()
    split_targets = collections.Counter()
    split_ast = {}
    rejected = collections.Counter()
    summary, in_summary = [], False
    for line in lines(path):
        if line.startswith('[engine] [poly-event]'):
            body = line[len('[engine] [poly-event]'):].strip()
            m = re.match(r'Polymorphic event! Source: (\S+?)@[0-9a-f]+\s+(.*\S)', body)
            if m:
                node = m.group(1)
                events[node] += 1
                classes_of[base(m.group(2))][node] += 1
                continue
            m = re.match(r'(Set needs split to true(?: via parent)?|Early return: (?:true|false)|Return: (?:true|false)'
                         r'|One caller! Analysing parent\.)(?:\s+callCount: \d+, numberOfKnownCallNodes: \d+)?\s+(.*\S)',
                         body)
            if m:
                outcome[m.group(1)] += 1
                if m.group(1).startswith('Set needs split'):
                    needs[m.group(1)] += 1
                    needs_of[base(m.group(2))][m.group(1)] += 1
            continue
        m = SPLIT.match(line)
        if m:
            name = m.group(1)
            t = re.match(r'Target too big: \d+ > \d+ (.*)', name)
            if t:
                rejected[base(t.group(1))] += 1
            else:
                split_targets[base(name)] += 1
                split_ast[base(name)] = int(m.group(2))
            continue
        if line.startswith('[engine] Splitting Statistics'):
            in_summary = True
            summary = [line]
        elif in_summary:
            # the summary's lines have no prefix; the next engine line ends it
            if line.startswith('[') or not line.strip():
                in_summary = False
            else:
                summary.append(line)
    print(f'polymorphic events: {sum(events.values())}; outcomes: {dict(outcome.most_common())}')
    splits = sum(split_targets.values())
    print(f'splits made (engine.TraceSplitting): {splits} of {len(split_targets)} functions;'
          f' refused as too big (over SplittingMaxCalleeSize): {sum(rejected.values())} of {len(rejected)} functions')
    # a node class is charged with the splits of every function whose own nodes reported it
    charged = collections.Counter()
    charged_targets = collections.Counter()
    for name, n in split_targets.items():
        for node in classes_of.get(name, {}):
            charged[node] += n
            charged_targets[node] += 1
    no_own = sum(n for name, n in split_targets.items() if name not in classes_of)
    print(f'\nnode classes reporting polymorphism ({top}): events; split functions whose own nodes reported it, and'
          f' their splits (a function with several classes counts for each)')
    for node, n in events.most_common(top):
        print(f'  {n:7d} {charged_targets[node]:5d} fns {charged[node]:6d} splits  {node}')
    print(f'  splits of functions none of whose own nodes reported polymorphism (split for a callee, via parent):'
          f' {no_own} of {splits}')
    print(f'\nfunctions split most ({top}): splits, AST, needs split direct / via parent, the node classes reporting in it')
    for name, n in split_targets.most_common(top):
        why = ', '.join(f'{k.split(".")[-1]} {v}' for k, v in classes_of[name].most_common(3)) or '-'
        nd = needs_of[name]
        print(f'  {n:6d} AST {split_ast[name]:4d} {nd["Set needs split to true"]:4d}/{nd["Set needs split to true via parent"]:<4d}'
              f' {name}   [{why}]')
    print(f'\nrefused splits, most ({min(top, 10)}):')
    for name, n in rejected.most_common(min(top, 10)):
        print(f'  {n:6d} {name}')
    if summary:
        print('\nengine.TraceSplittingSummary at the engine\'s close (first 60 lines):')
        print('\n'.join(summary[:60]))


def deopts_report(path, top):
    reasons = collections.Counter()
    targets = collections.defaultdict(collections.Counter)
    for line in lines(path):
        m = EVENT.match(line)
        if m:
            kind, _, name, reason = m.groups()
            reason = re.sub(r'@[0-9a-f]+', '', reason.strip())[:150]
            reasons[(kind, reason)] += 1
            targets[(kind, reason)][base(name)] += 1
    print(f'invalidations and deoptimizations by reason ({top}):')
    for (kind, reason), n in reasons.most_common(top):
        hit = ', '.join(f'{t} {c}' for t, c in targets[(kind, reason)].most_common(4))
        print(f'  {n:6d} {kind} {reason}\n         {hit}')


def matches(name, wanted):
    return any(w in name for w in wanted)


def inlining_report(path, wanted, top):
    comps = []
    cur = None
    for line in lines(path):
        m = INLINE.match(line)
        if not m:
            continue
        kind, name, _, ir, depth = m.groups()
        if kind == 'Inline start':
            cur = dict(name=name, ir=int(ir), inlined=[], cutoff=[]) if matches(name, wanted) else None
        elif kind == 'Inline done':
            if cur is not None:
                cur['ir_done'] = int(ir)
                comps.append(cur)
            cur = None
        elif cur is not None:
            (cur['inlined'] if kind == 'Inlined' else cur['cutoff']).append((int(depth), name, int(ir)))
    for c in comps:
        own = c['ir']
        inl = sum(ir for d, n, ir in c['inlined'] if d == 1)
        print(f'\n{c["name"]}: IR nodes {c["ir_done"]} at inlining\'s end (root {own}); {len(c["inlined"])} inlined'
              f' ({sum(1 for d, _, _ in c["inlined"] if d == 1)} direct callees, {inl} IR nodes estimated), {len(c["cutoff"])} cut off')
        agg = collections.Counter()
        cnt = collections.Counter()
        for d, n, ir in c['inlined']:
            agg[base(n)] += ir
            cnt[base(n)] += 1
        for n, ir in agg.most_common(top):
            print(f'    {ir:7d} IR  {cnt[n]:3d}x  {n}')


def expansion_trees(path, wanted):
    """Yields (target, rows) for each expansion tree of a wanted target: rows of (depth, name, count, self, rest)."""
    it = lines(path)
    for line in it:
        m = EXPANSION.match(line)
        if not m or not matches(m.group(1), wanted):
            continue
        rows = []
        next(it, None)
        for row in it:
            r = ROW.match(row)
            if not r:
                break
            # depth, name, count, self count, rest of the line, self invokes, self allocations
            rows.append((len(r.group(1)), r.group(2), int(r.group(3)), int(r.group(10)), r.group(17),
                         int(r.group(15)), int(r.group(16))))
        yield m.group(1), rows


def where(rest):
    """The source position column of a node expansion row: php:FILE:LINE:CHARS."""
    m = re.search(r'(php:\S+)', rest)
    return m.group(1) if m else ''


def expansion_report(path, wanted, top):
    for target, rows in expansion_trees(path, wanted):
        total = rows[0][2] if rows else 0
        nodes = any(r[1] in ('FunctionRootNode', 'OptimizedDirectCallNode', '<call-root>') for r in rows[:3])
        print(f'\n== {target}: {total} IR nodes after PE ({"node" if nodes else "method"} expansion)')
        if nodes:
            # own body against inlined callees: a row belongs to the innermost FunctionRootNode above it below an
            # OptimizedDirectCallNode; the root's FunctionRootNode is the function itself
            stack = []
            unit = collections.Counter()
            unit_of = {}
            by_class = collections.Counter()
            by_class_own = collections.Counter()
            calls = collections.Counter()
            for depth, name, count, self_count, rest, invokes, allocs in rows:
                while stack and stack[-1][0] >= depth:
                    stack.pop()
                parent_unit = stack[-1][1] if stack else '<call-root>'
                u = parent_unit
                if name in ('FunctionRootNode', 'BuiltinRootNode', 'FileRootNode') or name.endswith('RootNode'):
                    above = stack[-1][2] if stack else None
                    if above == 'OptimizedDirectCallNode':
                        u = f'inlined {name} at {where(rest)}'
                        calls[u] += 1
                    elif parent_unit == '<call-root>':
                        u = 'own body'
                stack.append((depth, u, name))
                unit[u] += self_count
                cls = re.sub(r'Gen$', '', name.split('$')[-1] if '$' in name else name)
                by_class[name] += self_count
                if u == 'own body':
                    by_class_own[name] += self_count
            inl = sum(v for k, v in unit.items() if k.startswith('inlined'))
            print(f'  own body {unit["own body"]} ({100 * unit["own body"] / max(1, total):.0f}%), inlined callees {inl}'
                  f' ({100 * inl / max(1, total):.0f}%) in {len(calls)} distinct callees ({sum(calls.values())} sites),'
                  f' call root {unit["<call-root>"]}')
            print(f'  inlined callees by IR nodes ({top}): nodes, sites')
            for u, n in sorted(((k, v) for k, v in unit.items() if k.startswith('inlined')), key=lambda kv: -kv[1])[:top]:
                print(f'    {n:7d} {calls[u]:4d}  {u[8:]}')
            print(f'  Truffle node classes by self IR nodes, all ({top}):')
            for name, n in by_class.most_common(top):
                print(f'    {n:7d} {100 * n / max(1, total):5.1f}%  {name}')
        else:
            by_method = collections.Counter()
            calls = collections.Counter()
            invokes_of = collections.Counter()
            allocs_of = collections.Counter()
            for depth, name, count, self_count, rest, invokes, allocs in rows:
                by_method[name] += self_count
                calls[name] += 1
                invokes_of[name] += invokes
                allocs_of[name] += allocs
            print(f'  invokes left (calls not inlined: boundaries, guest calls): {sum(invokes_of.values())},'
                  f' allocations {sum(allocs_of.values())}')
            print(f'  methods by the invokes they leave ({top}):')
            for name, n in invokes_of.most_common(top):
                print(f'    {n:6d}  {name}')
            print(f'  Java methods by self IR nodes ({top}): nodes, share, expansions')
            for name, n in by_method.most_common(top):
                print(f'    {n:7d} {100 * n / max(1, total):5.1f}% {calls[name]:6d}  {name}')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('log')
    ap.add_argument('what', choices=['compile', 'splits', 'deopts', 'inlining', 'expansion'])
    ap.add_argument('targets', nargs='*')
    ap.add_argument('--top', type=int, default=25)
    a = ap.parse_args()
    if a.what == 'compile':
        compile_report(a.log, a.top)
    elif a.what == 'splits':
        splits_report(a.log, a.top)
    elif a.what == 'deopts':
        deopts_report(a.log, a.top)
    elif a.what == 'inlining':
        inlining_report(a.log, a.targets, a.top)
    else:
        expansion_report(a.log, a.targets, a.top)


if __name__ == '__main__':
    sys.exit(main())
