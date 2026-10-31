"""The compilation diagnosis report: reads the engine logs tests/gate2/compile-diag.sh wrote and prints, per run,
- the failures and bailouts, grouped by kind (graph too big, code too large, deopt cycle, other) and by the first
  frame of our Java code in their stack trace;
- the largest compilations by IR size after partial evaluation, and the slowest;
- the targets compiled, invalidated or deoptimized many times, with the reasons;
- the performance warnings and the transfers to the interpreter, grouped by our source location;
- the engine's own statistics (permanent bailouts, failures, invalidation reasons).
--check compares each run against the thresholds below and exits 1 when one is exceeded.
Usage: python -I tests/gate2/compile_report.py DIR [--check] [--top N] [--max-failures N] ...
DIR holds one directory per run (TARGET-ENGINE/engine.log), or is one run's directory.
"""
import argparse
import collections
import os
import re
import sys

OURS = 'pl.codeloom.php.'
DONE = re.compile(r'^\[engine\] opt done\s+engine=\d+\s+id=(\d+)\s+(.*?)\s*\|Tier (\d)\|Time\s+(\d+)\(\s*(\d+)\+(\d+)\s*\)ms'
                  r'\|AST\s+(\d+)\|Inlined\s+(\d+)Y\s+(\d+)N\|IR\s+(\d+)/\s*(\d+)\|CodeSize\s+(\d+)')
FAILED = re.compile(r'^\[engine\] opt failed\s+engine=\d+\s+id=(\d+)\s+(.*?)\s*\|Tier (\d)\|Time\s+(\d+)\(.*?\|Reason: (.*?)\|UTC')
INVAL = re.compile(r'^\[engine\] opt inval\.\s+engine=\d+\s+id=(\d+)\s+(.*?)\s*\|.*\|Reason (.*)$')
DEOPT = re.compile(r'^\[engine\] opt deopt\s+engine=\d+\s+id=(\d+)\s+(.*?)\s*\|.*\|Reason (.*)$')
PRINTED = re.compile(r'^\[engine\] opt fail\s+(.*?)\s*\|AST')
WARN = re.compile(r'^\[engine\] perf warn\s+(.*?)\s*\|(.*)$')
FRAME = re.compile(r'^\s*(?:at\s+)?(?:\S+//)?([\w$.]+)\(([^)]*)\)')


def ours(frames, skip=()):
    """The first frame of our code, as Class.method(File.java:line), skipping the given method prefixes."""
    for cls_method, where in frames:
        if cls_method.startswith(OURS) and not any(cls_method.startswith(s) for s in skip):
            return cls_method[len(OURS):] + '(' + where + ')'
    return '(no frame of ours)'


def kind_of(reason):
    r = reason.lower()
    if 'deopt taken too many times' in r or 'deopt cycle' in r:
        return 'deopt cycle'
    if 'graph too big' in r or 'maximumgraalgraphsize' in r or 'too many nodes' in r or 'exceeded' in r:
        return 'graph too big'
    if 'should not be materialized' in r or 'must not let virtual object escape' in r:
        return 'frame escapes'
    if 'code is too large' in r:
        return 'code too large'
    if 'cancel' in r:
        return 'cancelled'
    return 'other'


