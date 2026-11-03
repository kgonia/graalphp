"""Which split copies paid (wiki/bytecode-dsl.md, "Which splits pay"): one engine log of a diagnosis server
(bench/bc-split-audit/runs.sh diag: TraceCompilation, TraceSplitting, SplittingTraceEvents, TraceInlining) read per
split root, the function whose copies Truffle made.

For each root, most copies first:
- why: the node classes whose polymorphic events were reported in the original (Polymorphic event! Source: ...) and
  whether the root needed its split itself ("Set needs split to true") or for a callee with one caller ("... via
  parent");
- copies: the splits made (engine.TraceSplitting), the call sites' files;
- poly again: copies that reported polymorphism themselves after the split (an event in "NAME <split-ID>"): their
  profile went polymorphic again, by node class;
- compiled: copies with an "opt done" (any tier, tier 2), their compile time and code (CodeSize);
- inlined: copies inlined into some compilation (compiler.TraceInlining "Inlined NAME <split-ID>"), and how many
  compilations inlined one;
- unused: copies neither compiled nor inlined anywhere: they ran in the interpreter only.
A copy that is compiled was called past the compile threshold (calls and loop iterations, 400 at least); one that
wasn't stayed under it during the run.

Usage: python -I split_audit.py LOG [--top N] [--json OUT]
"""
import argparse
import collections
import json
import re
import sys

SPLIT = re.compile(r'^\[engine\] split\s+(\d+)-(\d+)\s+(.*?)\s*\|AST\s+(\d+)\|Tier (\d)\|Calls/Thres\s+(\d+)/.*'
                   r'SourceSection (.*)$')
FAILED = re.compile(r'^\[engine\] split failed (Target too big|Not enough budget)\.?:? (\d+) > (\d+) (.*?)\s*\|AST')
POLY = re.compile(r'^\[engine\] \[poly-event\] Polymorphic event! Source: (\S+?)@([0-9a-f]+)\s+(.*\S)')
NEEDS = re.compile(r'^\[engine\] \[poly-event\] (Set needs split to true(?: via parent)?)\s+(.*\S)')
DONE = re.compile(r'^\[engine\] opt done\s+engine=\d+\s+id=\s*(\d+)\s+(.*?)\s*\|Tier (\d)\|Time\s+(\d+)\(.*?'
                  r'\|CodeSize\s+(\d+)')
INLINE = re.compile(r'^\[engine\] (Inline start|Inline done|Inlined|Cutoff)\s+(.*?)\s*\|Frequency')
SPLIT_NAME = re.compile(r'^(.*) <split-(\d+)>$')


def parse_name(name):
    m = SPLIT_NAME.match(name)
    return (m.group(1), int(m.group(2))) if m else (name, None)


def site_file(site):
    m = re.match(r'(.*?)~(\d+):', site)
    if not m:
        return site
    return re.sub(r'^/srv/target/gate2/wordpress/', '', m.group(1))


