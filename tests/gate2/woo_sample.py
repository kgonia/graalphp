"""The server's CPU time and memory during one load run (tests/gate2/woo.sh load), from a container on the client's
cores with the host's process table (--pid=host) and cgroups (-v /sys/fs/cgroup:/hostcg:ro), as visits.py's.

Samples every half second until STOP_FILE exists, then writes OUT: the CPU seconds the server's container used
between start and stop, the most RssAnon (all its processes), cgroup anon, shmem (php-fpm's OPcache) and memory.current seen, the cgroup's
memory.peak (over the container's life: the warm-up's included), and the samples.

DB_ID_FILE (optional): the database's container, whose CPU seconds over the run go to OUT too ("db_cpu_s").

Usage: python woo_sample.py ID_FILE STOP_FILE OUT [DB_ID_FILE]
"""
import json
import os
import sys
import time


def cgroup_of(container):
    return "/hostcg/docker/" + container


def cpu(cgroup):
    for line in open(cgroup + "/cpu.stat"):
        name, value = line.split()
        if name == "usage_usec":
            return int(value) / 1e6
    raise ValueError("no usage_usec")


def memory(cgroup):
    """MB: the processes' RssAnon, the cgroup's anonymous memory, its current."""
    rss_anon = 0
    for pid in open(cgroup + "/cgroup.procs").read().split():
        try:
            for line in open(f"/proc/{pid}/status"):
                if line.startswith("RssAnon:"):
                    rss_anon += int(line.split()[1])
        except OSError:
            pass
    stat = {line.split()[0]: int(line.split()[1]) for line in open(cgroup + "/memory.stat")}
    anon = stat["anon"]
    return {"rss_anon": rss_anon / 1024, "anon": anon / 2**20, "shmem": stat.get("shmem", 0) / 2**20,
            "current": int(open(cgroup + "/memory.current").read()) / 2**20}


def main():
    id_file, stop_file, out = sys.argv[1:4]
    container = open(id_file).read().split()[0]
    cgroup = cgroup_of(container)
    db = cgroup_of(open(sys.argv[4]).read().split()[0]) if len(sys.argv) > 4 else None
    db_start = cpu(db) if db else 0
    start, cpu_start = time.time(), cpu(cgroup)
    samples = []
    while not os.path.exists(stop_file):
        sample = memory(cgroup)
        sample["t"] = round(time.time() - start, 2)
        sample["cpu"] = round(cpu(cgroup) - cpu_start, 3)
        if db:
            sample["db_cpu"] = round(cpu(db) - db_start, 3)
        samples.append(sample)
        time.sleep(0.5)
    result = {"seconds": time.time() - start, "cpu_s": cpu(cgroup) - cpu_start,
              "peak": int(open(cgroup + "/memory.peak").read()) / 2**20}
    if db:
        result["db_cpu_s"] = cpu(db) - db_start
    for key in ("rss_anon", "anon", "current", "shmem"):
        result["max_" + key] = max((s[key] for s in samples), default=0)
    result["samples"] = samples
    with open(out, "w") as f:
        json.dump(result, f)


if __name__ == "__main__":
    main()
