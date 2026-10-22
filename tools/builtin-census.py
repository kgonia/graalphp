"""Which PHP built-in functions the given PHP files call (by name, statically), with call-site counts.
Built-in names come from php-src's *.stub.php files.
Usage: python -I tools/builtin-census.py <php-src-repo> <file-or-dir>...
"""
import collections, pathlib, re, subprocess, sys

php_src, targets = sys.argv[1], sys.argv[2:]
stubs = [f for f in subprocess.run(["git", "-C", php_src, "ls-tree", "-r", "--name-only", "HEAD"],
                                   capture_output=True, text=True).stdout.split()
         if f.endswith(".stub.php") and "/tests/" not in f and "zend_test" not in f]
builtin = {}
for path in stubs:
    text = subprocess.run(["git", "-C", php_src, "show", f"HEAD:{path}"], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout
    ext = path.split("/")[1] if path.startswith("ext/") else "Zend"
    for match in re.finditer(r"^function\s+&?\s*(\w+)\s*\(", text, re.M):
        builtin.setdefault(match.group(1).lower(), ext)

files = []
for target in targets:
    p = pathlib.Path(target)
    files.extend(p.rglob("*.php") if p.is_dir() else [p])
used = collections.Counter()
call = re.compile(r"(?<![\w>:$\\])\\?(\w+)\s*\(")
for path in files:
    text = re.sub(r"(?s)/\*.*?\*/|//[^\n]*|#[^\n]*", "", path.read_text(encoding="utf-8", errors="replace"))
    for match in call.finditer(text):
        name = match.group(1).lower()
        if name in builtin:
            used[name] += 1
by_ext = collections.defaultdict(list)
for name, count in sorted(used.items()):
    by_ext[builtin[name]].append(f"{name}({count})")
print(f"{len(used)} distinct built-ins, {sum(used.values())} call sites")
for ext, names in sorted(by_ext.items(), key=lambda e: -len(e[1])):
    print(f"\n[{ext}] {len(names)}: " + " ".join(names))
