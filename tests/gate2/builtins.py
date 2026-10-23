"""Gate 2 probe, step 2: which PHP built-in functions and classes WordPress core uses that Truffle PHP lacks.

Names come from the AST exports (statically named calls, `new`, static calls and constants, instanceof, catch,
extends, implements), so comments and strings don't count. A name is a built-in when the reference PHP 8.5
(the oracle container) declares it.

Phases (tests/gate2/builtins.sh runs them):
  builtins.py names     -> target/gate2/used.json: used names with call-site counts and first location
  builtins.py drivers   -> the PHP drivers: reference.php (lists PHP's built-ins), ours.php (asks Truffle PHP)
  builtins.py           -> target/gate2/builtins.md from the drivers' outputs
"""
import collections
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
AST = ROOT / "target/ast/target/gate2/wordpress"
OUT = ROOT / "target/gate2"


def name_of(node):
    if isinstance(node, dict) and node.get("k") == "AST_NAME":
        name = node["c"]["name"]
        if isinstance(name, str):
            return name.lstrip("\\").split("\\")[-1].lower()
    return None


def collect():
    functions, classes = collections.Counter(), collections.Counter()
    where = {}

    def walk(node, path):
        if isinstance(node, dict):
            kind = node.get("k")
            children = node.get("c", {})
            if kind == "AST_CALL":
                name = name_of(children.get("expr"))
                if name:
                    functions[name] += 1
                    where.setdefault("f:" + name, f"{path}:{node.get('l')}")
            elif kind in ("AST_NEW", "AST_STATIC_CALL", "AST_CLASS_CONST", "AST_STATIC_PROP", "AST_INSTANCEOF"):
                name = name_of(children.get("class"))
                if name and name not in ("self", "parent", "static"):
                    classes[name] += 1
                    where.setdefault("c:" + name, f"{path}:{node.get('l')}")
            elif kind == "AST_CATCH":
                for type_node in (children.get("class") or {}).get("c", []):
                    name = name_of(type_node)
                    if name:
                        classes[name] += 1
                        where.setdefault("c:" + name, f"{path}:{node.get('l')}")
            elif kind == "AST_CLASS":
                for key in ("extends",):
                    name = name_of(children.get(key))
                    if name:
                        classes[name] += 1
                        where.setdefault("c:" + name, f"{path}:{node.get('l')}")
                for type_node in (children.get("implements") or {}).get("c", []):
                    name = name_of(type_node)
                    if name:
                        classes[name] += 1
                        where.setdefault("c:" + name, f"{path}:{node.get('l')}")
            for child in (children.values() if isinstance(children, dict) else children):
                walk(child, path)
        elif isinstance(node, list):
            for child in node:
                walk(child, path)

    for json_path in sorted(AST.rglob("*.php.json")):
        walk(json.loads(json_path.read_text(encoding="utf-8")), json_path.relative_to(AST).as_posix()[:-5])
    return functions, classes, where


phase = sys.argv[1] if len(sys.argv) > 1 else "report"
if phase == "names":
    functions, classes, where = collect()
    (OUT / "used.json").write_text(json.dumps({"functions": functions, "classes": classes, "where": where}),
                                   encoding="utf-8")
    sys.exit(0)

