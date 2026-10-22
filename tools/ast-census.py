"""Counts zend_ast kinds (and flags per kind) across exported AST JSON files: what the front end must translate.
Usage: python -I tools/ast-census.py <ast-dir>
"""
import collections, json, pathlib, sys

kinds, flags = collections.Counter(), collections.defaultdict(collections.Counter)

def walk(node):
    if isinstance(node, dict):
        if "k" in node:
            kinds[node["k"]] += 1
            flags[node["k"]][node.get("f", 0)] += 1
            children = node.get("c", [])
            for child in (children.values() if isinstance(children, dict) else children):
                walk(child)

for path in pathlib.Path(sys.argv[1]).rglob("*.json"):
    walk(json.loads(path.read_text(encoding="utf-8")))

for kind, count in kinds.most_common():
    detail = ", ".join(f"{f}:{n}" for f, n in flags[kind].most_common(8)) if len(flags[kind]) > 1 else ""
    print(f"{count:7}  {kind:28} {detail}")
