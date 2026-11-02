"""libgraal's collections in a server's output (-Djdk.graal.internal.PrintGC=true: one line per collection of a
libgraal isolate, `[12.345s] GC(7) Pause Full GC (Collect on allocation) 222.22M->156.15M 716.891ms`). Every isolate
numbers its collections from GC(0), so the lines don't say which isolate collected; the heap sizes bound one
isolate's heap.

Per run: the collections by kind and cause, their pause time, the largest heap before and after a collection, and
per minute of the server's life the collections, their pauses and the largest heap after one.

Usage: python -I tests/gate2/libgraal_gc.py SERVER_LOG...
"""
import collections
import re
import sys

GC = re.compile(r'^\[(\d+\.\d+)s\] GC\((\d+)\) Pause (Full|Incremental) GC \(([^)]*)\) ([\d.]+)M->([\d.]+)M ([\d.]+)ms')


def main():
    for path in sys.argv[1:]:
        kinds = collections.Counter()
        pause = collections.Counter()
        before_max = after_max = 0.0
        starts = 0
        minutes = collections.defaultdict(lambda: [0, 0.0, 0.0])
        with open(path, encoding='utf-8', errors='replace') as f:
            for line in f:
                m = GC.match(line)
                if not m:
                    continue
                t, n, kind, cause, before, after, ms = m.groups()
                key = f'{kind} ({cause})'
                kinds[key] += 1
                pause[key] += float(ms)
                before_max = max(before_max, float(before))
                after_max = max(after_max, float(after))
                starts += n == '0'
                minute = minutes[int(float(t) // 60)]
                minute[0] += 1
                minute[1] += float(ms)
                minute[2] = max(minute[2], float(after))
        total = sum(kinds.values())
        print(f'{path}: {total} collections in {starts} isolate lives (GC(0) lines), {sum(pause.values()) / 1000:.1f} s'
              f' of pauses; largest heap before a collection {before_max:.0f} MB, after one {after_max:.0f} MB')
        for key, n in kinds.most_common():
            print(f'  {n:7d} {pause[key] / 1000:8.1f} s  {key}')
        print('  minute: collections, pause s, largest heap after one MB')
        for minute in sorted(minutes):
            n, ms, mx = minutes[minute]
            print(f'  {minute:4d} {n:6d} {ms / 1000:7.1f} {mx:7.0f}')


if __name__ == '__main__':
    main()
