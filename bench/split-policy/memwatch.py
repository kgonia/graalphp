"""One server container's memory over its whole life, every 0.1 s: where its cgroup's memory.peak comes from
(wiki/bytecode-dsl.md, "Which splits pay", the patched processor). Run from a container with the host's cgroups
(-v /sys/fs/cgroup:/hostcg:ro) on the client's cores, started by runs.sh watch as soon as the server's container
exists; it ends when the container's cgroup goes away and writes OUT.

OUT: memory.peak as last read, the most memory.current, anon, file, kernel and RssAnon seen (RssAnon every second:
it walks /proc), the time of the anon maximum, and the seconds anon spent within 5 % of its maximum (a burst is a
few seconds, a plateau most of the run); every second's sample for the curve.

Usage: python memwatch.py CONTAINER_ID OUT
"""
import json
import os
import sys
import time


def main():
    container, out = sys.argv[1:3]
    cg = "/hostcg/docker/" + container
    for _ in range(600):
        if os.path.exists(cg + "/memory.stat"):
            break
        time.sleep(0.1)
    start = time.time()
    peak = 0.0
    best = {"current": 0.0, "anon": 0.0, "file": 0.0, "kernel": 0.0, "rss_anon": 0.0}
    anon_series = []
    seconds = []
    last_second = -1
    while True:
        try:
            stat = {line.split()[0]: int(line.split()[1]) for line in open(cg + "/memory.stat")}
            current = int(open(cg + "/memory.current").read()) / 2**20
            peak = int(open(cg + "/memory.peak").read()) / 2**20
        except OSError:
            break
        t = time.time() - start
        sample = {"current": current, "anon": stat["anon"] / 2**20, "file": stat["file"] / 2**20,
                  "kernel": stat.get("kernel", 0) / 2**20}
        anon_series.append((t, sample["anon"]))
        for key, value in sample.items():
            best[key] = max(best[key], value)
        if int(t) != last_second:
            last_second = int(t)
            rss = 0
            try:
                for pid in open(cg + "/cgroup.procs").read().split():
                    try:
                        for line in open(f"/proc/{pid}/status"):
                            if line.startswith("RssAnon:"):
                                rss += int(line.split()[1])
                    except OSError:
                        pass
            except OSError:
                break
            sample["rss_anon"] = rss / 1024
            best["rss_anon"] = max(best["rss_anon"], sample["rss_anon"])
            seconds.append(dict(sample, t=round(t, 1)))
        time.sleep(0.1)
    top = best["anon"]
    at = next((t for t, a in anon_series if a == top), None)
    near = sum(1 for _, a in anon_series if a >= 0.95 * top) * 0.1
    json.dump({"seconds": round(time.time() - start, 1), "peak": peak,
               **{"max_" + k: v for k, v in best.items()}, "anon_max_at": at, "anon_near_max_s": round(near, 1),
               "samples": seconds}, open(out, "w"))


if __name__ == "__main__":
    main()
