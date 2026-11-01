"""Where a visit's time goes: the phase counters' lines (HttpMain -Dphp.phases=true, RequestPhases) in the server
logs of tests/gate2/native.sh measure, joined with visits.py's runs (target/<prefix>/runs/<variant>-<round>.json
and .server.log). See wiki/visit-phases.md.

A visit is the requests from one GET / (the home page, visits.py's first page) to the next. Per visit:
- on the requests' thread (the critical path, wall time added up over the visit's requests): the includes'
  lookups, reading files, parsing them, reading bodies left unparsed, translating, native calls (PCRE2 matches;
  compiling patterns apart), GC pauses, the contexts' setup and close, and the rest: execution;
- the requests' thread CPU;
- off the thread, CPU from the end of the visit before (the pause included) to the end of this one, or from the
  server's start for the first visit: Truffle's compiler threads, the JVM's own JIT (HotSpot's compiler threads,
  compiling the interpreter's Java), the GC and VM threads, the rest; the cgroup's CPU (visits.py) beside it.
Cells are the median over rounds of: the first visit (cold), the median of visits 2-5 (early), and the median of the
last WINDOW visits (warm), with the range over rounds in parentheses.

Usage: python phases_report.py RUNS_DIR VARIANT... [--window=N]
"""
import glob
import json
import os
import statistics
import sys

PHASES = ["include", "read", "parse", "body", "file_translate", "translate"]


def group(name):
    """A thread name (digits dropped, as RequestPhases prints it) to its group. HotSpot keeps a name's first 15
    characters, a native image its last 15 ("mpilerThread-" is TruffleCompilerThread-N)."""
    if name.startswith("TruffleCompiler") or name == "mpilerThread-":
        return "truffle"
    if name.startswith(("C CompilerThre", "JVMCI")):
        return "jit"
    if name.startswith(("GC Thread", "G Conc", "G Main Marker", "G Refine", "G Service", "VM Thread")):
        return "gc"
    if name.startswith("pool-") or name.endswith("pool--thread-"):
        return "requests"
    return "other"


def lines(path, prefix):
    for line in open(path, encoding="utf-8", errors="replace"):
        if line.startswith(prefix + " {"):
            yield json.loads(line[len(prefix) + 1:])


def groups(record):
    out = {"truffle": 0, "jit": 0, "gc": 0, "requests": 0, "other": 0}
    for name, ms in record["threads_ms"].items():
        out[group(name)] += ms
    out["process"] = record["process_cpu_ms"]
    return out


def visits_of(log):
    """Per visit: the server-side numbers, in seconds."""
    start = next(lines(log, "phases-start"), None)
    requests = list(lines(log, "phases"))
    visits, current = [], []
    for r in requests:
        if r["method"] == "GET" and r["uri"] == "/" and current:
            visits.append(current)
            current = []
        current.append(r)
    if current:
        visits.append(current)
    before = groups(start) if start else None
    out = []
    for v in visits:
        s = {"requests": len(v)}
        wall = sum(r["wall_ns"] for r in v) / 1e9
        s["wall"] = wall
        s["cpu"] = sum(r["cpu_ns"] for r in v) / 1e9
        for p in PHASES:
            s[p] = sum(r[p + "_ns"] for r in v) / 1e9
        s["files"] = sum(r["read_n"] for r in v)
        s["file_mb"] = sum(r["file_bytes"] for r in v) / 1e6
        s["bodies"] = sum(r["body_n"] for r in v)
        s["body_left_mb"] = sum(r["body_chars_left"] for r in v) / 1e6
        s["body_read_mb"] = sum(r["body_chars_read"] for r in v) / 1e6
        s["includes"] = sum(r["include_n"] for r in v)
        s["native"] = sum(r["native_ns"] for r in v) / 1e9
        s["native_compile"] = sum(r["native_compile_ns"] for r in v) / 1e9
        s["gc_pause"] = sum(r["gc_ms"] for r in v) / 1e3
        s["context"] = sum(r["context_ns"] + r["close_ns"] for r in v) / 1e9
        s["execution"] = wall - sum(s[p] for p in PHASES) - s["native"] - s["native_compile"] - s["gc_pause"] \
            - s["context"]
        for kind in ("functions", "methods", "closures"):
            s["declared_" + kind] = sum(r["declared_" + kind] for r in v)
            s["translated_" + kind] = sum(r["translated_" + kind] for r in v)
            s["declared_" + kind + "_total"] = v[-1]["declared_" + kind + "_total"]
            s["translated_" + kind + "_total"] = v[-1]["translated_" + kind + "_total"]
        after = groups(v[-1])
        if before:
            for g in after:
                s["off_" + g] = (after[g] - before[g]) / 1e3
            s["off_unseen"] = s["off_process"] - sum(s["off_" + g] for g in ("truffle", "jit", "gc", "requests",
                                                                               "other"))
        before = after
        out.append(s)
    return out