def read(path):
    copies = collections.defaultdict(list)   # root -> [(copy id, calls at split, site)]
    copy_root = {}
    refused = collections.defaultdict(collections.Counter)  # root -> reason -> n
    refused_size = {}
    poly = collections.defaultdict(collections.Counter)      # root (original) -> node class -> events
    poly_nodes = collections.defaultdict(set)               # root -> (class, hash)
    poly_copy = collections.defaultdict(lambda: collections.defaultdict(collections.Counter))  # root -> copy -> class
    needs = collections.defaultdict(collections.Counter)
    done = collections.defaultdict(list)                    # target id -> [(tier, ms, code)]
    name_of = {}
    inlined_in = collections.defaultdict(set)               # copy id -> compilation roots (names)
    inlined_orig = collections.Counter()                    # original root name -> times inlined
    cutoff = collections.Counter()                          # copy id -> times cut off
    current = None
    n_comp = 0
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            line = line.rstrip('\n')
            if not line.startswith('[engine]'):
                continue
            m = INLINE.match(line)
            if m:
                kind, name = m.groups()
                if kind == 'Inline start':
                    current = name
                    n_comp += 1
                elif kind == 'Inline done':
                    current = None
                else:
                    root, cid = parse_name(name)
                    if kind == 'Inlined':
                        if cid is not None:
                            inlined_in[cid].add(current)
                        else:
                            inlined_orig[root] += 1
                    elif cid is not None:
                        cutoff[cid] += 1
                continue
            m = POLY.match(line)
            if m:
                cls, h, name = m.groups()
                root, cid = parse_name(name)
                cls = cls.split('.')[-1]
                if cid is None:
                    poly[root][cls] += 1
                    poly_nodes[root].add((cls, h))
                else:
                    poly_copy[root][cid][cls] += 1
                continue
            m = NEEDS.match(line)
            if m:
                root, cid = parse_name(m.group(2))
                needs[root][('via parent' if 'via parent' in m.group(1) else 'own') + ('' if cid is None else ' (copy)')] += 1
                continue
            m = SPLIT.match(line)
            if m:
                cid, root, calls, site = int(m.group(2)), m.group(3), int(m.group(6)), m.group(7)
                copies[root].append((cid, calls, site))
                copy_root[cid] = root
                continue
            m = FAILED.match(line)
            if m:
                reason, size, limit, root = m.groups()
                refused[parse_name(root)[0]][reason] += 1
                if reason == 'Target too big':
                    refused_size[parse_name(root)[0]] = int(size)
                continue
            m = DONE.match(line)
            if m:
                tid, name, tier, ms, code = int(m.group(1)), m.group(2), int(m.group(3)), int(m.group(4)), int(m.group(5))
                done[tid].append((tier, ms, code))
                name_of[tid] = name
    return dict(copies=copies, copy_root=copy_root, refused=refused, refused_size=refused_size, poly=poly,
                poly_nodes=poly_nodes, poly_copy=poly_copy, needs=needs, done=done, name_of=name_of,
                inlined_in=inlined_in, inlined_orig=inlined_orig, cutoff=cutoff, compilations=n_comp)


