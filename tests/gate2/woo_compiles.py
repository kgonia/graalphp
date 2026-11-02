"""What Truffle compiled over a run, from an engine log of -Dpolyglot.engine.TraceCompilationDetails=true
(-Dpolyglot.log.file): compilations done per minute (and their time), the call targets compiled most often, how many
distinct targets were compiled once and how many again, and why compiled code was thrown away (invalidations and
deoptimizations by reason, and the targets they hit most).

Usage: python woo_compiles.py ENGINE_LOG [TOP]
"""
import collections
import re
import sys

LINE = re.compile(r"^\[engine\] opt (done|failed|inval\.|deopt|queued|unque\.|start)\s+engine=\d+\s+id=\s*(\d+)\s+(\S+)")
UTC = re.compile(r"\|UTC (\d{4}-\d\d-\d\dT\d\d:\d\d)")
TIME = re.compile(r"Time\s+(\d+)\(")
TIER = re.compile(r"\|Tier (\d)")
REASON = re.compile(r"\|Reason ([^|]+?)\s*$")


def main():
    top = int(sys.argv[2]) if len(sys.argv) > 2 else 15
    events = collections.Counter()
    per_minute = collections.defaultdict(lambda: [0, 0])
    done = collections.Counter()
    done_tier = collections.Counter()
    by_id = collections.defaultdict(int)
    names = {}
    reasons = collections.Counter()
    hit = collections.Counter()
    for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
        m = LINE.match(line)
        if not m:
            continue
        kind, ident, name = m.groups()
        events[kind] += 1
        names[ident] = name
        if kind == "done":
            minute = UTC.search(line)
            t = TIME.search(line)
            tier = TIER.search(line)
            if minute:
                per_minute[minute.group(1)][0] += 1
                per_minute[minute.group(1)][1] += int(t.group(1)) if t else 0
            done[name] += 1
            by_id[ident] += 1
            done_tier[tier.group(1) if tier else "?"] += 1
        elif kind in ("inval.", "deopt"):
            r = REASON.search(line.rstrip())
            reasons[(kind, r.group(1) if r else "?")] += 1
            hit[name] += 1
    print("events:", dict(events))
    print("compilations by tier:", dict(done_tier))
    again = sum(1 for n in by_id.values() if n > 1)
    print(f"call targets compiled: {len(by_id)}; compiled more than once: {again};"
          f" compilations of those: {sum(n for n in by_id.values() if n > 1)}")
    print("per minute (compilations, compile ms):")
    for minute in sorted(per_minute):
        print(f"  {minute} {per_minute[minute][0]:5d} {per_minute[minute][1]:8d}")
    print(f"most compiled ({top}):")
    for name, n in done.most_common(top):
        print(f"  {n:4d} {name}")
    print("invalidations / deoptimizations by reason:")
    for (kind, reason), n in reasons.most_common(top):
        print(f"  {n:6d} {kind} {reason}")
    print(f"most invalidated ({top}):")
    for name, n in hit.most_common(top):
        print(f"  {n:5d} {name}")


if __name__ == "__main__":
    main()
