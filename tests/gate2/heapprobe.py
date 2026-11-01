"""Live heap by requests in flight, from a server log with -Dphp.heapprobe (HttpMain's HeapProbe): each line is the
heap right after a full collection and the requests running then. The server's own heap is the median with none in
flight in the segment's idle stretch; a request's share is the live heap above that.

Segments are split at 10 s or longer without a probe in flight, so a run of 1, 4 then 8 clients separated by idle
pauses gives one row each. Usage: python heapprobe.py SERVER_LOG [SKIP_LINES]
"""
import json
import statistics
import sys


def main():
    probes = []
    for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
        if line.startswith("heap {"):
            try:
                probes.append(json.loads(line[5:]))
            except ValueError:
                pass
    probes = probes[int(sys.argv[2]) if len(sys.argv) > 2 else 0:]
    # segments: busy stretches between idle ones
    segments, current, idle = [], [], []
    last_busy = None
    for p in probes:
        busy = p["in_flight_max"] > 0
        if busy:
            if last_busy is not None and p["t_ms"] - last_busy > 10000 and current:
                segments.append((idle, current))
                current, idle = [], []
            current.append(p)
            last_busy = p["t_ms"]
        else:
            idle.append(p)
    if current:
        segments.append((idle, current))
    mb = 2 ** 20
    print("segment  idle live MB (median)  busy: probes, max in flight, live MB median / p90 / max, "
          "above idle MB median / max, per request in flight MB (median)")
    for i, (idle, busy) in enumerate(segments):
        base = statistics.median(p["live"] for p in idle) / mb if idle else float("nan")
        live = sorted(p["live"] / mb for p in busy)
        steady = [p for p in busy if p["in_flight"] == p["in_flight_max"]]
        per = [(p["live"] / mb - base) / p["in_flight"] for p in steady if p["in_flight"] > 0]
        p90 = live[int(0.9 * (len(live) - 1))]
        print(f"{i}  {base:8.1f}  {len(busy)}, {max(p['in_flight_max'] for p in busy)}, "
              f"{statistics.median(live):.1f} / {p90:.1f} / {live[-1]:.1f}, "
              f"{statistics.median(live) - base:.1f} / {live[-1] - base:.1f}, "
              f"{statistics.median(per) if per else float('nan'):.1f}")
        hist = {}
        for p in steady:
            hist.setdefault(p["in_flight"], []).append(p["live"] / mb - base)
        print("   by requests in flight: " + ", ".join(
            f"{k}: {statistics.median(v):.1f} MB (max {max(v):.1f}, n={len(v)})" for k, v in sorted(hist.items())))


if __name__ == "__main__":
    main()