class Run:
    def __init__(self, name, path):
        self.name = name
        self.done = []          # (id, name, tier, ms, pe_ms, graal_ms, ast, ir_pe, ir_after, code)
        self.failed = []        # (id, name, tier, ms, reason, frames)
        self.inval = []         # (id, name, reason)
        self.deopt = []         # (id, name, reason)
        self.warnings = []      # (target, message, frames)
        self.transfers = []     # frames
        self.deopts = []        # native image: (target, reason, frames), from the server's output
        self.stats = collections.OrderedDict()
        self.parse(path)
        server = os.path.join(os.path.dirname(path), 'server.log')
        if os.path.isfile(server):
            self.parse_deopts(server)

    def parse(self, path):
        with open(path, encoding='utf-8', errors='replace') as f:
            lines = f.read().splitlines()
        i = 0
        pending = {}
        while i < len(lines):
            line = lines[i]
            m = DONE.match(line)
            if m:
                g = m.groups()
                self.done.append((int(g[0]), g[1], int(g[2]), int(g[3]), int(g[4]), int(g[5]), int(g[6]),
                                  int(g[9]), int(g[10]), int(g[11])))
                i += 1
                continue
            m = FAILED.match(line)
            if m:
                g = m.groups()
                # CompilationFailureAction=Print's record with the stack trace comes as a record of its own
                self.failed.append([int(g[0]), g[1], int(g[2]), int(g[3]), g[4], pending.pop(g[1], [])])
                i += 1
                continue
            m = PRINTED.match(line)
            if m:
                frames, i = self.frames(lines, i + 1)
                for f in reversed(self.failed):
                    if f[1] == m.group(1) and not f[5]:
                        f[5] = frames
                        break
                else:
                    pending[m.group(1)] = frames
                continue
            m = INVAL.match(line)
            if m:
                self.inval.append((int(m.group(1)), m.group(2), m.group(3).strip()))
                i += 1
                continue
            m = DEOPT.match(line)
            if m:
                self.deopt.append((int(m.group(1)), m.group(2), m.group(3).strip()))
                i += 1
                continue
            m = WARN.match(line)
            if m:
                frames, i = self.frames(lines, i + 1)
                self.warnings.append((m.group(1), m.group(2).strip(), frames))
                continue
            if line.startswith('[engine] transferToInterpreter at'):
                frames, i = self.frames(lines, i + 1)
                self.transfers.append(frames)
                continue
            if line.startswith('[engine] Truffle runtime statistics'):
                i = self.statistics(lines, i + 1)
                continue
            i += 1

    def parse_deopts(self, path):
        """A native image's TraceTransferToInterpreter: every deoptimization, with its reason and stack trace."""
        with open(path, encoding='utf-8', errors='replace') as f:
            lines = f.read().splitlines()
        i = 0
        while i < len(lines):
            if lines[i].startswith('[Deoptimization initiated'):
                target, reason, frames = '', '', []
                i += 1
                while i < len(lines) and not lines[i].startswith('['):
                    line = lines[i].strip()
                    if line.startswith('name:'):
                        target = re.sub(r' \(SubstrateCompilation-\d+\)$', '', line[5:].strip())
                    elif line.startswith('reason:'):
                        reason = line[7:].strip()
                    else:
                        m = FRAME.match(lines[i])
                        if m:
                            frames.append((m.group(1), m.group(2)))
                    i += 1
                self.deopts.append((target, reason, frames))
                continue
            i += 1

    @staticmethod
    def frames(lines, i):
        """The stack frames (and exception lines) after a record, up to the next [engine] record."""
        frames = []
        while i < len(lines) and not lines[i].startswith('[engine]'):
            m = FRAME.match(lines[i])
            if m:
                frames.append((m.group(1), m.group(2)))
            i += 1
        return frames, i

    def statistics(self, lines, i):
        """The engine's statistics block: the compilation counts and their reasons, up to the AST statistics."""
        section = None
        while i < len(lines) and not lines[i].startswith('[engine]'):
            line = lines[i]
            if 'AST node statistics' in line:
                break
            m = re.match(r'^(\s+)(.*?)\s*:\s*(\S.*)$', line)
            if m:
                depth, key, value = len(m.group(1)), m.group(2), m.group(3).strip()
                if depth <= 6:
                    section = key
                    self.stats[key] = (value, collections.OrderedDict())
                elif section:
                    self.stats[section][1][key] = value
            i += 1
        return i

    def stat(self, key):
        v = self.stats.get(key)
        return int(v[0]) if v and v[0].isdigit() else 0


def short(name, width=60):
    return name if len(name) <= width else name[:width - 3] + '...'


