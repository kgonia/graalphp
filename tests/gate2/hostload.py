"""How busy the whole box was during each load run: the mean share of the VM's CPUs in use (from /proc/stat) and the
mean 1-minute load average, from a sampler's log (one line every 10 s: epoch, /proc/loadavg, the first line of
/proc/stat; `docker run alpine:3 sh -c 'while true; do echo "$(date +%s) $(cat /proc/loadavg) $(head -1 /proc/stat)";
sleep 10; done'`) and each run's window: NAME.sample.json's mtime less its "seconds".

Usage: python -I tests/gate2/hostload.py SAMPLER_LOG NAME.sample.json...
"""
import json
import os
import sys


def main():
    samples = []
    for line in open(sys.argv[1], encoding='utf-8', errors='replace'):
        f = line.split()
        if len(f) < 12 or f[6] != 'cpu':
            continue
        ticks = [int(x) for x in f[7:]]
        idle = ticks[3] + ticks[4]
        samples.append((int(f[0]), float(f[1]), sum(ticks), idle))
    for name in sys.argv[2:]:
        end = os.path.getmtime(name)
        start = end - json.load(open(name))['seconds']
        inside = [s for s in samples if start <= s[0] <= end]
        if len(inside) < 2:
            print(f'{os.path.basename(name)}: no samples')
            continue
        total = inside[-1][2] - inside[0][2]
        busy = total - (inside[-1][3] - inside[0][3])
        load = sum(s[1] for s in inside) / len(inside)
        print(f'{os.path.basename(name).replace(".sample.json", "")}: box CPU busy {100 * busy / total:.0f}%,'
              f' load average {load:.1f} ({len(inside)} samples)')


if __name__ == '__main__':
    main()
