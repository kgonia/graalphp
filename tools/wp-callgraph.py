"""Static call closure of WordPress functions: which user functions the given entry points can reach, and in which
file each is defined. Calls are found by regex (name followed by "("), so dynamic calls and methods are missed;
it is a guide for writing stubs, not a proof.
Usage: python -I tools/wp-callgraph.py <wordpress-repo> <fetched-dir> <entry>...
"""
import pathlib, re, subprocess, sys

repo, fetched, entries = sys.argv[1], pathlib.Path(sys.argv[2]), sys.argv[3:]
files = [f for f in subprocess.run(["git", "-C", repo, "ls-tree", "-r", "--name-only", "HEAD"],
                                   capture_output=True, text=True).stdout.split() if f.endswith(".php")]
defs = {}
for path in files:
    if "/vendor/" in path or path.startswith("wp-content/"):
        continue
    text = subprocess.run(["git", "-C", repo, "show", f"HEAD:{path}"], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout
    for match in re.finditer(r"^\s*function\s+&?\s*(\w+)\s*\(", text, re.M):
        depth, started, end = 0, False, len(text)
        for i in range(match.end(), len(text)):
            if text[i] == "{":
                depth, started = depth + 1, True
            elif text[i] == "}":
                depth -= 1
                if started and depth == 0:
                    end = i + 1
                    break
        defs.setdefault(match.group(1).lower(), (path, text[match.start():end]))

fetched_names = {p.name for p in fetched.glob("*.php")}
call = re.compile(r"(?<![\w>:$])(\w+)\s*\(")
seen, queue = set(), [e.lower() for e in entries]
while queue:
    name = queue.pop()
    if name in seen or name not in defs:
        continue
    seen.add(name)
    body = re.sub(r"(?s)/\*.*?\*/|//[^\n]*", "", defs[name][1])
    queue.extend(m.group(1).lower() for m in call.finditer(body))

outside = sorted((defs[n][0], n) for n in seen if pathlib.Path(defs[n][0]).name not in fetched_names)
print(f"{len(seen)} reachable user functions; {len(outside)} defined outside the fetched files:")
for path, name in outside:
    print(f"  {path:45} {name}")