used = json.loads((OUT / "used.json").read_text(encoding="utf-8"))
if phase == "drivers":
    (OUT / "reference.php").write_text(
        "<?php\n"
        "foreach (get_defined_functions()['internal'] as $f) { echo 'f ', strtolower($f), \"\\n\"; }\n"
        "foreach (array_merge(get_declared_classes(), get_declared_interfaces()) as $c) {\n"
        "    $r = new ReflectionClass($c);\n"
        "    if ($r->isInternal()) { echo 'c ', strtolower($c), ' ', $r->getExtensionName(), \"\\n\"; }\n"
        "}\n"
        "foreach (get_defined_functions()['internal'] as $f) {\n"
        "    echo 'e ', strtolower($f), ' ', (new ReflectionFunction($f))->getExtensionName(), \"\\n\";\n"
        "}\n", encoding="utf-8")
    lines = ["<?php"]
    for name in used["functions"]:
        lines.append(f"if (function_exists({json.dumps(name)})) echo 'f ', {json.dumps(name)}, \"\\n\";")
    for name in used["classes"]:
        lines.append(f"if (class_exists({json.dumps(name)}) || interface_exists({json.dumps(name)})) "
                     f"echo 'c ', {json.dumps(name)}, \"\\n\";")
    (OUT / "ours.php").write_text("\n".join(lines) + "\n", encoding="utf-8")
    sys.exit(0)

reference_functions, reference_classes, extension = set(), {}, {}
for line in (OUT / "reference.out").read_text(encoding="utf-8").splitlines():
    parts = line.split()
    if parts[0] == "f":
        reference_functions.add(parts[1])
    elif parts[0] == "c":
        reference_classes[parts[1]] = parts[2] if len(parts) > 2 else ""
    elif parts[0] == "e":
        extension[parts[1]] = parts[2] if len(parts) > 2 else ""
ours_functions, ours_classes = set(), set()
for line in (OUT / "ours.out").read_text(encoding="utf-8", errors="replace").splitlines():
    parts = line.split()
    if len(parts) == 2 and parts[0] == "f":
        ours_functions.add(parts[1])
    elif len(parts) == 2 and parts[0] == "c":
        ours_classes.add(parts[1])

functions, classes, where = used["functions"], used["classes"], used["where"]
builtin_functions = {n: c for n, c in functions.items() if n in reference_functions}
builtin_classes = {n: c for n, c in classes.items() if n in reference_classes}
missing_functions = {n: c for n, c in builtin_functions.items() if n not in ours_functions}
missing_classes = {n: c for n, c in builtin_classes.items() if n not in ours_classes}

report = ["# Gate 2 probe: PHP built-ins WordPress core uses", ""]
report.append(f"- Built-in functions called: **{len(builtin_functions)}** distinct "
              f"({sum(builtin_functions.values())} call sites); Truffle PHP has "
              f"{len(builtin_functions) - len(missing_functions)}, lacks **{len(missing_functions)}** "
              f"({sum(missing_functions.values())} call sites).")
report.append(f"- Built-in classes and interfaces used: **{len(builtin_classes)}**; Truffle PHP has "
              f"{len(builtin_classes) - len(missing_classes)}, lacks **{len(missing_classes)}**.")
report.append("- Calls guarded by function_exists() and polyfilled in PHP (sodium_*, mb_* fallbacks) count as "
              "used here.")
report += ["", "## Missing functions by extension", "", "| Extension | Missing | Call sites | Most used |",
           "|---|---:|---:|---|"]
by_extension = collections.defaultdict(list)
for name, count in missing_functions.items():
    by_extension[extension.get(name, "?")].append((count, name))
for ext, items in sorted(by_extension.items(), key=lambda kv: -sum(c for c, _ in kv[1])):
    items.sort(reverse=True)
    top = ", ".join(f"{n} ({c})" for c, n in items[:8])
    report.append(f"| {ext} | {len(items)} | {sum(c for c, _ in items)} | {top} |")
report += ["", "## Missing classes and interfaces", "", "| Class | Extension | Uses | First use |",
           "|---|---|---:|---|"]
for name, count in sorted(missing_classes.items(), key=lambda kv: -kv[1]):
    report.append(f"| {name} | {reference_classes[name]} | {count} | {where['c:' + name]} |")
report += ["", "## All missing functions, by call sites", ""]
report.append(", ".join(f"{n} ({c})" for n, c in sorted(missing_functions.items(), key=lambda kv: -kv[1])))
(OUT / "builtins.md").write_text("\n".join(report) + "\n", encoding="utf-8")
print("\n".join(report))
