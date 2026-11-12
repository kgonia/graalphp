"""Where a server's memory.peak comes from (bench/cold-visit/runs.sh memdiag): memwatch.py's samples beside libgraal's
collections (-Djdk.graal.internal.PrintGC=true; one isolate per compiler thread, each logging its collections timed
from the VM's start) and the Truffle compilations running at the anon maximum (TraceCompilation's opt start / done).

Per run: memory.peak, the largest anon, file and kernel, RssAnon at the end and its largest per-second sample; when
anon peaked (s after the server's start), the largest isolate heap seen by a collection within 5 s of it and the
largest anywhere, and the compilations in flight at the anon maximum, largest first.

Usage: python -I memattr.py DIR TAG... (DIR/TAG.mem.json, DIR/TAG.server.log, DIR/engine-TAG.log)
"""
import datetime
import json
import os
import re
import sys

GC = re.compile(r'^\[(\d+\.\d+)s\] GC\((\d+)\) Pause (Full|Incremental) GC \(([^)]*)\) ([\d.]+)M->([\d.]+)M ([\d.]+)ms')
OPT = re.compile(r'\[engine\] opt (start|done)\s+engine=\d+\s+id=(\d+)\s+(\S+).*?\|Tier (\d).*\|UTC ([0-9T:.-]+)')
IR = re.compile(r'\|IR\s+(\d+)/\s*(\d+)')


def main():
    d, tags = sys.argv[1], sys.argv[2:]
    for tag in tags:
        mem = json.load(open(os.path.join(d, tag + ".mem.json")))
        samples = mem["samples"]
        gcs = []
        for line in open(os.path.join(d, tag + ".server.log"), encoding="utf-8", errors="replace"):
            m = GC.match(line)
            if m:
                gcs.append((float(m.group(1)), float(m.group(5)), float(m.group(6))))
        at = mem["anon_max_at"]
        near = [g for g in gcs if abs(g[0] - at) <= 5]
        print(f"{tag}: memory.peak {mem['peak']:.0f} MB; max anon {mem['max_anon']:.0f}, file {mem['max_file']:.0f}, "
              f"kernel {mem['max_kernel']:.0f}, RssAnon (1 s samples) {mem['max_rss_anon']:.0f}, at the end "
              f"{samples[-1]['rss_anon']:.0f}; anon within 5% of its max for {mem['anon_near_max_s']} s")
        p90 = sorted(s["anon"] for s in samples)[int(0.9 * len(samples))]
        print(f"  anon p90 {p90:.0f} MB; anon max at {at:.1f} s after the server's start")
        if gcs:
            big = max(gcs, key=lambda g: g[1])
            print(f"  libgraal collections: {len(gcs)}; largest isolate heap {big[1]:.0f} MB (-> {big[2]:.0f}) at "
                  f"{big[0]:.1f} s; within 5 s of the anon max: "
                  f"{max((g[1] for g in near), default=0):.0f} MB")
        engine = os.path.join(d, "engine-" + tag + ".log")
        if os.path.exists(engine):
            events = []
            for line in open(engine, encoding="utf-8", errors="replace"):
                m = OPT.search(line)
                if not m:
                    continue
                kind, cid, name, tier, utc = m.groups()
                ir = IR.search(line)
                t = datetime.datetime.fromisoformat(utc)
                events.append((t, kind, cid + ":" + tier, name, int(ir.group(2)) if ir else 0))
            if events:
                t0 = min(e[0] for e in events)
                print(f"  compilations: {sum(1 for e in events if e[1] == 'done')} done; the largest graphs (IR after):")
                for e in sorted((e for e in events if e[1] == "done"), key=lambda e: -e[4])[:6]:
                    print(f"    {e[4]:>8} nodes  tier {e[2].split(':')[1]}  {(e[0] - t0).total_seconds():6.1f} s after the "
                          f"first  {e[3]}")


if __name__ == "__main__":
    main()
