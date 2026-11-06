"""Per function, the last compilation of each tier in Truffle compile logs: python irs.py A.log B.log [tier]
prints IR after PE / after all and code size side by side, the largest B/A first."""
import re
import sys

LINE = re.compile(r"opt done\s+(?:engine=\d+\s+)?id=\s*\d+\s+(?P<name>\S.*?)\s*\|Tier (?P<tier>\d).*?\|IR\s+(?P<pe>\d+)/\s*(?P<ir>\d+)"
                  r".*?\|CodeSize\s+(?P<code>\d+)")


def load(path, tier):
    out = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        m = LINE.search(line)
        if m and m["tier"] == tier:
            out[m["name"]] = (int(m["pe"]), int(m["ir"]), int(m["code"]))
    return out


tier = sys.argv[3] if len(sys.argv) > 3 else "2"
a, b = load(sys.argv[1], tier), load(sys.argv[2], tier)
rows = []
for name in a.keys() & b.keys():
    rows.append((b[name][2] / max(1, a[name][2]), name, a[name], b[name]))
rows.sort(key=lambda r: -max(r[2][2], r[3][2]))
print(f"{'function':50} {'A pe/ir/code':>22} {'B pe/ir/code':>22} B/A code")
for ratio, name, x, y in rows[:40]:
    print(f"{name[:50]:50} {'%d/%d/%d' % x:>22} {'%d/%d/%d' % y:>22} {ratio:.2f}")
only_a, only_b = sorted(a.keys() - b.keys()), sorted(b.keys() - a.keys())
print("only A:", len(only_a), only_a[:15])
print("only B:", len(only_b), only_b[:15])
