"""The load runs' numbers (tests/gate2/woo.sh load), from target/wc/load.

line NAME: one run in a line (k6's summary NAME.k6.json, the sample NAME.sample.json, the check NAME.check.json).
warm NAME: the warm-up's curve from its every request (NAME.csv.gz): per 10 s the pages, orders and median
  duration, and the warm-up time: the end of the first 10 s from which every later 10 s had at least 90% of the
  pages of the last minute's median.
phases LOG FIRST LAST: a *-phases server's CPU by thread group and its requests' phases, over its log's request
  lines FIRST..LAST.
table DIR OUT: every run of DIR (TAG-MIX-LEVEL, TAG = ENGINE-rROUND) as a Markdown table, the median of the rounds
  per engine, mix and level, its spread (min-max) for orders/s and p99; written to OUT (and its JSON next to it).
"""
import csv
import gzip
import json
import os
import re
import statistics
import sys


def load(name):
    return tuple(json.load(open(name + suffix)) for suffix in (".k6.json", ".sample.json", ".check.json"))


def numbers(name):
    k, s, c = load(name)
    m = k["metrics"]
    seconds = k["state"]["testRunDurationMs"] / 1000

    def count(key):
        return m.get(key, {}).get("values", {}).get("count", 0)

    d = m["http_req_duration"]["values"]
    orders = count("orders")
    return {"orders": orders, "orders_s": orders / seconds, "pages_s": count("pages") / seconds,
            "p50": d["med"], "p95": d["p(95)"], "p99": d["p(99)"],
            "failed": m["http_req_failed"]["values"]["rate"], "check_fails": m["checks"]["values"]["fails"],
            "orders_failed": count("orders_failed"), "php_errors": count("php_errors"),
            "cpu_s": s["cpu_s"], "cpu_per_order": s["cpu_s"] / orders if orders else None,
            "cpu_per_page": s["cpu_s"] / count("pages") if count("pages") else None,
            "rss_anon": s["max_rss_anon"], "current": s["max_current"], "peak": s["peak"],
            "db_orders": c["orders"], "db_problems": c["problem_count"], "db_problem": c["problems"][:3]}


def line(name):
    n = numbers(name)
    print(f"{os.path.basename(name)}: {n['orders']} orders ({n['orders_s']:.2f}/s), {n['pages_s']:.1f} pages/s,"
          f" p50 {n['p50']:.0f} p95 {n['p95']:.0f} p99 {n['p99']:.0f} ms, failed {n['failed']:.4f}, checks failed"
          f" {n['check_fails']}, php errors {n['php_errors']}; CPU {n['cpu_s']:.1f} s, RssAnon max"
          f" {n['rss_anon']:.0f} MB, current max {n['current']:.0f} MB, peak {n['peak']:.0f} MB;"
          f" db: {n['db_orders']} orders, {n['db_problems']} problems {n['db_problem']}")


