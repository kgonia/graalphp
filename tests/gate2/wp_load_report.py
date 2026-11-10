"""The WordPress load runs' numbers (tests/gate2/native.sh load), from target/$WPN_NAME/load.

line NAME: one run in a line, from k6's summary NAME.k6.json and the server's sample NAME.sample.json
  (woo_sample.py: its container's CPU seconds and memory over the run).
table DIR OUT: every run of DIR (VARIANT-rROUND-LEVEL) as a Markdown table: per variant and level the median of the
  rounds and their range, written to OUT with its JSON next to it.
"""
import json
import os
import re
import statistics
import sys


def numbers(name):
    k = json.load(open(name + ".k6.json"))
    s = json.load(open(name + ".sample.json"))
    m = k["metrics"]
    seconds = k["state"]["testRunDurationMs"] / 1000

    def count(key):
        return m.get(key, {}).get("values", {}).get("count", 0)

    d = m["http_req_duration"]["values"]
    pages = count("pages")
    return {"pages": pages, "pages_s": pages / seconds, "p50": d["med"], "p95": d["p(95)"], "p99": d["p(99)"],
            "failed": m["http_req_failed"]["values"]["rate"], "check_fails": m["checks"]["values"]["fails"],
            "php_errors": count("php_errors"), "cpu_s": s["cpu_s"], "cores": s["cpu_s"] / s["seconds"],
            "cpu_per_page": s["cpu_s"] / pages if pages else None, "rss_anon": s["max_rss_anon"],
            "current": s["max_current"], "peak": s["peak"]}


def line(name):
    n = numbers(name)
    print(f"{os.path.basename(name)}: {n['pages_s']:.1f} pages/s, p50 {n['p50']:.0f} p95 {n['p95']:.0f} p99"
          f" {n['p99']:.0f} ms, failed {n['failed']:.4f}, checks failed {n['check_fails']}, php errors"
          f" {n['php_errors']}; {n['cores']:.1f} cores, {1000 * (n['cpu_per_page'] or 0):.0f} CPU-ms/page, RssAnon"
          f" max {n['rss_anon']:.0f} MB, current max {n['current']:.0f} MB, peak {n['peak']:.0f} MB")


def table(directory, out):
    runs = {}
    for f in sorted(os.listdir(directory)):
        match = re.fullmatch(r"(.+)-r(\d+)-(\d+)\.k6\.json", f)
        if not match or not os.path.exists(os.path.join(directory, f[:-len(".k6.json")] + ".sample.json")):
            continue
        variant, level = match.group(1), int(match.group(3))
        runs.setdefault((variant, level), []).append(numbers(os.path.join(directory, f[:-len(".k6.json")])))
    rows, data = [], {}
    for (variant, level), ns in sorted(runs.items(), key=lambda kv: (kv[0][1], kv[0][0])):
        def med(key):
            return statistics.median(n[key] for n in ns)

        def span(key, fmt):
            values = [n[key] for n in ns]
            return f"{fmt.format(med(key))} ({fmt.format(min(values))}-{fmt.format(max(values))})"

        data[f"{variant}-{level}"] = ns
        rows.append(f"| {variant} | {level} | {len(ns)} | {span('pages_s', '{:.1f}')} | {med('p50'):.0f} |"
                    f" {span('p99', '{:.0f}')} | {med('cores'):.1f} | {1000 * med('cpu_per_page'):.0f} |"
                    f" {med('rss_anon'):.0f} | {span('peak', '{:.0f}')} | {sum(n['check_fails'] for n in ns)} |"
                    f" {sum(n['php_errors'] for n in ns)} |")
    text = ("| variant | users | rounds | pages/s (range) | p50 ms | p99 ms (range) | cores | CPU-ms/page |"
            " RssAnon MB | memory.peak MB (range) | failed checks | PHP errors |\n"
            "|---|---|---|---|---|---|---|---|---|---|---|---|\n" + "\n".join(rows) + "\n")
    open(out, "w").write(text)
    json.dump(data, open(os.path.splitext(out)[0] + ".json", "w"), indent=1)
    print(text)


if __name__ == "__main__":
    if sys.argv[1] == "line":
        line(sys.argv[2])
    elif sys.argv[1] == "table":
        table(sys.argv[2], sys.argv[3])
