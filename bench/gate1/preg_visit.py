"""The preg_* built-ins' inclusive time per WordPress visit (PregClock, -Dphp.pregclock=true), from the server logs
of tests/gate2/native.sh measure on the visit-phases branch with bench/gate1/results/preg-clock.patch applied: the
`phases {...}` lines (RequestPhases) carry preg_ns (inside preg_* calls, the replacement callbacks' PHP code
excluded), preg_callback_ns (that PHP code), preg_overhead_ns (the clock's own cost, measured in place: one
back-to-back gap per timed window) and the calls per function.

A visit is the requests from one GET / to the next. Per visit, beside the client's wait (visits.py): preg time raw
and net of the clock's cost, the callbacks' PHP time, the calls. Cells are the median over rounds of the median of
the last WINDOW visits (warm), with the range over rounds in parentheses; the cold visit is the first.

Usage: python bench/gate1/preg_visit.py RUNS_DIR VARIANT... [--window=N]
"""
import glob
import json
import os
import statistics
import sys


def requests(path):
    for line in open(path, encoding="utf-8", errors="replace"):
        if line.startswith("phases {"):
            yield json.loads(line[len("phases "):])


def visits_of(log):
    visits, current = [], []
    for r in requests(log):
        if r["method"] == "GET" and r["uri"] == "/" and current:
            visits.append(current)
            current = []
        current.append(r)
    if current:
        visits.append(current)
    out = []
    for v in visits:
        s = {"wall": sum(r["wall_ns"] for r in v) / 1e9, "cpu": sum(r["cpu_ns"] for r in v) / 1e9}
        for key in ("preg_ns", "preg_callback_ns", "preg_overhead_ns", "preg_n", "preg_callback_n", "native_ns"):
            s[key] = sum(r.get(key, 0) for r in v)
        s["preg"] = s["preg_ns"] / 1e9
        s["preg_net"] = (s["preg_ns"] - s["preg_overhead_ns"]) / 1e9
        s["callback"] = (s["preg_callback_ns"]) / 1e9
        functions = {}
        for r in v:
            for key, value in r.items():
                if key.startswith("preg_") and key.endswith("_ns") and key not in (
                        "preg_ns", "preg_callback_ns", "preg_overhead_ns"):
                    name = key[:-3]
                    f = functions.setdefault(name, [0, 0])
                    f[0] += value
                    f[1] += r.get(name + "_n", 0)
        s["functions"] = functions
        out.append(s)
    return out


def med(values):
    values = [x for x in values if x == x]
    return statistics.median(values) if values else float("nan")


def cell(values, fmt="{:.4f}"):
    values = [x for x in values if x == x]
    if not values:
        return "-"
    if len(values) == 1:
        return fmt.format(values[0])
    return f"{fmt.format(med(values))} ({fmt.format(min(values))}-{fmt.format(max(values))})"


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    window = int(next((a.split("=")[1] for a in sys.argv[1:] if a.startswith("--window=")), "20"))
    directory, variants = args[0], args[1:]
    for variant in variants:
        runs = []
        for path in sorted(glob.glob(os.path.join(directory, f"{variant}-[0-9]*.json"))):
            client = json.load(open(path))
            log = path[:-5] + ".server.log"
            waits = [client["cold"]["wait"]] + [w["wait"] for w in client["warm"]]
            runs.append({"waits": waits, "server": visits_of(log) if os.path.exists(log) else [],
                         "peak": client["memory"]["warm"]["peak"]})
        if not runs:
            continue
        n = min(len(r["waits"]) for r in runs)
        print(f"## {variant}: {len(runs)} rounds, {n} visits; memory.peak at the end "
              f"{cell([r['peak'] for r in runs], '{:.0f}')} MB\n")
        warm = (n - window, n)

        def wv(f, a, b):
            return [med([f(v) for v in r["server"][a:b]]) for r in runs if len(r["server"]) >= n]

        rows = [("wait (client)", [med(r["waits"][0:1]) for r in runs], [med(r["waits"][warm[0]:warm[1]]) for r in runs]),
                ("requests' wall", wv(lambda v: v["wall"], 0, 1), wv(lambda v: v["wall"], *warm))]
        if all(len(r["server"]) >= n for r in runs) and runs[0]["server"][0]["preg_n"]:
            rows += [("preg_* inclusive, raw", wv(lambda v: v["preg"], 0, 1), wv(lambda v: v["preg"], *warm)),
                     ("preg_* inclusive, net of the clock", wv(lambda v: v["preg_net"], 0, 1),
                      wv(lambda v: v["preg_net"], *warm)),
                     ("  the clock's measured cost", wv(lambda v: v["preg_overhead_ns"] / 1e9, 0, 1),
                      wv(lambda v: v["preg_overhead_ns"] / 1e9, *warm)),
                     ("callbacks' PHP code (not in the line above)", wv(lambda v: v["callback"], 0, 1),
                      wv(lambda v: v["callback"], *warm)),
                     ("preg_* calls", wv(lambda v: v["preg_n"], 0, 1), wv(lambda v: v["preg_n"], *warm)),
                     ("callbacks", wv(lambda v: v["preg_callback_n"], 0, 1), wv(lambda v: v["preg_callback_n"], *warm)),
                     ("preg net / wait, %", [100 * med([v["preg_net"] for v in r["server"][0:1]]) / r["waits"][0]
                                             for r in runs],
                      [100 * med([v["preg_net"] for v in r["server"][warm[0]:warm[1]]])
                       / med(r["waits"][warm[0]:warm[1]]) for r in runs])]
        print(f"| s per visit | cold | warm (last {window}) |")
        print("|---|---|---|")
        for name, cold, hot in rows:
            fmt = "{:.0f}" if name in ("preg_* calls", "callbacks") else (
                "{:.2f}" if name.endswith("%") else "{:.4f}")
            print(f"| {name} | {cell(cold, fmt)} | {cell(hot, fmt)} |")
        print()
        if all(len(r["server"]) >= n for r in runs) and runs[0]["server"][0]["preg_n"]:
            names = sorted({k for r in runs for v in r["server"][warm[0]:warm[1]] for k in v["functions"]})
            print(f"Per function, warm (median of the last {window} visits, median over rounds):\n")
            print("| function | ms per visit | calls per visit | ns per call |")
            print("|---|---|---|---|")
            for name in names:
                ms = med([med([v["functions"].get(name, [0, 0])[0] / 1e6 for v in r["server"][warm[0]:warm[1]]])
                          for r in runs])
                calls = med([med([v["functions"].get(name, [0, 0])[1] for v in r["server"][warm[0]:warm[1]]])
                             for r in runs])
                print(f"| {name} | {ms:.2f} | {calls:.0f} | {1e6 * ms / calls if calls else 0:.0f} |")
            print()


if __name__ == "__main__":
    main()
