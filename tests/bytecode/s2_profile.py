"""Where a WordPress visit's time goes, per PHP function, and how much of it S2 could speed up: from the CPU
sampler's JSON (-Dpolyglot.cpusampler=true -Dpolyglot.cpusampler.Output=json) and s2_reasons.py's JSON.

The sampler counts samples on the request threads' guest stacks, per root and tier (0 interpreted, 1 and 2
compiled). A root's self samples are its own code's: a PHP function's nodes, with the runtime they run inline (array
and string operations, the in-place built-ins, includes and their parsing, the first call's translation). Built-ins
are roots of their own (source "<builtin>"), so their time is apart.

Bytecode changes only the interpreter's own overhead, so the share it could speed up is the interpreted self time of
PHP functions on bytecode: S1's today, then each S2 step's. The bound is Amdahl's: 1 / ((1 - f) + f / s), with s
the bytecode interpreter's speed against the tree's, and compiled time unchanged (peak is equal).

Usage: python s2_profile.py REASONS.json S PROFILE.json [MINUS.json]
       (MINUS: a profile to subtract, e.g. the cold visit's from a cold-plus-warm run's, for the warm visits)
"""
import collections
import json
import sys

STEPS = ["now", "a", "b", "c", "d", "e", "f"]
NAMES = {"now": "on bytecode with S1", "a": "+ (a) property access", "b": "+ (b) method and static calls",
         "c": "+ (c) isset / empty", "d": "+ (d) global", "e": "+ (e) in-place built-ins",
         "f": "+ (f) casts and constants"}


def roots(path):
    """Per (root name, source name): self samples per tier [interpreted, tier 1, tier 2]."""
    data = json.load(open(path))
    totals = collections.defaultdict(lambda: [0, 0, 0])

    def walk(nodes):
        for node in nodes:
            source = (node.get("source_section") or {}).get("source_name", "")
            tiers = node.get("self_tier_count") or [node.get("self_hit_count", 0)]
            entry = totals[(node.get("root_name", "?"), source)]
            for i, count in enumerate(tiers[:3]):
                entry[min(i, 2)] += count
            walk(node.get("children", []))

    # one entry per context: a server runs each request in a context of its own
    for context in data.get("contexts", [data]):
        for thread in context.get("profile", []):
            walk(thread.get("samples", []))
    return totals


def main():
    reasons = json.load(open(sys.argv[1]))
    s = float(sys.argv[2])
    profile = roots(sys.argv[3])
    if len(sys.argv) > 4:
        minus = roots(sys.argv[4])
        for key, tiers in minus.items():
            profile[key] = [max(0, a - b) for a, b in zip(profile[key], tiers)]
    step_of = {name: "now" for name in reasons["bytecode"]}
    for name, info in reasons["tree"].items():
        if info["moves_at"] is not None:
            step_of[name] = info["moves_at"]
    total = sum(sum(t) for t in profile.values())
    builtins = sum(sum(t) for (name, source), t in profile.items() if source == "<builtin>")
    php = {key: t for key, t in profile.items() if key[1] != "<builtin>"}
    known = {key: t for key, t in php.items() if key[0] in step_of or key[0] in reasons["tree"]}
    other = {key: t for key, t in php.items() if key not in known}
    interpreted = sum(t[0] for t in known.values())
    compiled = sum(t[1] + t[2] for t in known.values())
    print(f"{total} samples: PHP functions interpreted {interpreted / total:.1%}, compiled {compiled / total:.1%}; "
          f"built-ins {builtins / total:.1%}; other roots (files' top-level code, generators' bodies, class "
          f"templates) {sum(sum(t) for t in other.values()) / total:.1%}, of which interpreted "
          f"{sum(t[0] for t in other.values()) / total:.1%}")
    print()
    print(f"| functions on bytecode | interpreted PHP-code self time, share of the visit | bound at s = {s} |")
    print("|---|---|---|")
    for i, step in enumerate(STEPS):
        steps = set(STEPS[:i + 1])
        f = sum(t[0] for (name, _), t in known.items() if step_of.get(name) in steps) / total
        print(f"| {NAMES[step]} | {f:.1%} | {1 / ((1 - f) + f / s):.3f}× |")
    print()
    print("Top interpreted PHP functions (self samples), the step that would move them:")
    for (name, source), t in sorted(known.items(), key=lambda kv: -kv[1][0])[:15]:
        info = reasons["tree"].get(name)
        where = step_of.get(name) or "blocked: " + ", ".join(info["blockers"][:3])
        print(f"  {t[0]:6d} interpreted, {t[1] + t[2]:6d} compiled  {name}  [{where}]")
    print("Top other roots:")
    for (name, source), t in sorted(other.items(), key=lambda kv: -sum(kv[1]))[:5]:
        print(f"  {sum(t):6d} ({t[0]} interpreted)  {name}  {source}")


if __name__ == "__main__":
    main()
