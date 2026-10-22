"""Runs the Gate 1 driver on reference PHP and, for each "Call to undefined function X", appends WordPress's own
definition of X (from a WordPress checkout) to bench/gate1/stubs-copied.php, until the driver runs clean.
Review the result: functions that drag in the database or other subsystems belong in stubs.php as hand stubs.
Usage: python -I tools/wp-autostub.py <wordpress-repo> [kernels] [iterations]
"""
import os, re, shutil, subprocess, sys, pathlib

repo = sys.argv[1]
kernels = sys.argv[2] if len(sys.argv) > 2 else "all"
iterations = sys.argv[3] if len(sys.argv) > 3 else "1"
root = pathlib.Path(__file__).resolve().parent.parent
# Git Bash, not the WSL bash.exe that Windows puts first on PATH.
bash = os.environ.get("GIT_BASH", r"C:\Program Files\Git\usr\bin\bash.exe")
target = root / "bench/gate1/stubs-copied.php"

paths = [f for f in subprocess.run(["git", "-C", repo, "ls-tree", "-r", "--name-only", "HEAD", "wp-includes"],
                                   capture_output=True, text=True).stdout.split() if f.endswith(".php")]
index = {}
for path in paths:
    text = subprocess.run(["git", "-C", repo, "show", f"HEAD:{path}"], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout
    for match in re.finditer(r"^function\s+&?\s*(\w+)\s*\(", text, re.M):
        depth, started, end = 0, False, len(text)
        for i in range(match.end(), len(text)):
            if text[i] == "{":
                depth, started = depth + 1, True
            elif text[i] == "}":
                depth -= 1
                if started and depth == 0:
                    end = i + 1
                    break
        index.setdefault(match.group(1).lower(), (path, text[match.start():end]))

if not target.exists():
    target.write_text("<?php\n// WordPress's own definitions at the pinned commit, copied by tools/wp-autostub.py.\n",
                      encoding="utf-8", newline="\n")
added = []
while True:
    run = subprocess.run([bash, "bench/gate1/php.sh", "php", "bench/gate1/run.php", kernels, iterations],
                         cwd=root, capture_output=True, text=True, encoding="utf-8", errors="replace")
    output = run.stdout + run.stderr
    missing = re.search(r"Call to undefined function (\w+)\(\)", output)
    if not missing:
        break
    name = missing.group(1).lower()
    if name not in index or name in added:
        print(f"cannot resolve {name}:\n{output[-1500:]}")
        sys.exit(1)
    path, source = index[name]
    with target.open("a", encoding="utf-8", newline="\n") as out:
        out.write(f"\n// copied: {path}\n{source}\n")
    added.append(name)
    print(f"added {name} from {path}", flush=True)

print(f"\n{len(added)} functions added")
print("\n".join(line for line in output.splitlines() if re.match(r"^[a-z]+ \d+ ", line) or "rror" in line)[-3000:])