def report(run, top):
    print(f'=== {run.name}')
    done_ids = collections.Counter(d[0] for d in run.done)
    pe_ms = sum(d[4] for d in run.done)
    all_ms = sum(d[3] for d in run.done) + sum(f[3] for f in run.failed)
    print(f'compilations done: {len(run.done)} ({len(done_ids)} targets), failed: {len(run.failed)},'
          f' invalidated: {len(run.inval)}, deoptimized: {len(run.deopt)};'
          f' compile time {all_ms / 1000:.1f} s (partial evaluation {pe_ms / 1000:.1f} s of the successful),'
          f' {sum(f[3] for f in run.failed) / 1000:.1f} s in failed compilations')
    if run.stats:
        parts = []
        for key in ('Permanent Bailouts', 'Failed', 'Temporary Bailouts', 'Invalidated'):
            if key in run.stats:
                parts.append(f'{key.lower()} {run.stats[key][0]}')
        print('engine statistics: ' + ', '.join(parts))
    else:
        print('engine statistics: none (the engine did not close?)')

    print(f'\n-- failures and bailouts ({len(run.failed)})')
    groups = collections.defaultdict(list)
    for f in run.failed:
        groups[(kind_of(f[4]), ours(f[5]))].append(f)
    for (kind, where), fs in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        print(f'{len(fs):5}  {kind:15} at {where}')
        for f in fs[:top]:
            print(f'         tier {f[2]} {f[3]:6} ms  {short(f[1])}: {short(f[4], 110)}')
    for key in ('Permanent Bailouts', 'Failed'):
        if key in run.stats and run.stats[key][1]:
            print(f'  engine statistics, {key.lower()}:')
            for reason, count in run.stats[key][1].items():
                print(f'    {count:>5}  {short(reason, 140)}')

    print(f'\n-- largest compilations by IR nodes after partial evaluation (top {top})')
    print('   IR(PE)  IR(end)   code  ms(PE+graal)  tier  AST  target')
    for d in sorted(run.done, key=lambda d: -d[7])[:top]:
        print(f'{d[7]:8} {d[8]:8} {d[9]:7}  {d[3]:5}({d[4]}+{d[5]})  {d[2]:4} {d[6]:5}  {short(d[1])}')
    print(f'\n-- slowest compilations (top {top})')
    for d in sorted(run.done, key=lambda d: -d[3])[:top]:
        print(f'{d[3]:6} ms ({d[4]}+{d[5]})  IR {d[7]:6}  tier {d[2]}  {short(d[1])}')

    print('\n-- targets compiled, invalidated or deoptimized many times')
    names = {}
    for rec in run.done + run.failed:
        names[rec[0]] = rec[1]
    for rec in run.inval + run.deopt:
        names.setdefault(rec[0], rec[1])
    inval_by = collections.defaultdict(collections.Counter)
    for t, _, reason in run.inval:
        inval_by[t][reason] += 1
    deopt_by = collections.Counter(t for t, _, _ in run.deopt)
    rows = []
    for t in names:
        n = done_ids.get(t, 0)
        k = sum(inval_by[t].values())
        if n >= 4 or k >= 3 or deopt_by[t] >= 3:
            rows.append((n, k, deopt_by[t], t))
    for n, k, dp, t in sorted(rows, key=lambda r: (-r[0] - r[1], r[3]))[:top]:
        reasons = ', '.join(f'{c}x {short(r, 50)}' for r, c in inval_by[t].most_common(3))
        print(f'{n:4} compiled {k:4} invalidated {dp:4} deopt  {short(names[t], 50)}  {reasons}')
    if not rows:
        print('  none')
    reasons = collections.Counter(r for _, _, r in run.inval)
    print('  invalidation reasons: ' + (', '.join(f'{c}x {short(r, 60)}' for r, c in reasons.most_common(8)) or 'none'))

    print(f'\n-- performance warnings ({len(run.warnings)}), by our source location')
    wgroups = collections.defaultdict(list)
    for target, message, frames in run.warnings:
        what = re.sub(r'\(\d+\|\w+\)', '', message)
        what = re.sub(r'HotSpotMethod<|>', '', what)
        wgroups[(ours(frames), short(what, 120))].append(target)
    for (where, what), targets in sorted(wgroups.items(), key=lambda kv: -len(kv[1]))[:top * 2]:
        print(f'{len(targets):5}  at {where}\n         {what} (e.g. in {short(targets[0], 50)})')

    print(f'\n-- transfers to the interpreter in compiled code ({len(run.transfers)}), by our source location')
    tgroups = collections.Counter(ours(fr, skip=('pl.codeloom.php.runtime.PhpContext.',)) for fr in run.transfers)
    for where, count in tgroups.most_common(top):
        print(f'{count:5}  at {where}')
    if run.deopts:
        print(f'\n-- deoptimizations (native image, {len(run.deopts)}), by reason and our source location')
        dgroups = collections.defaultdict(list)
        for target, reason, frames in run.deopts:
            dgroups[(re.sub(r'\s+', ' ', reason), ours(frames))].append(target)
        for (reason, where), targets in sorted(dgroups.items(), key=lambda kv: -len(kv[1]))[:top * 2]:
            common = collections.Counter(targets).most_common(2)
            names = ', '.join(f'{short(t, 40)} ({c})' for t, c in common)
            print(f'{len(targets):5}  {short(reason, 50):50} at {where}\n         in {names}')
    print()


