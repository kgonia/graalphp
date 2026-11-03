"""Two servers' compilations side by side, from engine logs of -Dpolyglot.engine.TraceCompilationDetails=true.

Per log: the compilations done by tier (count, compile time split into partial evaluation and the rest, IR after PE
and after the whole compilation, code size), how many targets compiled again at a tier they already had, the
invalidations and deoptimizations by reason, the dequeues by reason and the root kinds (PHP functions, file
top-level code, built-ins, split copies). Then, for the PHP functions both compiled at the same tier, the ratios
(B over A) of the first compilation's numbers, median and total, and the TOP functions by B's compile time.

Usage: python compare.py A.log B.log [TOP] [--names NAME,...]
"""
import collections
import re
import statistics
import sys

DONE = re.compile(r"^\[engine\] opt done\s+engine=\d+\s+id=\s*(\d+)\s+(.*?)\s*\|Tier (\d)\|Time\s+(\d+)\(\s*(\d+)\+(\d+)\s*\)ms"
                  r"\|AST\s+(\d+)\|Inlined\s+(\d+)Y\s+(\d+)N\|IR\s+(\d+)/\s*(\d+)\|CodeSize\s+(\d+)")
EVENT = re.compile(r"^\[engine\] opt (inval\.|deopt|unque\.|failed|queued|start)\s+engine=\d+\s+id=\s*(\d+)\s+(\S+)")
REASON = re.compile(r"\|Reason:? (.*?)\s*$")


def kind(name):
    if "<split-" in name:
        return "split copy"
    if name.startswith("FileRootNode"):
        return "file top level"
    if name.startswith("BuiltinRootNode"):
        return "built-in"
    if name.startswith("ThunkRootNode"):
        return "thunk"
    return "PHP function"


def read(path):
    done = []
    events = collections.Counter()
    reasons = collections.defaultdict(collections.Counter)
    targets = collections.defaultdict(collections.Counter)
    for line in open(path, encoding="utf-8", errors="replace"):
        m = DONE.match(line)
        if m:
            done.append({"id": int(m[1]), "name": m[2], "tier": int(m[3]), "ms": int(m[4]), "pe": int(m[5]),
                         "rest": int(m[6]), "ast": int(m[7]), "inl": int(m[8]), "ir": int(m[10]),
                         "ir_after": int(m[11]), "code": int(m[12])})
            continue
        m = EVENT.match(line)
        if m:
            what = m[1]
            events[what] += 1
            r = REASON.search(line)
            reason = re.sub(r"0x[0-9a-f]+|@[0-9a-f]+|\d+", "#", r[1])[:110] if r else "-"
            if what != "queued" and what != "start":
                reasons[what][reason] += 1
                targets[what][m[3]] += 1
    return done, events, reasons, targets


def summary(label, path):
    done, events, reasons, targets = read(path)
    print(f"== {label}: {path}")
    for tier in (1, 2):
        d = [c for c in done if c["tier"] == tier]
        if not d:
            continue
        ms = sum(c["ms"] for c in d)
        print(f"tier {tier}: {len(d)} done, {ms / 1000:.1f} s ({sum(c['pe'] for c in d) / 1000:.1f} PE +"
              f" {sum(c['rest'] for c in d) / 1000:.1f} rest), IR after PE {sum(c['ir'] for c in d):,}, after"
              f" {sum(c['ir_after'] for c in d):,}, code {sum(c['code'] for c in d) / 1e6:.1f} MB;"
              f" ms per 1000 IR (PE) {1000 * ms / max(1, sum(c['ir'] for c in d)):.1f}")
        per = collections.Counter((c["id"]) for c in d)
        again = sum(n - 1 for n in per.values() if n > 1)
        print(f"  {len(per)} targets, {again} compilations of a target again at this tier,"
              f" {sum(1 for n in per.values() if n > 2)} targets 3+ times")
        by_kind = collections.defaultdict(lambda: [0, 0])
        for c in d:
            by_kind[kind(c["name"])][0] += 1
            by_kind[kind(c["name"])][1] += c["ms"]
        print("  by kind: " + ", ".join(f"{k} {n} ({ms / 1000:.0f} s)" for k, (n, ms) in
                                       sorted(by_kind.items(), key=lambda kv: -kv[1][1])))
    print("events: " + ", ".join(f"{k} {v}" for k, v in sorted(events.items())))
    for what in ("inval.", "deopt", "unque.", "failed"):
        if reasons[what]:
            print(f"  {what} by reason: " + "; ".join(f"{r} {n}" for r, n in reasons[what].most_common(8)))
            print(f"  {what} top targets: " + ", ".join(f"{t} {n}" for t, n in targets[what].most_common(8)))
    return done


def first_by(done):
    out = {}
    for c in done:
        if kind(c["name"]) != "PHP function":
            continue
        out.setdefault((c["name"], c["tier"]), c)
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    names = None
    if "--names" in sys.argv:
        names = sys.argv[sys.argv.index("--names") + 1].split(",")
        args = [a for a in args if a != sys.argv[sys.argv.index("--names") + 1]]
    top = int(args[2]) if len(args) > 2 else 20
    a, b = first_by(summary("A", args[0])), first_by(summary("B", args[1]))
    for tier in (1, 2):
        both = [k for k in a if k in b and k[1] == tier]
        if not both:
            continue
        print(f"\ntier {tier}: {len(both)} PHP functions compiled by both (first compilation of each), B / A:")
        for field in ("ms", "pe", "rest", "ir", "ir_after", "code"):
            ratios = [b[k][field] / a[k][field] for k in both if a[k][field] > 0]
            ta, tb = sum(a[k][field] for k in both), sum(b[k][field] for k in both)
            print(f"  {field:<8} median {statistics.median(ratios):.2f}, total {tb / max(ta, 1):.2f}"
                  f" ({ta:,} against {tb:,})")
        pe_per_a = sum(a[k]["pe"] for k in both) / max(1, sum(a[k]["ir"] for k in both))
        pe_per_b = sum(b[k]["pe"] for k in both) / max(1, sum(b[k]["ir"] for k in both))
        rest_per_a = sum(a[k]["rest"] for k in both) / max(1, sum(a[k]["ir_after"] for k in both))
        rest_per_b = sum(b[k]["rest"] for k in both) / max(1, sum(b[k]["ir_after"] for k in both))
        print(f"  PE ms per 1000 IR after PE: A {1000 * pe_per_a:.1f}, B {1000 * pe_per_b:.1f};"
              f" rest ms per 1000 IR after: A {1000 * rest_per_a:.1f}, B {1000 * rest_per_b:.1f}")
        chosen = [k for k in both if names and k[0] in names] if names else \
            sorted(both, key=lambda k: -b[k]["ms"])[:top]
        print(f"  {'function':<46} {'ms A/B':>11} {'PE A/B':>11} {'rest A/B':>11} {'IR PE A/B':>13}"
              f" {'IR after A/B':>15} {'code A/B':>15} {'inl A/B':>8}")
        for k in chosen:
            x, y = a[k], b[k]
            print(f"  {k[0][:46]:<46} {x['ms']:>5}/{y['ms']:<5} {x['pe']:>5}/{y['pe']:<5} {x['rest']:>5}/{y['rest']:<5}"
                  f" {x['ir']:>6}/{y['ir']:<6} {x['ir_after']:>7}/{y['ir_after']:<7} {x['code']:>7}/{y['code']:<7}"
                  f" {x['inl']:>3}/{y['inl']:<3}")


main()
