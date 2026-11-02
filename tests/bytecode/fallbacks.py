"""The fallback inventory: from logs of -Dphp.bytecode.report=all runs (a server's log, tests/bytecode/scan.php's
stderr, phpt's batch.log), how many functions run on bytecode and on the tree, and per construct how many functions
it keeps on the tree: as any of their constructs, and as the only one. A function is counted once per source by its
name and its constructs (closures share a name, so equal closures count once).

Usage: python -I fallbacks.py SOURCE=LOG [SOURCE=LOG...]
"""
import collections
import re
import sys

LINE = re.compile(r"php\.bytecode (bytecode|tree    |all     ) (.*?)(?:: (.*))?$")


def construct(reason):
    """The reason without its line: '... (AST_X at line N)' -> '... (AST_X)'."""
    return re.sub(r" at line -?\d+\)", ")", reason).strip()


def read(path):
    bytecode, tree = set(), {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = LINE.search(line.rstrip("\n"))
            if not m:
                continue
            kind, name, reasons = m.group(1).strip(), m.group(2), m.group(3) or ""
            if kind == "bytecode":
                bytecode.add(name.removesuffix(" (generator)"))
            elif kind == "all":
                parts = tuple(sorted({construct(r) for r in reasons.split(" | ") if r and r != "generator"}))
                tree[(name, parts)] = parts
    return bytecode, tree


def main():
    for arg in sys.argv[1:]:
        source, path = arg.split("=", 1)
        bytecode, tree = read(path)
        anywhere, only = collections.Counter(), collections.Counter()
        for parts in tree.values():
            for p in parts:
                anywhere[p] += 1
            if len(parts) == 1:
                only[parts[0]] += 1
        print(f"### {source}: {len(bytecode)} functions on bytecode, {len(tree)} on the tree")
        print()
        print("| construct | functions | the only one |")
        print("|---|---|---|")
        for p, n in anywhere.most_common():
            print(f"| {p} | {n} | {only.get(p, 0)} |")
        print()


main()