def med(values):
    return statistics.median(values) if values else float("nan")


def cell(values, digits=3):
    values = [x for x in values if x == x]
    if not values:
        return "–"
    f = f"{{:.{digits}f}}"
    if len(values) == 1:
        return f.format(values[0])
    return f"{f.format(med(values))} ({f.format(min(values))}–{f.format(max(values))})"


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    window = int(next((a.split("=")[1] for a in sys.argv[1:] if a.startswith("--window=")), "20"))
    directory, variants = args[0], args[1:]
    for variant in variants:
        runs = []
        for path in sorted(glob.glob(os.path.join(directory, f"{variant}-*.json"))):
            log = path[:-5] + ".server.log"
            client = json.load(open(path))
            server = visits_of(log) if os.path.exists(log) else []
            waits = [client["cold"]["wait"]] + [w["wait"] for w in client["warm"]]
            cpus = [client["cold"]["cpu"]] + [w["cpu"] for w in client["warm"]]
            runs.append({"waits": waits, "cpus": cpus, "server": server, "peak": client["memory"]["warm"]["peak"]})
        if not runs:
            continue
        n = min(len(r["waits"]) for r in runs)
        windows = {"cold": (0, 1), "early (2–5)": (1, 5), f"warm (last {window})": (n - window, n)}
        print(f"## {variant}: {len(runs)} rounds, {n} visits; memory.peak at the end {cell([r['peak'] for r in runs], 0)} MB")
        print()
        rows = [("wait (client)", lambda r, a, b: med(r["waits"][a:b])),
                ("cgroup CPU", lambda r, a, b: med(r["cpus"][a:b]))]
        has_server = all(len(r["server"]) >= n for r in runs)
        if has_server:
            def sv(key):
                return lambda r, a, b: med([v[key] for v in r["server"][a:b]])
            rows += [("requests' wall", sv("wall")), ("  include lookups", sv("include")),
                     ("  file read", sv("read")), ("  file parse", sv("parse")), ("  body read", sv("body")),
                     ("  file translate", sv("file_translate")), ("  body translate", sv("translate")), ("  native (PCRE2 match)", sv("native")),
                     ("  native (pattern compile)", sv("native_compile")), ("  GC pauses", sv("gc_pause")),
                     ("  context setup and close", sv("context")), ("  execution (the rest)", sv("execution")),
                     ("requests' CPU", sv("cpu"))]
            if "off_truffle" in runs[0]["server"][0]:
                rows += [("off-thread: Truffle compilation", sv("off_truffle")),
                         ("off-thread: JVM JIT (interpreter)", sv("off_jit")), ("off-thread: GC and VM", sv("off_gc")),
                         ("off-thread: other and ended threads", lambda r, a, b: med(
                             [v["off_other"] + v["off_unseen"] for v in r["server"][a:b]])),
                         ("process CPU", sv("off_process"))]
            rows += [("files parsed", sv("files")), ("MB parsed", sv("file_mb")),
                     ("MB of bodies left unparsed", sv("body_left_mb")), ("bodies read", sv("bodies")),
                     ("MB of bodies read", sv("body_read_mb")), ("includes", sv("includes"))]
        print("| s per visit | " + " | ".join(windows) + " |")
        print("|---|" + "---|" * len(windows))
        for name, f in rows:
            digits = 0 if name in ("files parsed", "bodies read", "includes") else 3
            print(f"| {name} | " + " | ".join(cell([f(r, a, b) for r in runs], digits) for a, b in windows.values())
                  + " |")
        print()
        if has_server:
            print("Functions declared (in the files translated) and translated, the process's totals:")
            print()
            print("| at the end of | kind | declared | translated | share |")
            print("|---|---|---|---|---|")
            for label, index in (("visit 1", 0), (f"visit {n}", n - 1)):
                for kind in ("functions", "methods", "closures"):
                    d = med([r["server"][index]["declared_" + kind + "_total"] for r in runs])
                    t = med([r["server"][index]["translated_" + kind + "_total"] for r in runs])
                    print(f"| {label} | {kind} | {d:.0f} | {t:.0f} | {t / d if d else 0:.0%} |")
            print()


if __name__ == "__main__":
    main()
