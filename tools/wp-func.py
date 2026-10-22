"""Prints the source of WordPress functions from a WordPress git checkout (blobs only, no working tree needed).
Usage: python -I tools/wp-func.py <wordpress-repo> <function>...
"""
import re, subprocess, sys

repo, names = sys.argv[1], sys.argv[2:]
files = subprocess.run(["git", "-C", repo, "ls-tree", "-r", "--name-only", "HEAD", "wp-includes"],
                       capture_output=True, text=True).stdout.split()
for name in names:
    pattern = re.compile(r"^function\s+&?\s*" + re.escape(name) + r"\s*\(", re.M)
    for path in files:
        if not path.endswith(".php"):
            continue
        text = subprocess.run(["git", "-C", repo, "show", f"HEAD:{path}"], capture_output=True,
                              text=True, encoding="utf-8", errors="replace").stdout
        match = pattern.search(text)
        if not match:
            continue
        depth, end, started = 0, match.start(), False
        for i in range(match.start(), len(text)):
            if text[i] == "{":
                depth, started = depth + 1, True
            elif text[i] == "}":
                depth -= 1
                if started and depth == 0:
                    end = i + 1
                    break
        print(f"// {path}\n{text[match.start():end]}\n")
        break
    else:
        print(f"// NOT FOUND: {name}\n")
