"""One row per memrun (woo.sh memrun; wiki/pe-round4-diagnosis.md): throughput and latency (woo_report.py's line
numbers), the server's memory (RssAnon, memory.peak, libgraal's share at most: memsplit.py's "untracked"), its CPU by
thread group at the last snapshot (requests, Truffle's compiler threads, the GC, HotSpot's own JIT), Truffle's
compilations (count, wall time, failures by reason), libgraal's collections (PrintGC: the largest heap after one).

Usage: python -I tests/gate2/pe4_memtable.py OUT_DIR TAG...   (OUT_DIR: target/$P, holding mem/ and load/)
"""
import collections
import glob
import json
import os
import re
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import memsplit  # noqa: E402
import woo_report  # noqa: E402

GC = re.compile(r'^\[\d+\.\d+s\] GC\(\d+\) Pause (?:Full|Incremental) GC \([^)]*\) ([\d.]+)M->([\d.]+)M ([\d.]+)ms')
DONE = re.compile(r'^\[engine\] opt done .*?\|Time\s+(\d+)\(')
FAILED = re.compile(r'^\[engine\] opt failed .*?\|Time\s+(\d+)\(.*?\|Reason: ([^|]*)')


def threads(text):
    out = collections.Counter()
    if '## thread cpu' not in text:
        return out
    for line in text[text.index('## thread cpu'):].splitlines()[1:]:
        if line.startswith('##'):
            break
        parts = line.rsplit(' ', 2)
        if len(parts) == 3:
            out[parts[0]] += float(parts[2])
    return out


def row(out, tag):
    mem = os.path.join(out, 'mem', tag)
    load = os.path.join(out, 'load', tag + '-steady')
    sample = woo_report.load(load)[1]
    n = woo_report.numbers(load)
    snaps = sorted(glob.glob(os.path.join(mem, '1*.txt')))
    untracked = 0
    for snap in snaps:
        parts = memsplit.split(open(snap, encoding='utf-8', errors='replace').read())['parts']
        untracked = max(untracked, parts['untracked'])
    cpu = threads(open(snaps[-1], encoding='utf-8', errors='replace').read()) if snaps else {}
    total = sum(cpu.values()) or 1
    req = cpu.get('pool--thread-', 0)
    comp = cpu.get('TruffleCompiler', 0)
    gc = sum(v for k, v in cpu.items() if k.startswith(('GCThread', 'GConc', 'GRefine', 'GMain', 'GService')))
    jit = cpu.get('JVMCI-nativeCo', 0) + cpu.get('CCompilerThre', 0)
    compilations = wall = 0
    failures = collections.Counter()
    engine = os.path.join(mem, 'engine.log')
    if os.path.isfile(engine):
        for line in open(engine, encoding='utf-8', errors='replace'):
            m = DONE.match(line)
            if m:
                compilations += 1
                wall += int(m.group(1))
                continue
            m = FAILED.match(line)
            if m:
                wall += int(m.group(1))
                reason = m.group(2)
                key = ('OutOfMemoryError' if 'OutOfMemory' in reason else 'timeout' if 'exceeded' in reason
                       else 'oopmap' if 'oopmap' in reason else 'code too large' if 'too large' in reason
                       else reason[:60])
                failures[key] += 1
    gc_after = gc_n = 0
    server = os.path.join(mem, 'server.log')
    if os.path.isfile(server):
        for line in open(server, encoding='utf-8', errors='replace'):
            m = GC.match(line)
            if m:
                gc_n += 1
                gc_after = max(gc_after, float(m.group(2)))
    return dict(tag=tag, orders_s=n['orders_s'], pages_s=n['pages_s'], p50=n['p50'], p99=n['p99'],
                cpu_page=n['cpu_per_page'], rss=sample['max_rss_anon'],
                peak=sample['peak'], untracked=untracked, req=req / total, comp=comp / total, gc=gc / total,
                jit=jit / total, comp_s=comp, compilations=compilations, wall=wall / 1000,
                failures=dict(failures), libgraal_gcs=gc_n, libgraal_after=gc_after)


def main():
    out = sys.argv[1]
    rows = [row(out, tag) for tag in sys.argv[2:]]
    print('| run | orders/s | pages/s | p50 ms | p99 ms | CPU-s/page | RssAnon max MB | memory.peak MB | libgraal at most MB |'
          ' CPU: requests / compiler / GC / JIT | compiler CPU-s | compilations, wall s | failed | libgraal heap after a GC, max MB |')
    print('|' + '---|' * 14)
    for r in rows:
        fails = ', '.join(f'{v} {k}' for k, v in r['failures'].items()) or '0'
        print(f"| {r['tag']} | {r['orders_s']:.2f} | {r['pages_s']:.1f} | {r['p50']:.0f} | {r['p99']:.0f} |"
              f" {r['cpu_page']:.3f} | {r['rss']:.0f} | {r['peak']:.0f} | {r['untracked'] / 1048576:.0f} |"
              f" {100 * r['req']:.0f}% / {100 * r['comp']:.0f}% / {100 * r['gc']:.0f}% / {100 * r['jit']:.0f}% |"
              f" {r['comp_s']:.0f} | {r['compilations']}, {r['wall']:.0f} | {fails} | {r['libgraal_after']:.0f} |")
    json.dump(rows, open(os.path.join(out, 'memtable.json'), 'w'), indent=1)


if __name__ == '__main__':
    main()
