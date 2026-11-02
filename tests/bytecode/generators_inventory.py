"""The generator inventory: from the logs of tests/bytecode/scan.php runs (-Dphp.bytecode.report=all), every
generator function (a function, method or closure containing yield) of each source, whether it runs on bytecode,
and for those on the tree the constructs that keep it there.

A generator on the tree is reported as "all NAME: generator | constructs..." (before S3: every generator), one on
bytecode as "bytecode NAME (generator)". A body the tree can't translate either (its error is in the scan's stdout,
FILE.out beside FILE.log) is still counted on the tree; the last column counts those errors.

Usage: python -I generators_inventory.py SOURCE=LOG [SOURCE=LOG...] [--details]
"""
import collections
import os
import re
import sys


def construct(reason):
    """A short name for what keeps a function on the tree."""
    if reason == "generator":
        return None
    if reason.startswith("built-in compiled in place"):
        return "built-ins compiled in place"
    match = re.search(r"\((AST_\w+) at line", reason)
    kind = match.group(1) if match else ""
    if "$this" in reason:
        return "$this"
    if reason.startswith("anonymous class"):
        return "anonymous class"
    if kind == "AST_NAMED_ARG":
        return "named argument"
    if reason.startswith("write to a property of an expression"):
        return "assignment to a property of an expression"
    if re.match(r"(argument|read of|assignment to) \$(_[A-Z]+|GLOBALS)\b", reason):
        return "superglobal"
    if "a place other than a variable" in reason and kind == "AST_VAR":
        return "variable variable"
    names = {
        "AST_YIELD": "yield", "AST_YIELD_FROM": "yield from", "AST_PROP": "property access",
        "AST_NULLSAFE_PROP": "property access", "AST_STATIC_PROP": "static property",
        "AST_METHOD_CALL": "method call", "AST_NULLSAFE_METHOD_CALL": "method call", "AST_STATIC_CALL": "static call",
        "AST_ISSET": "isset / empty", "AST_EMPTY": "isset / empty", "AST_CLASS_CONST": "class constant",
        "AST_CONST": "constant", "AST_CAST": "cast", "AST_NEW": "new", "AST_INSTANCEOF": "instanceof",
        "AST_CLONE": "clone", "AST_THROW": "throw", "AST_TRY": "try", "AST_SWITCH": "switch",
        "AST_ENCAPS_LIST": "string interpolation", "AST_CLOSURE": "closure", "AST_ARROW_FUNC": "closure",
        "AST_GLOBAL": "global", "AST_STATIC": "static variable", "AST_UNSET": "unset", "AST_ASSIGN_REF": "reference",
        "AST_REF": "reference", "AST_INCLUDE_OR_EVAL": "include / eval", "AST_PRINT": "print",
        "AST_EXIT": "exit", "AST_MAGIC_CONST": "magic constant", "AST_CLASS_NAME": "::class",
        "AST_UNPACK": "argument unpacking", "AST_NAMED_ARG": "named argument", "AST_GOTO": "goto",
        "AST_LABEL": "goto", "AST_LIST": "list()", "AST_SHELL_EXEC": "shell exec",
    }
    if reason.startswith("assignment target"):
        return "assignment to " + {"AST_ARRAY": "list()", "AST_PROP": "property", "AST_DIM": "nested element",
                                   "AST_STATIC_PROP": "static property"}.get(kind, kind)
    if reason.startswith("foreach by reference"):
        return "foreach by reference"
    if reason.startswith("foreach into") or reason.startswith("foreach key"):
        return "foreach into a place other than a variable"
    if reason.startswith("call of a callable value"):
        return "call of a callable value"
    if re.match(r"\w+\(\)", reason):
        return reason.split(" (")[0]
    if reason.startswith("default value"):
        return "parameter default"
    if reason.startswith("binary operator") or reason.startswith("unary operator"):
        return "operator " + reason.split(" (")[0].split(" ")[-1]
    if reason.startswith("break out of"):
        return "break/continue out of several levels"
    if reason.startswith("argument"):
        return "argument (place by reference)"
    if reason.startswith("array"):
        return "array literal (mixed keys, references, spreads)"
    if reason.startswith("[] for reading") or reason.startswith("??="):
        return reason.split(" (")[0]
    if kind in names:
        return names[kind]
    return reason.split(" (")[0] + (" " + kind if kind else "")


def read(log):
    # a function is named by its file (scan.php's "file" lines) and its name: php-src's tests reuse names
    on_bytecode, on_tree = [], {}
    file = ""
    for line in open(log, encoding="utf-8", errors="replace"):
        if line.startswith("php.bytecode file "):
            file = line[len("php.bytecode file "):].strip().rsplit("/", 1)[-1]
        elif line.startswith("php.bytecode bytecode ") and line.rstrip().endswith(" (generator)"):
            name = line[len("php.bytecode bytecode "):].rstrip()[:-len(" (generator)")]
            if f"{file}: {name}" not in on_bytecode:
                on_bytecode.append(f"{file}: {name}")
        elif line.startswith("php.bytecode all "):
            name, _, reasons = line[len("php.bytecode all "):].strip().partition(": generator")
            parts = ["generator"] + [r.strip() for r in reasons.split(" | ") if r.strip()]
            if reasons or line.rstrip().endswith(": generator"):
                on_tree[f"{file}: {name}"] = parts
    failed = []
    out = os.path.splitext(log)[0] + ".out"
    if os.path.exists(out):
        failed = [line.strip() for line in open(out, encoding="utf-8", errors="replace") if line.strip()]
    return on_bytecode, on_tree, failed


def main():
    sources = [a.split("=", 1) for a in sys.argv[1:] if "=" in a]
    details = "--details" in sys.argv
    print("| source | generators | on bytecode | on the tree | of which the tree can't translate the body either |")
    print("|---|---|---|---|---|")
    blockers = collections.Counter()
    only = collections.Counter()
    per_source = {}
    for source, log in sources:
        on_bytecode, on_tree, failed = read(log)
        per_source[source] = (on_bytecode, on_tree, failed)
        print(f"| {source} | {len(on_bytecode) + len(on_tree)} | {len(on_bytecode)} | {len(on_tree)} "
              f"| {len(failed)} |")
        for reasons in on_tree.values():
            found = {c for c in map(construct, reasons) if c}
            blockers.update(found)
            if len(found) == 1:
                only.update(found)
    print()
    print("| construct | generators it keeps on the tree | the only one |")
    print("|---|---|---|")
    for name, n in blockers.most_common():
        print(f"| {name} | {n} | {only.get(name, 0)} |")
    if details:
        for source, (on_bytecode, on_tree, failed) in per_source.items():
            print(f"\n## {source}")
            for name in on_bytecode:
                print(f"- bytecode: {name}")
            for name, reasons in on_tree.items():
                print(f"- tree: {name}: {', '.join(sorted({c for c in map(construct, reasons) if c}))}")
            for error in failed:
                print(f"- not translated: {error}")


if __name__ == "__main__":
    main()
