"""Compares tests/fuzz/run.sh's outputs: target/fuzz/SEED/expected (PHP 8.5) against actual (Truffle PHP).

Modes (--mode=):
  full         the whole output, shutdown included
  before-end   up to the "@end" line: every object freed while the program runs, at its statement (the default;
               shutdown's order is a step of its own)
  no-destructors   every "~label" line dropped on both sides: the rest of the program must agree, which checks the
               generator and the runtime's other semantics, on a build without destructors
The error logs (*.err) are compared too, by message without file and line.
Prints one line per differing program (its first differing line on each side) and a summary.

Usage: python compare.py DIR [--mode=MODE] [--show=N]
"""
import os
import re
import sys

LOG = re.compile(r"^(?:PHP )?(Fatal error|Warning|Notice|Deprecated): +(.*?)(?: in \S+ on line \d+)?$")


def log_lines(path):
    """An error log's messages without the file and line, which differ between the two sides' paths."""
    if not os.path.exists(path):
        return []
    out = []
    for line in open(path, encoding="utf-8", errors="replace").read().splitlines():
        m = LOG.match(line)
        if m:
            out.append(f"{m.group(1)}: {m.group(2)}")
    return out


def cut(lines, mode):
    if mode == "no-destructors":
        return [l for l in lines if not l.startswith("~")]
    if mode == "before-end" and "@end" in lines:
        return lines[:lines.index("@end")]
    return lines


def main():
    d = sys.argv[1]
    mode = next((a.split("=", 1)[1] for a in sys.argv[2:] if a.startswith("--mode=")), "before-end")
    show = int(next((a.split("=", 1)[1] for a in sys.argv[2:] if a.startswith("--show=")), "20"))
    names = sorted((n for n in os.listdir(os.path.join(d, "expected")) if n.endswith(".out")),
                   key=lambda n: int(n[1:].split(".")[0]))
    bad = []
    for n in names:
        exp = open(os.path.join(d, "expected", n), encoding="utf-8", errors="replace").read().splitlines()
        act = open(os.path.join(d, "actual", n), encoding="utf-8", errors="replace").read().splitlines()
        e, a = cut(exp, mode), cut(act, mode)
        el, al = log_lines(os.path.join(d, "expected", n[:-4] + ".err")), log_lines(os.path.join(d, "actual", n[:-4] + ".err"))
        if e == a and el != al:
            i = next((i for i, (x, y) in enumerate(zip(el, al)) if x != y), min(len(el), len(al)))
            bad.append((n, i, "log: " + (el[i] if i < len(el) else "<end>"), "log: " + (al[i] if i < len(al) else "<end>"), ""))
        elif e != a:
            i = next((i for i, (x, y) in enumerate(zip(e, a)) if x != y), min(len(e), len(a)))
            bad.append((n, i, e[i] if i < len(e) else "<end>", a[i] if i < len(a) else "<end>", e[i - 1] if i else ""))
    for n, i, e, a, before in bad[:show]:
        print(f"DIFF {n.removesuffix('.out')} line {i + 1} after {before!r}: php {e!r}, truffle {a!r}")
    print(f"{len(names) - len(bad)}/{len(names)} equal ({mode}), {len(bad)} differ")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
