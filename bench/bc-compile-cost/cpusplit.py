"""Splits a diagnosis server's CPU by thread class over windows of visits (bench/bc-compile-cost/runs.sh diag).

The snapshots (every second: a timestamp, the cgroup's usage_usec, each thread's utime + stime) are matched to the
visits' end times (tests/gate2/visits.py's "t"). A window runs from the last snapshot at or before its start to the
first at or after its end; a thread counts the ticks it had at its last snapshot inside the window. Classes:
Truffle's compiler threads (the PHP code's compilations, libgraal's work and its GC included: it runs in the calling
thread), HotSpot's compiler threads (the JVM's own Java code), the GC and VM threads, the HTTP server's threads (the
requests' PHP: interpreted, compiled, built-ins), the rest; "not seen" is what the cgroup counted and no thread
snapshot did. With the engine log (TraceCompilation), each window also gets the compilations finished in it, by
tier: their count and their compile time (Truffle's Time, wall clock on a compiler thread).

Usage: python cpusplit.py VISITS.json THREADS.txt [ENGINE.log]
"""
import json
import re
import sys
from datetime import datetime, timezone

HEADER = re.compile(r"## usage_usec (\d+) clk_tck (\d+) time ([\d.]+)")
DONE = re.compile(r"^\[engine\] opt done .*?\|Tier (\d)\|Time\s+(\d+)\(\s*(\d+)\+(\d+)\s*\)ms.*?\|UTC ([0-9T:.-]+)")


def snapshots(path):
    block = None
    for line in open(path, encoding="utf-8", errors="replace"):
        m = HEADER.match(line)
        if m:
            if block:
                yield block
            block = {"usage": int(m[1]) / 1e6, "tick": int(m[2]), "time": float(m[3]), "threads": {}}
            continue
        parts = line.rstrip("\n").split("\t")
        if block and len(parts) == 3 and len(parts[2].split()) == 2:
            utime, stime = (int(x) for x in parts[2].split())
            block["threads"][parts[0]] = (parts[1], utime + stime)
    if block:
        yield block


def group(name):
    if name.startswith("Truffle"):
        return "Truffle compiler"
    if name.startswith(("JVMCI", "C1 Compiler", "C2 Compiler")):
        return "HotSpot compiler"
    if name.startswith(("GC Thread", "G1 ", "VM Thread", "VM Periodic", "Native Heap Tri", "Monitor Deflati")):
        return "GC and VM"
    if name.startswith(("pool-", "HTTP-", "req-rsp")):
        return "requests"
    return "other"


def split(snaps, start, end):
    first = max((s for s in snaps if s["time"] <= start), key=lambda s: s["time"], default=snaps[0])
    last = min((s for s in snaps if s["time"] >= end), key=lambda s: s["time"], default=snaps[-1])
    inside = [s for s in snaps if first["time"] <= s["time"] <= last["time"]]
    seen = {}
    for s in inside:
        seen.update(s["threads"])
    tick = first["tick"]
    groups, names = {}, {}
    for tid, (name, ticks) in seen.items():
        delta = (ticks - first["threads"].get(tid, (name, 0))[1]) / tick
        groups[group(name)] = groups.get(group(name), 0) + delta
        plain = re.sub(r"[#\-]?\d+$", "", name).strip()
        names[plain] = names.get(plain, 0) + delta
    total = last["usage"] - first["usage"]
    groups["not seen"] = total - sum(groups.values())
    return total, groups, names, last["time"] - first["time"]


def compilations(log, start, end):
    tiers = {}
    for line in open(log, encoding="utf-8", errors="replace"):
        m = DONE.match(line)
        if not m:
            continue
        t = datetime.fromisoformat(m[5].replace("Z", "+00:00")).replace(tzinfo=timezone.utc).timestamp()
        if start <= t < end:
            n, ms, pe, rest = tiers.get(m[1], (0, 0, 0, 0))
            tiers[m[1]] = (n + 1, ms + int(m[2]), pe + int(m[3]), rest + int(m[4]))
    return tiers


def main():
    visits = json.load(open(sys.argv[1]))
    snaps = list(snapshots(sys.argv[2]))
    log = sys.argv[3] if len(sys.argv) > 3 else None
    warm = visits["warm"]
    ends = [visits["cold"]["t"]] + [v["t"] for v in warm]
    n = len(warm)
    windows = [("cold visit (start to its end)", snaps[0]["time"], ends[0], 1),
               ("visits 1-50", ends[0], ends[min(50, n)], min(50, n)),
               (f"visits 51-{n - 50}", ends[min(50, n)], ends[n - 50], n - 100),
               (f"visits {n - 49}-{n} (the warm window)", ends[n - 50], ends[n], 50)]
    keys = ["requests", "Truffle compiler", "HotSpot compiler", "GC and VM", "other", "not seen"]
    for title, start, end, count in windows:
        if count <= 0:
            continue
        total, groups, names, span = split(snaps, start, end)
        print(f"{title}: {total:.1f} CPU-s in {span:.0f} s, {total / count:.2f} per visit")
        for key in keys:
            value = groups.get(key, 0)
            print(f"  {key:<17} {value / count:6.2f} per visit  {100 * value / max(total, 1e-9):5.1f}%")
        top = sorted(names.items(), key=lambda kv: -kv[1])[:6]
        print("  top threads: " + ", ".join(f"{k} {v:.1f}" for k, v in top))
        if log:
            for tier, (c, ms, pe, rest) in sorted(compilations(log, start, end).items()):
                print(f"  tier {tier}: {c} compilations done, {ms / 1000:.1f} s compiling ({pe / 1000:.1f} PE +"
                      f" {rest / 1000:.1f} Graal), {ms / 1000 / count:.2f} s per visit")


main()
