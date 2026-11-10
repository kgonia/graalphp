"""The K8 runs' numbers (tests/gate2/native.sh k8, wiki/beat-php.md), from DIR's VARIANT-rROUND.k8.json files
(k8_drive.py's): per variant and page the medians over the rounds' requests, each block's first half left out.

- plain: a visitor's request as the client sees it (ms), and the server's CPU per request (CPU-ms, every thread of
  its container: the compiler's and the collector's too);
- boot: the request's start to the end of wp_loaded (the plugin's stamps: WordPress loaded, the plugins and the
  theme set up, init done);
- first: the first render inside the request (wp() and template-loader.php), the request's caches cold;
- hot: a later render (iterations 3 and up), the same PHP code with the request's caches warm; "wp()" is its part
  spent in wp() (the request parsed, the main query);
- hot, early and late: the same over the k8 block's first and last quarter of requests, which shows whether the
  engine was still warming up.
Every ratio is the variant's time over the first variant's (argument order), so under 1 is faster.

Usage: python k8_report.py DIR OUT VARIANT...   (the first variant is the reference)
"""
import glob
import json
import math
import os
import statistics
import sys


def med(values):
    return statistics.median(values) if values else float("nan")


def load(directory, variant):
    """page -> the numbers of a variant, its rounds pooled."""
    pages, peaks = {}, []
    for name in sorted(glob.glob(os.path.join(directory, variant + "-r*.k8.json"))):
        run = json.load(open(name))
        peaks.append(run["peak_mb"])
        for block in run["blocks"]:
            p = pages.setdefault(block["page"], {"plain": [], "plain_cpu": [], "boot": [], "first": [], "hot": [],
                                                 "hot_wp": [], "early": [], "late": [], "shape": None, "errors": 0,
                                                 "k8_cpu": [], "k8_wall": []})
            rows = block["requests"]
            p["errors"] += sum(1 for r in rows if r["status"] != 200 or "error" in r)
            if block["kind"] == "plain":
                p["plain"] += [r["wall_ms"] for r in rows[len(rows) // 2:]]
                p["plain_cpu"].append(1000 * block["cpu_s"] / len(rows))
                p["plain_bytes"] = rows[-1]["bytes"]
                continue
            good = [r for r in rows if "iterations" in r]
            quarter = max(1, len(good) // 4)

            def hot(requests):
                return [i[0] / 1e6 for r in requests for i in r["iterations"][3:]]

            p["early"] += hot(good[:quarter])
            p["late"] += hot(good[-quarter:])
            steady = good[len(good) // 2:]
            p["boot"] += [r["phases"]["wp_loaded"] for r in steady]
            p["first"] += [r["iterations"][0][0] / 1e6 for r in steady]
            p["hot"] += hot(steady)
            p["hot_wp"] += [i[1] / 1e6 for r in steady for i in r["iterations"][3:]]
            p["k8_cpu"].append(block["cpu_s"] / block["seconds"])
            p["k8_wall"] += [r["wall_ms"] for r in steady]
            if good:
                # the pages' lengths, iteration by iteration: equal engines print equal pages
                p["shape"] = [i[2] for i in good[-1]["iterations"]]
                p["sums"] = [i[3] for i in good[-1]["iterations"]]
    return pages, peaks


def main():
    directory, out = sys.argv[1:3]
    variants = sys.argv[3:]
    data = {v: load(directory, v) for v in variants}
    reference = data[variants[0]][0]
    lines = ["| page | engine | plain ms | CPU-ms/plain | boot ms | first render ms | hot render ms (wp()) |"
             " hot early → late | hot vs " + variants[0] + " | plain vs " + variants[0] + " |",
             "|---|---|---|---|---|---|---|---|---|---|"]
    ratios = {v: {"hot": [], "plain": [], "boot": [], "first": []} for v in variants}
    notes = []
    for page in reference:
        for v in variants:
            p = data[v][0].get(page)
            if p is None:
                continue
            ref = reference[page]
            hot_ratio = med(p["hot"]) / med(ref["hot"])
            plain_ratio = med(p["plain"]) / med(ref["plain"])
            for key in ratios[v]:
                ratios[v][key].append(med(p[key]) / med(ref[key]))
            lines.append(f"| `{page}` | {v} | {med(p['plain']):.1f} | {med(p['plain_cpu']):.1f} | {med(p['boot']):.1f}"
                         f" | {med(p['first']):.1f} | {med(p['hot']):.2f} ({med(p['hot_wp']):.2f})"
                         f" | {med(p['early']):.2f} → {med(p['late']):.2f} | {hot_ratio:.2f} | {plain_ratio:.2f} |")
            if p["errors"]:
                notes.append(f"{v} `{page}`: {p['errors']} failed requests")
            if p["shape"] != ref["shape"]:
                notes.append(f"{v} `{page}`: the iterations' lengths differ from {variants[0]}'s:"
                             f" {p['shape'][:4]} against {ref['shape'][:4]}")
            elif p.get("sums") != ref.get("sums"):
                notes.append(f"{v} `{page}`: the same lengths as {variants[0]}'s but other checksums")
    lines.append("")
    lines.append("Geometric means over the pages, the variant's time over " + variants[0] + "'s:")
    lines.append("")
    lines.append("| engine | plain | boot | first render | hot render | memory.peak MB |")
    lines.append("|---|---|---|---|---|---|")
    summary = {}
    for v in variants:
        geo = {k: math.exp(statistics.fmean(math.log(x) for x in xs)) if xs else float("nan")
               for k, xs in ratios[v].items()}
        peaks = data[v][1]
        summary[v] = {**geo, "peak_mb": med(peaks)}
        lines.append(f"| {v} | {geo['plain']:.2f} | {geo['boot']:.2f} | {geo['first']:.2f} | {geo['hot']:.2f}"
                     f" | {med(peaks):.0f} ({min(peaks):.0f}-{max(peaks):.0f}) |")
    if notes:
        lines += ["", "Notes:"] + ["- " + n for n in notes]
    text = "\n".join(lines) + "\n"
    open(out, "w", encoding="utf-8").write(text)
    json.dump({v: {"pages": {page: {k: (med(x) if isinstance(x, list) and k not in ("shape", "sums") else x)
                                    for k, x in p.items()} for page, p in data[v][0].items()},
                   "summary": summary[v]} for v in variants}, open(os.path.splitext(out)[0] + ".json", "w"), indent=1)
    sys.stdout.reconfigure(encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