def rows(d):
    out = []
    orig_id = {}
    for tid, name in d['name_of'].items():
        root, cid = parse_name(name)
        if cid is None:
            orig_id[root] = tid
    for root, cs in d['copies'].items():
        ids = [c[0] for c in cs]
        compiled = [i for i in ids if i in d['done']]
        tier2 = [i for i in ids if any(t == 2 for t, _, _ in d['done'].get(i, ()))]
        inlined = [i for i in ids if d['inlined_in'].get(i)]
        poly_again = [i for i in ids if i in d['poly_copy'].get(root, {})]
        again_cls = collections.Counter()
        for i in poly_again:
            again_cls.update(d['poly_copy'][root][i].keys())
        ms = sum(ms for i in compiled for _, ms, _ in d['done'][i])
        code = sum(c for i in compiled for _, _, c in d['done'][i])
        files = collections.Counter(site_file(c[2]) for c in cs)
        oid = orig_id.get(root)
        out.append(dict(
            root=root, copies=len(ids), why=dict(d['poly'].get(root, {})), nodes=len(d['poly_nodes'].get(root, ())),
            needs=dict(d['needs'].get(root, {})), compiled=len(compiled), tier2=len(tier2), inlined=len(inlined),
            inlined_compilations=sum(len(d['inlined_in'][i]) for i in inlined),
            unused=len([i for i in ids if i not in d['done'] and not d['inlined_in'].get(i)]),
            poly_again=len(poly_again), poly_again_by=dict(again_cls), compile_ms=ms, code=code,
            files=dict(files.most_common(4)), refused=dict(d['refused'].get(root, {})),
            original_compiled=oid is not None and oid in d['done'],
            original_tiers=sorted({t for t, _, _ in d['done'].get(oid, ())}) if oid is not None else [],
            original_inlined=d['inlined_orig'].get(root, 0)))
    out.sort(key=lambda r: -r['copies'])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('log')
    ap.add_argument('--top', type=int, default=20)
    ap.add_argument('--json')
    a = ap.parse_args()
    d = read(a.log)
    rs = rows(d)
    total = sum(r['copies'] for r in rs)
    comp = sum(r['compiled'] for r in rs)
    inl = sum(r['inlined'] for r in rs)
    unused = sum(r['unused'] for r in rs)
    again = sum(r['poly_again'] for r in rs)
    all_done = sum(len(v) for v in d['done'].values())
    all_ms = sum(ms for v in d['done'].values() for _, ms, _ in v)
    split_ms = sum(r['compile_ms'] for r in rs)
    print(f'{a.log}')
    print(f'splits {total} of {len(rs)} roots; copies compiled {comp} ({100 * comp / max(1, total):.0f}%), inlined'
          f' somewhere {inl} ({100 * inl / max(1, total):.0f}%), neither {unused} ({100 * unused / max(1, total):.0f}%);'
          f' polymorphic again {again} ({100 * again / max(1, total):.0f}%)')
    print(f'compilations {all_done}, {all_ms / 1000:.0f} s; of copies {sum(r["compiled"] for r in rs)} targets,'
          f' {split_ms / 1000:.0f} s ({100 * split_ms / max(1, all_ms):.0f}%); inlining traces {d["compilations"]}')
    refused = collections.Counter()
    for root, c in d['refused'].items():
        refused.update(c)
    print(f'refused splits: {dict(refused)}')
    print()
    print('| root | why (own nodes reporting in the original) | needs: own/via parent | copies | compiled (T2) |'
          ' inlined | neither | polymorphic again | call sites |')
    print('|---|---|---|---|---|---|---|---|---|')
    for r in rs[:a.top]:
        why = ', '.join(f'{k.replace("Gen", "").split("$")[-1]} {v}' for k, v in
                        sorted(r['why'].items(), key=lambda kv: -kv[1])[:3]) or '-'
        needs = f'{r["needs"].get("own", 0)}/{r["needs"].get("via parent", 0)}'
        again = f'{r["poly_again"]}' + (f' ({", ".join(k.replace("Gen", "").split("$")[-1] for k in r["poly_again_by"])})'
                                       if r['poly_again'] else '')
        files = ', '.join(f'{k} {v}' for k, v in list(r['files'].items())[:2])
        print(f'| {r["root"]} | {why} | {needs} | {r["copies"]} | {r["compiled"]} ({r["tier2"]}) |'
              f' {r["inlined"]} | {r["unused"]} | {again} | {files} |')
    print()
    print("| reporting node class (the root's own, or for a callee) | roots | copies | compiled |"
          ' inlined | neither | polymorphic again |')
    print('|---|---|---|---|---|---|---|')
    by = collections.defaultdict(lambda: [0, 0, 0, 0, 0, 0])
    for r in rs:
        cls = max(r['why'].items(), key=lambda kv: kv[1])[0].replace('Gen', '').split('$')[-1] if r['why']             else 'for a callee'
        b = by[cls]
        for i, k in enumerate(('roots', 'copies', 'compiled', 'inlined', 'unused', 'poly_again')):
            b[i] += 1 if k == 'roots' else r[k]
    for cls, b in sorted(by.items(), key=lambda kv: -kv[1][1]):
        print(f'| {cls} | {b[0]} | {b[1]} | {b[2]} ({100 * b[2] / max(1, b[1]):.0f}%) | {b[3]} | {b[4]} | {b[5]} |')
    if a.json:
        json.dump(dict(rows=rs, total=total, compiled=comp, inlined=inl, unused=unused, poly_again=again,
                       compilations=all_done, compile_ms=all_ms, split_compile_ms=split_ms, refused=dict(refused)),
                  open(a.json, 'w'), indent=1)


if __name__ == '__main__':
    sys.exit(main())
