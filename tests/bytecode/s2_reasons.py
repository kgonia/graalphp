"""What S2 would move to bytecode on a workload: from a server's log under -Dphp.bytecode.report=all, every function
the workload called, the constructs that keep it on the tree, and how many functions each step of S2 frees.

Steps, cumulative: (a) property access (with $this), (b) method and static calls, (c) isset and empty, (d) global,
(e) the built-ins the tree compiles in place, (f) casts and constants. A function moves at the first step after
which none of its constructs is left. What still blocks the rest is grouped by kind.

Usage: python s2_reasons.py SERVER_LOG [--json OUT]   (OUT: per function its steps and blockers, for s2_profile.py)
"""
import collections
import json
import re
import sys

STEPS = ["a", "b", "c", "d", "e", "f"]
STEP_NAMES = {"a": "property access (and $this)", "b": "+ method and static calls", "c": "+ isset / empty",
              "d": "+ global", "e": "+ built-ins compiled in place", "f": "+ casts and constants"}


def category(reason):
    """The S2 step (a-f) that covers a construct, or the kind of what else blocks."""
    if reason == "generator":
        return "generator"
    if reason.startswith("built-in compiled in place"):
        return "e"
    match = re.search(r"\((AST_\w+) at line", reason)
    kind = match.group(1) if match else ""
    if kind in ("AST_PROP", "AST_NULLSAFE_PROP", "AST_STATIC_PROP") or "$this" in reason:
        return "a"
    if kind in ("AST_METHOD_CALL", "AST_STATIC_CALL", "AST_NULLSAFE_METHOD_CALL"):
        return "b"
    if kind in ("AST_ISSET", "AST_EMPTY"):
        return "c"
    if kind == "AST_GLOBAL":
        return "d"
    if kind in ("AST_CAST", "AST_CONST", "AST_CLASS_CONST", "AST_MAGIC_CONST"):
        return "f"
    if re.match(r"(compact|extract|func_get_args|func_num_args|get_defined_vars|get_object_vars)\(\)", reason):
        return "local scope by name (compact, extract, func_get_args)"
    if re.match(r"(call_user_func|call_user_func_array|get_class|get_called_class)\(\)", reason) \
            or reason.startswith("call of a callable value"):
        return "dynamic calls (callables, call_user_func)"
    if "reference" in reason or kind in ("AST_REF", "AST_ASSIGN_REF") or "capture" in reason:
        return "references"
    if kind == "AST_INCLUDE_OR_EVAL":
        return "include"
    if kind in ("AST_CLOSURE", "AST_ARROW_FUNC"):
        return "closures"
    if kind in ("AST_NEW", "AST_INSTANCEOF", "AST_CLONE"):
        return "new / instanceof / clone"
    if kind == "AST_STATIC":
        return "static variables"
    if kind in ("AST_TRY", "AST_THROW"):
        return "try / throw"
    # a switch left out has its cases translated alone, where their break and continue have no loop: they count
    # with the switch
    if kind in ("AST_SWITCH", "AST_MATCH", "AST_SWITCH_LIST", "AST_BREAK", "AST_CONTINUE"):
        return "switch / match"
    if kind in ("AST_GOTO", "AST_LABEL") or reason.startswith("goto"):
        return "goto"
    if kind in ("AST_ENCAPS_LIST", "AST_SHELL_EXEC"):
        return "string interpolation"
    if kind in ("AST_UNSET", "AST_DIM") or reason.startswith("[] for reading") or reason.startswith("??="):
        return "array element writes, unset, ??="
    if reason.startswith("argument") or kind in ("AST_UNPACK", "AST_NAMED_ARG"):
        return "arguments (unpacking, named, by-reference places)"
    if kind == "AST_ARRAY" or reason.startswith("array"):
        return "array literals (keyed with unkeyed, spreads)"
    if kind == "AST_CLASS_NAME":
        return "::class"
    if reason.startswith("binary operator") or reason.startswith("unary operator"):
        return "operators"
    if reason.startswith("default value"):
        return "parameter defaults"
    if reason.startswith("read of") or reason.startswith("assignment to") or "a place other than a variable" in reason:
        return "variables (superglobals, variable variables)"
    return "other: " + (kind or reason.split(" (")[0])


def main():
    log = sys.argv[1]
    out = sys.argv[sys.argv.index("--json") + 1] if "--json" in sys.argv else None
    bytecode, tree = set(), {}
    for line in open(log, encoding="utf-8", errors="replace"):
        if line.startswith("php.bytecode bytecode "):
            bytecode.add(line[len("php.bytecode bytecode "):].strip())
        elif line.startswith("php.bytecode all "):
            name, _, reasons = line[len("php.bytecode all "):].strip().partition(": ")
            tree[name] = [r.strip() for r in reasons.split(" | ") if r.strip()]
    print(f"{len(bytecode)} functions on bytecode, {len(tree)} on the tree "
          f"({len(bytecode) / (len(bytecode) + len(tree)):.1%} on bytecode)")
    print()
    functions = {}
    for name, reasons in tree.items():
        cats = {category(r) for r in reasons}
        steps = cats & set(STEPS)
        blockers = sorted(cats - set(STEPS))
        moves = None if blockers else max((STEPS.index(s) for s in steps), default=-1)
        functions[name] = {"reasons": reasons, "steps": sorted(steps), "blockers": blockers,
                           "moves_at": None if moves is None else (STEPS[moves] if moves >= 0 else "now")}
    print(f"constructs per function on the tree: median "
          f"{sorted(len(f['reasons']) for f in functions.values())[len(functions) // 2]}, "
          f"{sum(1 for f in functions.values() if any(r.endswith('(function)') for r in f['reasons']))} "
          f"stopped at the declaration, {sum(1 for f in functions.values() if '(more)' in f['reasons'])} cut off")
    print()
    print("| step | functions moved by it | on bytecode after it | share of the called functions |")
    print("|---|---|---|---|")
    total = len(bytecode) + len(tree)
    on = len(bytecode)
    for step in STEPS:
        moved = sum(1 for f in functions.values() if f["moves_at"] == step)
        on += moved
        print(f"| ({step}) {STEP_NAMES[step]} | {moved} | {on} | {on / total:.1%} |")
    print()
    blocked = [f for f in functions.values() if f["blockers"]]
    print(f"Still on the tree after (f): {len(blocked)} functions. What blocks them (a function may count under "
          f"several):")
    print()
    print("| blocker | functions | of which it is the only blocker |")
    print("|---|---|---|")
    counts = collections.Counter(b for f in blocked for b in f["blockers"])
    only = collections.Counter(f["blockers"][0] for f in blocked if len(f["blockers"]) == 1)
    for blocker, n in counts.most_common():
        print(f"| {blocker} | {n} | {only.get(blocker, 0)} |")
    if out:
        json.dump({"bytecode": sorted(bytecode), "tree": functions}, open(out, "w"), indent=1)


if __name__ == "__main__":
    main()