def warm(name):
    bins = {}
    start = None
    with gzip.open(name + ".csv.gz", "rt", newline="") as f:
        for row in csv.DictReader(f):
            if row["metric_name"] != "http_req_duration":
                continue
            t = int(row["timestamp"])
            start = t if start is None else min(start, t)
            bins.setdefault(t, []).append((row.get("name", ""), float(row["metric_value"])))
    if start is None:
        return
    tens = {}
    for t, values in bins.items():
        tens.setdefault((t - start) // 10, []).extend(values)
    curve = []
    for i in range(max(tens) + 1):
        values = tens.get(i, [])
        curve.append({"t": (i + 1) * 10, "pages": len(values),
                      "orders": sum(1 for n, _ in values if n == "place-order"),
                      "median_ms": statistics.median([v for _, v in values]) if values else None})
    last = [b["pages"] for b in curve[-7:-1]] or [curve[-1]["pages"]]
    target = 0.9 * statistics.median(last)
    warm_at = next((b["t"] for i, b in enumerate(curve[:-1])
                    if all(c["pages"] >= target for c in curve[i:-1])), None)
    json.dump({"curve": curve, "warm_s": warm_at, "steady_pages_per_10s": statistics.median(last)},
              open(name + ".warm.json", "w"))
    print(f"{os.path.basename(name)}: warm after {warm_at} s (pages per 10 s: "
          f"{' '.join(str(b['pages']) for b in curve)})")


def phases(log, first, last):
    """Where a run's CPU went on a *-phases server: its requests' lines FIRST..LAST of the server log's
    "phases {...}" lines (RequestPhases): the process's CPU by thread group between the two (phases_report.py's
    groups), and on the requests' threads the phases' wall times added up, GC pauses apart."""
    sys.dont_write_bytecode = True
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import phases_report
    records = list(phases_report.lines(log, "phases"))[int(first):int(last)]
    if len(records) < 2:
        return
    a, b = phases_report.groups(records[0]), phases_report.groups(records[-1])
    cpu = {k: (b[k] - a[k]) / 1000 for k in a}
    window = records[1:]
    wall = sum(r["wall_ns"] for r in window) / 1e9
    split = {p: sum(r[p + "_ns"] for r in window) / 1e9 for p in phases_report.PHASES}
    for key in ("native_ns", "native_compile_ns", "context_ns", "close_ns"):
        split[key[:-3]] = sum(r[key] for r in window) / 1e9
    split["gc_pause"] = sum(r["gc_ms"] for r in window) / 1e3
    result = {"requests": len(window), "cpu_s": cpu, "request_wall_s": wall,
              "request_cpu_s": sum(r["cpu_ns"] for r in window) / 1e9, "request_split_s": split}
    print(json.dumps(result))


def table(directory, out):
    runs = {}
    for f in sorted(os.listdir(directory)):
        match = re.match(r"(.+)-r([0-9]+)-([0-9.]+)-([0-9]+)\.check\.json$", f)
        if not match:
            continue
        engine, _, mix, level = match.groups()
        name = os.path.join(directory, f[:-len(".check.json")])
        if not os.path.exists(name + ".k6.json"):
            continue
        runs.setdefault((engine, mix, int(level)), []).append(numbers(name))
    rows = []
    lines = ["| engine | buy | conc. | rounds | orders/s | pages/s | p50 ms | p95 ms | p99 ms | errors | CPU-s/order"
             " | CPU-s/page | RssAnon MB | memory.current MB | memory.peak MB | db problems |",
             "|" + "---|" * 16]
    for (engine, mix, level), ns in sorted(runs.items(), key=lambda kv: (kv[0][1], kv[0][0], kv[0][2])):
        def med(key):
            values = [n[key] for n in ns if n[key] is not None]
            return statistics.median(values) if values else None

        def spread(key, fmt):
            values = [n[key] for n in ns if n[key] is not None]
            return f"{fmt.format(min(values))}-{fmt.format(max(values))}" if len(values) > 1 else ""
        row = {"engine": engine, "buy": mix, "level": level, "rounds": len(ns)}
        for key in ns[0]:
            if key != "db_problem":
                row[key] = med(key)
        rows.append(row)
        errors = sum(n["failed"] for n in ns) / len(ns)
        lines.append(f"| {engine} | {mix} | {level} | {len(ns)} | {med('orders_s'):.2f} ({spread('orders_s', '{:.2f}')})"
                     f" | {med('pages_s'):.1f} | {med('p50'):.0f} | {med('p95'):.0f} | {med('p99'):.0f}"
                     f" ({spread('p99', '{:.0f}')}) | {errors:.2%} / {sum(n['php_errors'] for n in ns)} php"
                     f" | {med('cpu_per_order') or 0:.2f} | {med('cpu_per_page'):.3f} | {med('rss_anon'):.0f}"
                     f" | {med('current'):.0f} | {med('peak'):.0f} | {sum(n['db_problems'] for n in ns)} |")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    json.dump(rows, open(os.path.splitext(out)[0] + ".json", "w"), indent=1)
    print("\n".join(lines))


if __name__ == "__main__":
    {"line": lambda: line(sys.argv[2]), "warm": lambda: warm(sys.argv[2]),
     "table": lambda: table(sys.argv[2], sys.argv[3]),
     "phases": lambda: phases(sys.argv[2], sys.argv[3], sys.argv[4])}[sys.argv[1]]()