def check(run, args):
    """The thresholds a run must stay within; the messages of those it exceeds."""
    problems = []
    kinds = collections.Counter(kind_of(f[4]) for f in run.failed if kind_of(f[4]) != 'cancelled')
    failures = sum(kinds.values())
    if failures > args.max_failures:
        problems.append(f'{failures} failed compilations ({dict(kinds)}), allowed {args.max_failures}')
    if kinds['deopt cycle'] > args.max_deopt_cycles:
        problems.append(f'{kinds["deopt cycle"]} deopt cycles, allowed {args.max_deopt_cycles}')
    permanent = run.stat('Permanent Bailouts') + run.stat('Failed')
    if permanent > args.max_failures:
        problems.append(f'the engine counted {permanent} permanent bailouts and failures, allowed {args.max_failures}')
    if not run.stats:
        problems.append('no engine statistics: the server did not close its engine')
    biggest = max((d[7] for d in run.done), default=0)
    if biggest > args.max_ir:
        problems.append(f'a compilation of {biggest} IR nodes after partial evaluation, allowed {args.max_ir}')
    slowest = max((d[3] for d in run.done), default=0)
    if slowest > args.max_compile_ms:
        problems.append(f'a compilation of {slowest} ms, allowed {args.max_compile_ms}')
    counts = collections.Counter(d[0] for d in run.done)
    if counts:
        target, most = counts.most_common(1)[0]
        if most > args.max_recompilations:
            name = next(d[1] for d in run.done if d[0] == target)
            problems.append(f'{name} compiled {most} times, allowed {args.max_recompilations}')
    if len(run.warnings) > args.max_perf_warnings:
        problems.append(f'{len(run.warnings)} performance warnings, allowed {args.max_perf_warnings}')
    return problems


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('dir')
    p.add_argument('--top', type=int, default=12)
    p.add_argument('--check', action='store_true', help='exit 1 when a run exceeds a threshold')
    # thresholds: the baseline after the fixes (wiki/partial-evaluation.md), with room for noise
    p.add_argument('--max-failures', type=int, default=0, help='failed compilations and permanent bailouts')
    p.add_argument('--max-deopt-cycles', type=int, default=0)
    p.add_argument('--max-ir', type=int, default=60000, help='IR nodes after partial evaluation')
    p.add_argument('--max-compile-ms', type=int, default=10000)
    p.add_argument('--max-recompilations', type=int, default=12, help='compilations of one call target')
    p.add_argument('--max-perf-warnings', type=int, default=1000)
    args = p.parse_args()
    if os.path.isfile(os.path.join(args.dir, 'engine.log')):
        runs = [(os.path.basename(os.path.normpath(args.dir)), args.dir)]
    else:
        runs = [(d, os.path.join(args.dir, d)) for d in sorted(os.listdir(args.dir))
                if os.path.isfile(os.path.join(args.dir, d, 'engine.log'))]
    if not runs:
        sys.exit(f'no engine.log under {args.dir}')
    failed = False
    for name, path in runs:
        run = Run(name, os.path.join(path, 'engine.log'))
        report(run, args.top)
        if args.check:
            problems = check(run, args)
            for problem in problems:
                print(f'CHECK FAILED {name}: {problem}')
            if not problems:
                print(f'check passed {name}')
            failed |= bool(problems)
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
