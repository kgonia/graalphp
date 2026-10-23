"""Extracts php-src .phpt tests into runnable cases for the differential conformance run (tests/phpt/run.sh).

Usage: python extract.py <php-src dir> <cases dir>

For each selected test it writes <cases>/<path>.php (the --FILE-- section) and, when present,
<path>.skipif.php. Support files next to the tests (.inc, data files) are copied so includes resolve. Tests
needing what a plain `php file.php` run can't give are listed as excluded in <cases>/excluded.txt, with the
section that excluded them. <cases>/cases.txt lists the runnable cases.
"""
import os
import re
import shutil
import sys

DIRS = [
    "Zend/tests",
    "tests/lang",
    "ext/standard/tests/array",
    "ext/standard/tests/strings",
    "ext/standard/tests/math",
    "ext/standard/tests/general_functions",
    "ext/standard/tests/serialize",
    "ext/standard/tests/class_object",
    "ext/pcre/tests",
    "ext/json/tests",
    "ext/ctype/tests",
]

# Sections that need a SAPI, input or configuration a plain CLI run of the file doesn't provide.
EXCLUDING = {"ARGS", "STDIN", "ENV", "INI", "POST", "POST_RAW", "PUT", "GET", "COOKIE", "GZIP_POST", "DEFLATE_POST",
             "CGI", "PHPDBG", "FILE_EXTERNAL", "REDIRECTTEST", "CAPTURE_STDIO", "EXTENSIONS", "FILEEOF", "WHITESPACE_SENSITIVE"}

SECTION = re.compile(rb"^--([A-Z_]+)--\s*$", re.M)


def sections(data):
    found = {}
    marks = list(SECTION.finditer(data))
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(data)
        found[m.group(1).decode()] = data[m.end():end].lstrip(b"\r\n") if m.group(1) != b"FILE" else data[m.end() + 1:end]
    return found


def main(src, out):
    cases, excluded = [], []
    for d in DIRS:
        root = os.path.join(src, d)
        for dirpath, _, files in os.walk(root):
            rel_dir = os.path.relpath(dirpath, src).replace(os.sep, "/")
            for name in files:
                path = os.path.join(dirpath, name)
                rel = f"{rel_dir}/{name}"
                target = os.path.join(out, rel)
                if not name.endswith(".phpt"):
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    shutil.copyfile(path, target)
                    continue
                with open(path, "rb") as f:
                    s = sections(f.read())
                blocking = sorted(set(s) & EXCLUDING)
                if "FILE" not in s:
                    blocking.append("no FILE")
                if blocking:
                    excluded.append(f"{rel}\t{','.join(blocking)}")
                    continue
                base = target[:-len(".phpt")]
                os.makedirs(os.path.dirname(base), exist_ok=True)
                with open(base + ".php", "wb") as f:
                    f.write(s["FILE"])
                if "SKIPIF" in s:
                    with open(base + ".skipif.php", "wb") as f:
                        f.write(s["SKIPIF"])
                cases.append(rel[:-len(".phpt")] + ".php")
    with open(os.path.join(out, "cases.txt"), "w", newline="\n") as f:
        f.write("\n".join(cases) + "\n")
    with open(os.path.join(out, "excluded.txt"), "w", newline="\n") as f:
        f.write("\n".join(excluded) + "\n")
    print(f"{len(cases)} cases, {len(excluded)} excluded")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
