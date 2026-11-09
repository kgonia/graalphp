"""The table of tests/gate2/k8-cli.sh's runs (wiki/beat-php.md): one process, one request of N renders of a page.

Per run NAME and page: the boot (the request's start to the end of wp_loaded), the first render, and a render's
time along the loop: the median of iterations 3-9, of each tenth of the loop, and of its last fifth ("steady"), the
fastest tenth of the last fifth's iterations ("best"), and the container's memory.peak. A run's pages are compared
with the first run's (the reference): equal engines print equal pages, iteration by iteration.

Usage: python k8_cli_report.py DIR OUT NAME...      (the first NAME is the reference)
"""
import glob
import json
import math
import os
import re
import statistics
import sys


def load(directory, name):
    runs = []
    for path in sorted(glob.glob(os.path.join(directory, name + ".*.json")),
                       key=lambda p: int(re.search(r"\.(\d+)\.json$", p).group(1))):
        try:
            data = json.load(open(path, encoding="utf-8"))
        except ValueError:
            runs.append(None)
            continue
        err = open(path[:-4] + "err", encoding="utf-8", errors="replace").read()
        peak = re.search(r"^peak (\d+)", err, re.M)
        data["peak_mb"] = int(peak.group(1)) / 2**20 if peak else float("nan")
        runs.append(data)
    return runs


def main():
    directory, out = sys.argv[1:3]
    names = sys.argv[3:]
    data = {name: load(directory, name) for name in names}
    reference = data[names[0]]
    lines = ["| page | run | renders | boot ms | first render ms | 3-9 | the loop's tenths, median ms | steady ms | best ms |"
             " steady vs " + names[0] + " | memory.peak MB |", "|---|---|---|---|---|---|---|---|---|---|---|"]
    ratios = {name: [] for name in names}
    notes = []
    summary = {}
    for index, ref in enumerate(reference):
        for name in names:
            run = data[name][index] if index < len(data[name]) else None
            if run is None or ref is None:
                notes.append(f"{name}, page {index + 1}: no answer")
                continue
            times = [i[0] / 1e6 for i in run["iterations"]]
            n = len(times)
            tenths = [statistics.median(part) for part in
                      (times[max(3, n * k // 10):n * (k + 1) // 10] for k in range(10)) if part]
            last = sorted(times[n - n // 5:])
            steady = statistics.median(last)
            best = statistics.median(last[:max(1, len(last) // 10)])
            ref_times = [i[0] / 1e6 for i in ref["iterations"]]
            ref_steady = statistics.median(ref_times[len(ref_times) - len(ref_times) // 5:])
            ratios[name].append(steady / ref_steady)
            summary.setdefault(name, []).append({"page": index + 1, "steady": steady, "best": best,
                                                  "boot": run["phases"]["wp_loaded"], "first": times[0],
                                                  "peak_mb": run["peak_mb"], "n": n})
            lines.append(f"| {index + 1} | {name} | {n} | {run['phases']['wp_loaded']:.0f} | {times[0]:.0f}"
                         f" | {statistics.median(times[3:10]):.1f} | {' '.join(f'{t:.1f}' for t in tenths)}"
                         f" | {steady:.2f} | {best:.2f} | {steady / ref_steady:.2f} | {run['peak_mb']:.0f} |")
            shape = [(i[2], i[3]) for i in run["iterations"][:6]]
            ref_shape = [(i[2], i[3]) for i in ref["iterations"][:6]]
            if shape != ref_shape:
                notes.append(f"{name}, page {index + 1}: the first iterations' pages differ from {names[0]}'s:"
                             f" {shape[:3]} against {ref_shape[:3]}")
            if run["iterations"][-1][4]:
                notes.append(f"{name}, page {index + 1}: the last iteration sent {run['iterations'][-1][4]} queries")
    lines += ["", "Geometric mean over the pages of steady time over " + names[0] + "'s: "
              + ", ".join(f"{name} {math.exp(statistics.fmean(math.log(r) for r in rs)):.2f}"
                          for name, rs in ratios.items() if rs)]
    if notes:
        lines += ["", "Notes:"] + ["- " + note for note in notes]
    text = "\n".join(lines) + "\n"
    open(out, "w", encoding="utf-8").write(text)
    json.dump(summary, open(os.path.splitext(out)[0] + ".json", "w"), indent=1)
    sys.stdout.reconfigure(encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
