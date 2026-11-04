"""Every second, one server container's CPU per thread, read from outside the container (bench/perf-check/runs.sh).

bench/bc-compile-cost/runs.sh diag sampled the threads with a shell loop inside the server's container, so on the
server's own CPUs; this runs on the client's cores with the host's process table (--pid=host) and cgroups
(-v /sys/fs/cgroup:/hostcg:ro) and writes the same format, which bench/bc-compile-cost/cpusplit.py reads: per
snapshot a line "## usage_usec N clk_tck T time SECONDS", then one line per thread "TID<tab>NAME<tab>UTIME STIME".
It waits for ID_FILE (one line: the container's ID and its first process's PID, as tests/gate2/native.sh measure
writes it) and ends when that process is gone.

Usage: python threadcpu.py ID_FILE OUT
"""
import os
import sys
import time


def main():
    id_file, out = sys.argv[1:3]
    while not os.path.exists(id_file) or len(open(id_file).read().split()) < 2:
        time.sleep(0.05)
    container, pid = open(id_file).read().split()[:2]
    stat = f"/hostcg/docker/{container}/cpu.stat"
    tasks = f"/proc/{pid}/task"
    tick = os.sysconf("SC_CLK_TCK")
    with open(out, "w") as f:
        while os.path.isdir(tasks):
            try:
                usage = open(stat).readline().strip()
            except OSError:
                break
            lines = [f"## {usage} clk_tck {tick} time {time.time():.3f}"]
            for tid in os.listdir(tasks):
                try:
                    name = open(f"{tasks}/{tid}/comm").read().strip()
                    fields = open(f"{tasks}/{tid}/stat").read().rsplit(")", 1)[1].split()
                except OSError:
                    continue
                lines.append(f"{tid}\t{name}\t{fields[11]} {fields[12]}")
            f.write("\n".join(lines) + "\n")
            f.flush()
            time.sleep(1)


if __name__ == "__main__":
    main()
