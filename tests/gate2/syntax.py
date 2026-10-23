"""Gate 2 probe, step 1: what of WordPress core's syntax the translator can't handle yet.

1. Counts zend_ast kinds over every exported file (function bodies included) and lists the kinds that
   Translator.java never mentions, with counts and a first location.
2. Groups the errors of translating every file and every function, method and closure body on Truffle PHP
   (truffle_translate: nothing runs) by construct.

Usage: tests/gate2/syntax.sh (after tests/gate2/fetch.sh and tests/gate2/export.sh; build first).
`syntax.py driver` writes the PHP driver; `syntax.py` reads its output and writes target/gate2/syntax.md.
"""
import collections
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
AST = ROOT / "target/ast/target/gate2/wordpress"
OUT = ROOT / "target/gate2"

files = sorted(AST.rglob("*.php.json"))
paths = [f"target/gate2/wordpress/{p.relative_to(AST).as_posix()[:-5]}" for p in files]

if sys.argv[1:] == ["driver"]:
    driver = ["<?php", "$files = ["]
    driver += [json.dumps(p) + "," for p in paths]
    driver += ["];",
               "foreach ($files as $f) {",
               "    foreach (truffle_translate($f) as $e) {",
               '        echo $f, "\\t", $e, "\\n";',
               "    }",
               "}",
               'echo "done\\n";']
    (OUT / "translate-all.php").write_text("\n".join(driver) + "\n", encoding="utf-8")
    sys.exit(0)

translator = (ROOT / "src/main/java/pl/codeloom/php/parser/Translator.java").read_text(encoding="utf-8")
handled = set(re.findall(r'"(AST_[A-Z_]+)"', translator))
kinds = collections.Counter()
first = {}


def walk(node, path):
    if isinstance(node, dict):
        if "k" in node:
            kind = node["k"]
            kinds[kind] += 1
            first.setdefault(kind, f"{path}:{node.get('l', '?')}")
            children = node.get("c", [])
            for child in (children.values() if isinstance(children, dict) else children):
                walk(child, path)


for json_path in files:
    walk(json.loads(json_path.read_text(encoding="utf-8")), json_path.relative_to(AST).as_posix()[:-5])

output = (OUT / "translate.out").read_text(encoding="utf-8", errors="replace")
lines = [l for l in output.splitlines() if "\t" in l]
completed = "done" in output.splitlines()
errors = collections.defaultdict(list)
for line in lines:
    path, message = line.split("\t", 1)
    key = re.sub(r" at line \d+\)", ")", message)
    errors[key].append(path.removeprefix("target/gate2/wordpress/"))

failed_files = {l.split("\t", 1)[0] for l in lines}
report = [f"# Gate 2 probe: syntax of WordPress core ({len(files)} files)", ""]
report.append("- Translation of every file and every function, method and closure body: "
              + ("completed" if completed else "DID NOT COMPLETE: " + output.strip()[-800:]))
report.append(f"- Files with at least one error: {len(failed_files)} of {len(files)}; errors: {len(lines)} "
              "(one per file or body that failed: the first construct the translator stopped at)")
report += ["", "## Translator errors, by construct", "", "| Errors | Construct | Example file |", "|---:|---|---|"]
for key, items in sorted(errors.items(), key=lambda kv: -len(kv[1])):
    report.append(f"| {len(items)} | {key} | {items[0]} |")
report += ["", "## zend_ast kinds that Translator.java never names", "", "| Count | Kind | First seen |",
           "|---:|---|---|"]
for kind, count in kinds.most_common():
    if kind not in handled:
        report.append(f"| {count} | {kind} | {first[kind]} |")
report += ["", f"All kinds: {len(kinds)}; named by the translator: {len([k for k in kinds if k in handled])}."]
(OUT / "syntax.md").write_text("\n".join(report) + "\n", encoding="utf-8")
print("\n".join(report))
