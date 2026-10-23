"""Gate 2: the built-in functions Truffle PHP lacks that the files wp-settings.php loads call (an upper bound of
what the boot needs). Run after tests/gate2/builtins.sh. Usage: python -I tests/gate2/boot_needs.py
"""
import collections
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
WP = ROOT / "target/gate2/wordpress"
AST = ROOT / "target/ast/target/gate2/wordpress"
OUT = ROOT / "target/gate2"

settings = (WP / "wp-settings.php").read_text(encoding="utf-8")
files = {"wp-includes/" + f for f in re.findall(r"ABSPATH \. WPINC \. '/([^']+\.php)'", settings)}
files |= {"wp-settings.php", "wp-load.php", "wp-config.php", "wp-includes/load.php",
          "wp-includes/default-constants.php", "wp-includes/version.php", "wp-includes/plugin.php"}
ours = {l.split()[1] for l in (OUT / "ours.out").read_text(encoding="utf-8", errors="replace").splitlines()
        if l.startswith("f ")}
reference = {l.split()[1] for l in (OUT / "reference.out").read_text(encoding="utf-8").splitlines()
             if l.startswith("f ")}
calls = collections.Counter()


def walk(node):
    if isinstance(node, dict):
        if node.get("k") == "AST_CALL":
            callee = node["c"].get("expr")
            if isinstance(callee, dict) and callee.get("k") == "AST_NAME" and isinstance(callee["c"]["name"], str):
                name = callee["c"]["name"].lstrip("\\").split("\\")[-1].lower()
                if name in reference and name not in ours:
                    calls[name] += 1
        children = node.get("c", {})
        for child in (children.values() if isinstance(children, dict) else children):
            walk(child)
    elif isinstance(node, list):
        for child in node:
            walk(child)


present = [f for f in sorted(files) if (AST / (f + ".json")).exists()]
for f in present:
    walk(json.loads((AST / (f + ".json")).read_text(encoding="utf-8")))
print(f"{len(present)} files; {len(calls)} missing built-ins called")
print(", ".join(f"{name}({count})" for name, count in calls.most_common()))
