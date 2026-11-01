"""Allocation and GC per visit from a server log with phase counters (HttpMain -Dphp.phases=true, RequestPhases) and,
when the server ran with -XX:+PrintGC (a native image) or -Xlog:gc (the JVM), its collections. See wiki/gc.md.

A visit is the requests from one GET / to the next, as in phases_report.py. For the last N visits (default 30) it
prints the medians per visit of: the requests' wall time and allocated bytes, the bytes allocated by every thread
group (from alloc_by_thread, so a native image's compiler threads show), each collector's collections and time, the
GC pauses' share of the wall time, and the heap pools at the visit's end (used, committed, used after the last
collection). From the GC log: the collections in those visits, the heap before and after them, and how much each
young collection left.

Usage: python gcstats.py SERVER_LOG [N]
"""
import json
import re
import statistics
import sys


def lines(path):
    for line in open(path, encoding="utf-8", errors="replace"):
        if line.startswith("phases {"):
            yield ("p", json.loads(line[7:]))
        elif "GC(" in line or line.startswith("[GC") or "Pause" in line:
            yield ("g", line.strip())


def main():
    path, n = sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 30
    visits, current, gclog = [], None, []
    for kind, record in lines(path):
        if kind == "g":
            if current is not None:
                current["gc"].append(record)
            continue
        if record["method"] == "GET" and record["uri"] == "/":
            current = {"requests": [], "gc": []}
            visits.append(current)
        if current is not None:
            current["requests"].append(record)
    visits = [v for v in visits if len(v["requests"]) >= 6][-(n + 1):]
    # each visit's thread allocation: the difference to the visit before's last line
    # the heap's allocation between collections (a collection's "before" less the previous one's "after"), so the
    # threads a native image doesn't count (its compiler's, the HTTP server's) show beside the requests'
    previous_after = None
    for visit in visits:
        allocated = 0.0
        for g in visit["gc"]:
            m = re.search(r"([\d.]+)M->([\d.]+)M", g)
            if not m:
                continue
            if previous_after is not None:
                allocated += float(m.group(1)) - previous_after
            previous_after = float(m.group(2))
        visit["heap_allocated_mb"] = allocated
    rows = []
    for before, visit in zip(visits, visits[1:]):
        a, b = before["requests"][-1], visit["requests"][-1]
        row = {
            "wall_s": sum(r["wall_ns"] for r in visit["requests"]) / 1e9,
            "alloc_mb": sum(r["alloc_bytes"] for r in visit["requests"]) / 2**20,
            "gc_s_in_requests": sum(r["gc_ms"] for r in visit["requests"]) / 1e3,
        }
        for name, total in b["alloc_by_thread"].items():
            key = "pool" if "pool" in name else "compiler" if "ompiler" in name else "other"
            row["by_thread_" + key + "_mb"] = row.get("by_thread_" + key + "_mb", 0) + (
                total - a["alloc_by_thread"].get(name, 0)) / 2**20
        for name, (count, ms) in b["gcs"].items():
            c0, t0 = a["gcs"].get(name, [0, 0])
            row["gc_n " + name] = count - c0
            row["gc_ms " + name] = ms - t0
        for name, (used, committed, after) in b["pools"].items():
            row["pool_used_mb " + name] = used / 2**20
            row["pool_committed_mb " + name] = committed / 2**20
            row["pool_after_gc_mb " + name] = after / 2**20
        row["gclog_heap_allocated_mb"] = visit["heap_allocated_mb"]
        sizes = [re.findall(r"([\d.]+)M->([\d.]+)M", g) for g in visit["gc"]]
        row["gclog_collections"] = len([s for s in sizes if s])
        row["gclog_full"] = len([g for g in visit["gc"] if re.search(r"Full|Complete", g)])
        freed = [float(x) - float(y) for s in sizes for x, y in s[:1]]
        after = [float(y) for s in sizes for _, y in s[:1]]
        row["gclog_freed_mb_per_collection"] = statistics.median(freed) if freed else 0
        row["gclog_after_mb_median"] = statistics.median(after) if after else 0
        rows.append(row)
    if not rows:
        print("no visits")
        return
    keys = list(dict.fromkeys(k for r in rows for k in r))
    print(f"{len(rows)} visits ({path})")
    for k in keys:
        values = [r.get(k, 0) for r in rows]
        print(f"  {k:55s} median {statistics.median(values):10.3f}  min {min(values):10.3f}  max {max(values):10.3f}")


if __name__ == "__main__":
    main()
