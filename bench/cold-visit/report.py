"""The cold-visit runs of bench/cold-visit/runs.sh, per variant: medians over rounds (the range in parentheses) of
- start to the cold home page (the server's start to the first page's last byte), the cold visit's wait (its six
  pages) and its CPU (the cgroup's, from the server's start), memory.peak after the cold visit;
- the early visits (2-6 here: the median of the visits after the cold one);
- the host's load during the run's first 40 s (lpload.ps1): the P-cores' and the E-cores' mean;
then per round, each variant's cold visit, and the ratio of each variant's cold visit to the first variant's in the
same round.

Usage: python report.py RUNS_DIR VARIANT... (the first is the reference)
"""
import json
import os
import re
import statistics
import sys


def load(runs, variant):
    out = {}
    for name in os.listdir(runs):
        m = re.fullmatch(r"jvm-java\." + re.escape(variant) + r"-(\d+)\.json", name)
        if not m:
            continue
        r = int(m.group(1))
        d = json.load(open(os.path.join(runs, name)))
        lp = os.path.join(runs, f"jvm-java.{variant}-{r}.lp")
        p = e = None
        if os.path.exists(lp):
            for line in open(lp, encoding="utf-8", errors="replace"):
                mm = re.match(r"P-cores (\d+)%, E-cores (\d+)%", line.strip())
                if mm:
                    p, e = int(mm.group(1)), int(mm.group(2))
        warm = d.get("warm", [])
        out[r] = {"first": d["cold"]["start_to_first_page"], "wait": d["cold"]["wait"], "cpu": d["cold"]["cpu"],
                  "peak": d["memory"]["cold"]["peak"], "early": statistics.median(v["wait"] for v in warm) if warm else None,
                  "ok": d["cold"]["ok"], "p": p, "e": e,
                  "home": d["cold"]["pages"][0][2] / 1000}
    return out


def cell(values, fmt):
    values = [v for v in values if v is not None]
    if not values:
        return "-"
    med = statistics.median(values)
    return f"{fmt.format(med)} ({fmt.format(min(values))}-{fmt.format(max(values))})"


def main():
    runs, variants = sys.argv[1], sys.argv[2:]
    data = {v: load(runs, v) for v in variants}
    print("| variant | rounds | start to the cold home page s | cold visit wait s | cold visit CPU-s | memory.peak after "
          "it MB | early visit wait s | host P-cores / E-cores % |")
    print("|---|---|---|---|---|---|---|---|")
    for v in variants:
        rs = data[v].values()
        print(f"| {v} | {len(rs)} | {cell([x['first'] for x in rs], '{:.2f}')} | {cell([x['wait'] for x in rs], '{:.2f}')} "
              f"| {cell([x['cpu'] for x in rs], '{:.1f}')} | {cell([x['peak'] for x in rs], '{:.0f}')} "
              f"| {cell([x['early'] for x in rs], '{:.2f}')} | {cell([x['p'] for x in rs], '{:.0f}')} / "
              f"{cell([x['e'] for x in rs], '{:.0f}')} |")
    rounds = sorted(set().union(*[set(d) for d in data.values()]))
    print()
    print("| round | " + " | ".join(variants) + " |")
    print("|---|" + "---|" * len(variants))
    for r in rounds:
        row = []
        for v in variants:
            x = data[v].get(r)
            row.append("-" if x is None else f"{x['wait']:.2f} s (P {x['p']}%, E {x['e']}%){'' if x['ok'] else ' FAILED'}")
        print(f"| {r} | " + " | ".join(row) + " |")
    ref = variants[0]
    print()
    print(f"cold visit wait / {ref}'s, per round (median, range):")
    for v in variants[1:]:
        ratios = [data[v][r]["wait"] / data[ref][r]["wait"] for r in rounds if r in data[v] and r in data[ref]]
        if ratios:
            print(f"  {v}: {statistics.median(ratios):.3f} ({min(ratios):.3f}-{max(ratios):.3f}), {len(ratios)} rounds")


if __name__ == "__main__":
    main()
