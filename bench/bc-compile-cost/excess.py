"""Where a log B's compile time over A's comes from, for the PHP functions both compiled at a tier (first compilation
of each): the excess (B - A, ms) split by the cause class of each function: B's IR after PE over A's (graph
growth, >= 1.5), the Graal tier's time per IR node after the compilation (B over A >= 2: a slow phase), or neither.

Usage: python excess.py A.log B.log TIER
"""
import re
import sys
DONE = re.compile(r"^\[engine\] opt done\s+engine=\d+\s+id=\s*\d+\s+(.*?)\s*\|Tier (\d)\|Time\s+(\d+)\(\s*(\d+)\+(\d+)\s*\)ms"
                  r"\|AST\s+\d+\|Inlined\s+(\d+)Y\s+\d+N\|IR\s+(\d+)/\s*(\d+)")


def read(path, tier):
    out = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        m = DONE.match(line)
        if m and int(m[2]) == tier and "RootNode" not in m[1] and "<split" not in m[1]:
            out.setdefault(m[1], tuple(int(x) for x in m.groups()[2:]))
    return out


a, b = read(sys.argv[1], int(sys.argv[3])), read(sys.argv[2], int(sys.argv[3]))
classes = {}
for name in a.keys() & b.keys():
    ms_a, pe_a, rest_a, inl_a, ir_a, after_a = a[name]
    ms_b, pe_b, rest_b, inl_b, ir_b, after_b = b[name]
    if ir_b >= 1.5 * ir_a:
        c = "IR after PE >= 1.5x"
    elif after_a and after_b and rest_a > 20 and (rest_b / after_b) >= 2 * (rest_a / after_a):
        c = "Graal time per node >= 2x"
    else:
        c = "neither"
    n, ex, pe, rest, ta = classes.get(c, (0, 0, 0, 0, 0))
    classes[c] = (n + 1, ex + ms_b - ms_a, pe + pe_b - pe_a, rest + rest_b - rest_a, ta + ms_a)
total = sum(v[1] for v in classes.values())
for c, (n, ex, pe, rest, ta) in sorted(classes.items(), key=lambda kv: -kv[1][1]):
    print(f"{c:<28} {n:5} functions, excess {ex / 1000:7.1f} s ({100 * ex / total:5.1f}%; PE {pe / 1000:.1f} s,"
          f" Graal tier {rest / 1000:.1f} s), A's time {ta / 1000:.1f} s")
print(f"total excess {total / 1000:.1f} s")
