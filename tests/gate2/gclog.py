"""GC pauses of tests/gate2/native.sh measure's native servers, from their logs (target/$WPN/runs/<run>.server.log),
run with -XX:+PrintGC: the serial GC's lines ("GC(3) Pause Incremental GC (Collect on allocation) 82M->42M 60.9ms")
and G1's ("GC(3) Pause Young (Normal) (G1 Evacuation Pause) 117M->77M(156M) 7.280ms"; its concurrent phases are no
pauses and are counted apart).

Per variant, the median over its rounds of each run's: pauses, total pause time (s), the median and the longest
pause (ms), full or old-generation collections, and G1's concurrent time (s).

Usage: python gclog.py RUNS_DIR VARIANT...
"""
import glob
import os
import re
import statistics
import sys

PAUSE = re.compile(r"GC\(\d+\) Pause (.*?) [\d.]+[KMG]->[\d.]+[KMG].*? ([\d.]+)ms\s*$")
CONCURRENT = re.compile(r"GC\(\d+\) Concurrent (?:Mark|Undo) Cycle ([\d.]+)ms\s*$")


def parse(path):
    pauses, full, concurrent = [], 0, 0.0
    for line in open(path, encoding="utf-8", errors="replace"):
        m = PAUSE.search(line)
        if m:
            pauses.append(float(m.group(2)))
            kind = m.group(1)
            full += "Full" in kind or "Complete" in kind
            continue
        m = CONCURRENT.search(line)
        if m:
            concurrent += float(m.group(1)) / 1000
    return {"pauses": len(pauses), "total": sum(pauses) / 1000,
            "median": statistics.median(pauses) if pauses else 0.0, "max": max(pauses, default=0.0),
            "full": full, "concurrent": concurrent}


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    directory, variants = sys.argv[1], sys.argv[2:]
    print("| variant | rounds | pauses | pause time, s | median pause, ms | longest pause, ms | full collections "
          "| concurrent, s |")
    print("|---|---|---|---|---|---|---|---|")
    for v in variants:
        runs = [parse(f) for f in sorted(glob.glob(os.path.join(directory, f"{v}-*.server.log")))]
        if not runs:
            continue

        def med(key, fmt):
            values = [r[key] for r in runs]
            return f"{statistics.median(values):{fmt}} ({min(values):{fmt}}–{max(values):{fmt}})"

        print(f"| {v} | {len(runs)} | {med('pauses', '.0f')} | {med('total', '.2f')} | {med('median', '.1f')} "
              f"| {med('max', '.0f')} | {med('full', '.0f')} | {med('concurrent', '.2f')} |")


if __name__ == "__main__":
    main()
