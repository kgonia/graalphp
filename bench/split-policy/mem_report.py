"""Where a server's memory.peak comes from, per variant (the median over rounds, the range): the cgroup's
memory.peak against the most RssAnon, anon, file and memory.current memwatch.py saw (every 0.1 s, RssAnon every
second), how long anon stayed within 5 % of its maximum, and libgraal's largest isolate heap before a collection
(-Djdk.graal.internal.PrintGC=true in the server's output). A gap memory.peak - RssAnon that anon also shows only for
seconds is a burst; one that file shows is the page cache. Against the burst, the plateau: anon's 90th percentile
over the life and its median over the last minute (memwatch's per-second samples).

Usage: python -I mem_report.py woo DIR VARIANT... | g2 DIR VARIANT... [--runs]
  --runs: a row per run instead, with the time of the anon maximum and of the largest isolate heap (its GC line's
  time is the VM's uptime), and anon two seconds after the maximum
  woo: DIR/load/VARIANT-rN-1-16.id and VARIANT-rN.server.log; g2: DIR/runs/jvm-java.VARIANT-N.id and .server.log
"""
import glob
import json
import os
import re
import statistics
import sys

GC = re.compile(r'^\[(\d+\.\d+)s\] GC\(\d+\) Pause \w+ GC \([^)]*\) ([\d.]+)M->([\d.]+)M')


def libgraal(log):
    top = 0.0
    at = None
    n = 0
    try:
        for line in open(log, encoding='utf-8', errors='replace'):
            m = GC.match(line)
            if m:
                n += 1
                if float(m.group(2)) > top:
                    top, at = float(m.group(2)), float(m.group(1))
    except OSError:
        return None, 0, None
    return top, n, at


def med(values, fmt='{:.0f}'):
    values = [v for v in values if v is not None]
    if not values:
        return '-'
    m = statistics.median(values)
    return fmt.format(m) if len(values) == 1 else f'{fmt.format(m)} ({fmt.format(min(values))}-{fmt.format(max(values))})'


def main():
    args = [a for a in sys.argv[1:] if a != '--runs']
    per_run = len(args) < len(sys.argv) - 1
    kind, directory = args[:2]
    if per_run:
        print('| run | memory.peak | max anon | at s | anon 2 s later | anon p90 | anon, last minute |'
              ' libgraal largest heap MB | at s |')
        print('|---|---|---|---|---|---|---|---|---|')
    else:
        print('| variant | runs | memory.peak | max memory.current | max anon | max RssAnon | max file | peak - RssAnon |'
          ' anon near its max, s | anon p90 | anon, last minute | libgraal largest heap MB (collections) |')
        print('|---|---|---|---|---|---|---|---|---|---|---|---|')
    for v in args[2:]:
        rows = []
        if kind == 'woo':
            pairs = [(f, re.sub(r'-1-16\.id$', '.server.log', f))
                     for f in sorted(glob.glob(os.path.join(directory, 'load', f'{v}-r[0-9]*-1-16.id')))]
            watch = os.path.join(directory, 'watch')
        else:
            pairs = [(f, f[:-3] + '.server.log')
                     for f in sorted(glob.glob(os.path.join(directory, 'runs', f'jvm-java.{v}-[0-9]*.id')))]
            watch = os.path.join(directory, 'watch')
        for idf, log in pairs:
            container = open(idf).read().split()[0]
            try:
                m = json.load(open(os.path.join(watch, container + '.mem.json')))
            except OSError:
                continue
            heap, gcs, heap_at = libgraal(log)
            anon = [x['anon'] for x in m['samples']]
            rows.append(dict(m, heap=heap, gcs=gcs, gap=m['peak'] - m['max_rss_anon'],
                             p90=sorted(anon)[int(0.9 * len(anon))], last=statistics.median(anon[-60:])))
            if per_run:
                at = m['anon_max_at']
                later = next((x['anon'] for x in m['samples'] if x['t'] >= at + 2), None)
                name = re.sub(r'(-1-16)?\.id$', '', os.path.basename(idf))
                print(f'| {name} | {m["peak"]:.0f} | {m["max_anon"]:.0f} | {at:.1f} |'
                      f' {"-" if later is None else f"{later:.0f}"} | {rows[-1]["p90"]:.0f} | {rows[-1]["last"]:.0f} |'
                      f' {"-" if heap is None else f"{heap:.0f}"} | {"-" if heap_at is None else f"{heap_at:.1f}"} |')
        if per_run:
            continue
        print(f'| {v} | {len(rows)} | {med([r["peak"] for r in rows])} | {med([r["max_current"] for r in rows])} |'
              f' {med([r["max_anon"] for r in rows])} | {med([r["max_rss_anon"] for r in rows])} |'
              f' {med([r["max_file"] for r in rows])} | {med([r["gap"] for r in rows])} |'
              f' {med([r["anon_near_max_s"] for r in rows], "{:.1f}")} | {med([r["p90"] for r in rows])} |'
              f' {med([r["last"] for r in rows])} |'
              f' {med([r["heap"] for r in rows])} ({med([r["gcs"] for r in rows])}) |')


if __name__ == '__main__':
    main()
