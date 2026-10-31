"""Tables of tests/gate2/visits.py's runs (target/wpn/runs/<variant>-<round>.json): per variant the median over
rounds, with the range over rounds in parentheses.

- start: the server's start to its first response (a static file), and to the cold home page;
- the cold visit: its wait (the pages' times added up) and the server's CPU during it;
- warm: the median wait per visit, and CPU-s per visit (pause included), over the last WINDOW visits;
- memory (MB): RssAnon of the server's processes after start, after the cold visit and at the end; the cgroup's
  memory.peak at the same points;
- the curve: the median wait per visit over windows of visits, and where the reference's total time to serve the
  first k visits (start included) falls below each variant's;
- the warm pages: each page's median time over the last WINDOW visits.

Usage: python visits_report.py DIR REFERENCE VARIANT... [--window N]
"""
import glob
import json
import os
import statistics
import sys

WINDOWS = [(1, 1), (2, 2), (3, 3), (4, 5), (6, 10), (11, 20), (21, 30), (31, 50), (51, 75), (76, 100), (101, 125),
           (126, 150), (151, 200)]


def runs_of(directory, variant):
    files = sorted(glob.glob(os.path.join(directory, f"{variant}-*.json")))
    return [json.load(open(f)) for f in files]


def med(values):
    return statistics.median(values) if values else float("nan")


def cell(values, digits=2):
    if not values:
        return "–"
    fmt = f"{{:.{digits}f}}"
    if len(values) == 1:
        return fmt.format(values[0])
    return f"{fmt.format(med(values))} ({fmt.format(min(values))}–{fmt.format(max(values))})"


def waits(run):
    """Per visit, the cold one first: its wait in seconds."""
    return [run["cold"]["wait"]] + [v["wait"] for v in run["warm"]]


def total_time(run, k):
    """Seconds from the server's start to the end of its k-th visit, pauses left out (the same for every server)."""
    return run["start_to_ready"] + sum(waits(run)[:k])


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    window = int(next((a.split("=")[1] for a in sys.argv[1:] if a.startswith("--window=")), "50"))
    directory, reference, variants = args[0], args[1], args[2:]
    runs = {v: runs_of(directory, v) for v in variants}
    print(f"rounds: " + ", ".join(f"{v} {len(r)}" for v, r in runs.items()))
    failed = {v: sum((not r["cold"]["ok"]) + sum(not w["ok"] for w in r["warm"]) for r in rs) for v, rs in runs.items()}
    print("failed visits: " + ", ".join(f"{v} {n}" for v, n in failed.items()))
    print()
    print(f"| | start → first response, s | start → cold home page, s | cold visit: wait, s | cold visit: CPU-s "
          f"| warm wait per visit, s (last {window}) | warm CPU-s per visit (last {window}) |")
    print("|---|---|---|---|---|---|---|")
    for v, rs in runs.items():
        print(f"| {v} | {cell([r['start_to_ready'] for r in rs])} "
              f"| {cell([r['cold']['start_to_first_page'] for r in rs])} "
              f"| {cell([r['cold']['wait'] for r in rs])} | {cell([r['cold']['cpu'] for r in rs], 1)} "
              f"| {cell([med([w['wait'] for w in r['warm'][-window:]]) for r in rs], 3)} "
              f"| {cell([sum(w['cpu'] for w in r['warm'][-window:]) / len(r['warm'][-window:]) for r in rs])} |")
    print()
    print("| MB | RssAnon after start | after the cold visit | warm | memory.peak after start | after the cold visit "
          "| warm |")
    print("|---|---|---|---|---|---|---|")
    for v, rs in runs.items():
        cells = [cell([r["memory"][p]["rss_anon"] for r in rs], 0) for p in ("start", "cold", "warm")]
        cells += [cell([r["memory"][p]["peak"] for r in rs], 0) for p in ("start", "cold", "warm")]
        print(f"| {v} | " + " | ".join(cells) + " |")
    print()
    visits = min(len(r["warm"]) + 1 for rs in runs.values() for r in rs)
    windows = [(a, b) for a, b in WINDOWS if b <= visits]
    print("Median wait per visit (s), the cold visit is visit 1:")
    print()
    print("| visits | " + " | ".join(variants) + " |")
    print("|---|" + "---|" * len(variants))
    for a, b in windows:
        row = []
        for v in variants:
            row.append(f"{med([med(waits(r)[a - 1:b]) for r in runs[v]]):.3f}")
        print(f"| {a}{'' if a == b else '–' + str(b)} | " + " | ".join(row) + " |")
    print()
    print("Median CPU-s per visit (pause included), warm visits:")
    print()
    print("| visits | " + " | ".join(variants) + " |")
    print("|---|" + "---|" * len(variants))
    for a, b in windows[1:]:
        row = [f"{med([med([w['cpu'] for w in r['warm'][a - 2:b - 1]]) for r in runs[v]]):.2f}" for v in variants]
        print(f"| {a}{'' if a == b else '–' + str(b)} | " + " | ".join(row) + " |")
    print()
    ref = runs[reference]
    for v in variants:
        if v == reference:
            continue
        crossings = []
        for k in range(1, visits + 1):
            if med([total_time(r, k) for r in ref]) < med([total_time(r, k) for r in runs[v]]):
                crossings.append(k)
        first = next((k for k in range(1, visits + 1) if all(j in crossings for j in range(k, visits + 1))), None)
        print(f"{reference} has served k visits sooner than {v} (start included, pauses left out) from k = {first}"
              if first else f"{reference} doesn't catch up with {v} within {visits} visits")
    print()
    names = [p[0] for p in ref[0]["warm"][0]["pages"]]
    print(f"Median page time over the last {window} visits (ms):")
    print()
    print("| page | " + " | ".join(variants) + " |")
    print("|---|" + "---|" * len(variants))
    for i, name in enumerate(names):
        row = [f"{med([med([w['pages'][i][2] for w in r['warm'][-window:]]) for r in runs[v]]):.1f}" for v in variants]
        print(f"| {name} | " + " | ".join(row) + " |")


if __name__ == "__main__":
    main()
