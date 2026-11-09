"""Groups tests/fuzz/run.sh's differing programs by their probable cause: the kind of the statement at the first
difference (the one whose "@k" marker follows the differing line) and the difference's direction (early: Truffle PHP
reached zero where PHP didn't, missed: PHP destroyed an object Truffle PHP didn't, order: both destroyed, different
objects). One frequent bug makes most programs differ and hides the rest, so the number of groups, not the number of
equal programs, says how many things are wrong. Prints each group's count and its first few programs, smallest first
(the ones to reduce).

Usage: python -I tests/fuzz/triage.py DIR [--mode=before-end]
"""
import collections
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare import cut  # noqa: E402

# the statement kinds gen.py writes, by a pattern of their text (first match wins)
KINDS = [
    ("throw", r"thrower\("), ("unsete", r"^unset\(\$e\)"), ("closurecall", r"instanceof Closure"),
    ("closure", r"function \(\) use"), ("fluentstore", r"= \(new D\([^)]*\)\)->self\(\)"),
    ("fluent", r"^\(new D\([^)]*\)\)->self\(\)"), ("setp", r"->setP\("), ("take", r"->take\("),
    ("pop", r"array_pop"), ("shift", r"array_shift"), ("clone", r"clone "), ("pair", r"pair\("),
    ("local", r"^local\("), ("fresh", r"fresh\("), ("idtemp", r"id\(new D"), ("id", r"\bid\("),
    ("droptemp", r"drop\(new D"), ("drop", r"drop\("), ("call", r"= [fg]\d+\("), ("gcall", r"^g\d+\(\)"),
    ("arrcopy", r"\[\] = 1"), ("push", r"\[\] = "), ("elem", r"\['k'\] = "), ("arr", r"= \["),
    ("propnew", r"->q = new"), ("propnull", r"->[pq] = null"), ("read", r"= \$\w+->[pq];"), ("prop", r"->p = "),
    ("ref", r"= &"), ("new", r"= new D"), ("null", r"= null;"), ("unset", r"^unset\("), ("copy", r"= \(?\$"),
]


def kind(statement):
    s = re.sub(r"echo \"@\d+\\n\";\s*$", "", statement.strip()).strip()
    for name, pattern in KINDS:
        if re.search(pattern, s):
            return name
    return "other"


def first_diff(exp, act):
    for i, (x, y) in enumerate(zip(exp, act)):
        if x != y:
            return i
    return min(len(exp), len(act)) if len(exp) != len(act) else None


def main():
    d = sys.argv[1]
    mode = next((a.split("=", 1)[1] for a in sys.argv[2:] if a.startswith("--mode=")), "before-end")
    groups = collections.defaultdict(list)
    for n in os.listdir(os.path.join(d, "expected")):
        if not n.endswith(".out"):
            continue
        read = lambda side: open(os.path.join(d, side, n), encoding="utf-8", errors="replace").read().splitlines()
        exp, act = cut(read("expected"), mode), cut(read("actual"), mode)
        i = first_diff(exp, act)
        if i is None:
            continue
        e, a = (exp[i] if i < len(exp) else ""), (act[i] if i < len(act) else "")
        direction = ("early" if a.startswith("~") and not e.startswith("~") else
                     "missed" if e.startswith("~") and not a.startswith("~") else
                     "order" if e.startswith("~") else "other")
        # the statement: the one whose marker is the first "@k" at or after the difference, on PHP's side
        mark = next((l for l in exp[i:] if l.startswith("@")), "@end")
        src = open(os.path.join(d, "cases", n[:-4]), encoding="utf-8").read().splitlines()
        stmt = next((l for l in src if f'echo "{mark}\\n";' in l), "") if mark != "@end" else "<end>"
        where = "fn" if stmt.startswith("    ") else "top"
        groups[(direction, kind(stmt), where)].append((len(src), n[:-8]))
    total = sum(len(v) for v in groups.values())
    print(f"{total} differing programs, {len(groups)} groups (direction, statement kind, top level or function)")
    for key, progs in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        print(f"{len(progs):5}  {key[0]:6} {key[1]:12} {key[2]:3}  e.g. {', '.join(p for _, p in sorted(progs)[:4])}")


if __name__ == "__main__":
    main()
