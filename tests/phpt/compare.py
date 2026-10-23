"""Compares Truffle PHP against PHP on the extracted php-src tests (tests/phpt/run.sh).

Usage: python compare.py <phpt dir>      (reads <dir>/expected and <dir>/actual, writes <dir>/report.txt)

A case passes when stdout is byte-identical and both engines agree on whether the script died (PHP exit code
255 on a fatal error or uncaught exception). Failures are classified by their first cause: no AST export (the
file doesn't parse), an unsupported construct or built-in, a timeout, an internal (Java) error, a fatal error
on one side only, or different output.
"""
import collections
import os
import re
import sys

base = sys.argv[1]


def results(path):
    rows = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 2:
            rows[parts[0]] = parts[1:]
    return rows


expected = results(os.path.join(base, "expected", "results.txt"))
actual = {k.replace("target/phpt/cases/", "", 1): v for k, v in results(os.path.join(base, "actual", "results.txt")).items()}

kinds = collections.Counter()
by_dir = collections.defaultdict(collections.Counter)
missing = collections.Counter()
unsupported = collections.Counter()
lines = []
for case, (outcome, code) in sorted(expected.items()):
    top = "/".join(case.split("/")[:3]) if case.startswith("ext/") else "/".join(case.split("/")[:2])
    if outcome == "skip":
        continue
    if outcome == "timeout":
        kind = "php-timeout"
    elif case not in actual:
        kind = "not-run"
    else:
        status = actual[case][0]
        want = open(os.path.join(base, "expected", case + ".out"), "rb").read()
        got_path = os.path.join(base, "actual", "target/phpt/cases", case + ".out")
        got = open(got_path, "rb").read() if os.path.exists(got_path) else b""
        php_died = code == "255"
        we_died = status.startswith("fatal") or status == "exit:255"
        message = status.split(" ", 1)[1] if " " in status else ""
        if "no AST export" in message:
            kind = "no-ast"
        elif status in ("timeout", "hung"):
            kind = "timeout"
        elif status.startswith("internal"):
            kind = "internal"
        elif m := re.search(r"Call to undefined function (\w+)", message):
            kind = "missing-function"
            if not php_died:
                missing[m.group(1).lower()] += 1
            else:
                kind = "pass" if got == want else "diff"
        elif re.search(r"[Uu]nsupported|not supported", message):
            kind = "unsupported"
            unsupported[re.sub(r"\s+at .*|\d+", "", message)[:90]] += 1
        elif got == want and php_died == we_died:
            kind = "pass"
        elif php_died != we_died:
            kind = "fatal-mismatch"
        else:
            kind = "diff"
    kinds[kind] += 1
    by_dir[top][kind] += 1
    if kind != "pass":
        lines.append(f"{case}\t{kind}\t{actual.get(case, ['-'])[0][:160]}")

ran = sum(kinds.values())
report = [f"cases compared: {ran}, passing: {kinds['pass']} ({kinds['pass'] * 100 / max(ran, 1):.1f}%)", ""]
report += [f"{v:6}  {k}" for k, v in kinds.most_common()]
report += ["", "by directory (pass / total):"]
for d, c in sorted(by_dir.items()):
    total = sum(c.values())
    report.append(f"  {d:45} {c['pass']:5} / {total:5}  {c['pass'] * 100 / total:5.1f}%")
report += ["", "most-needed missing functions:"] + [f"  {v:4}  {k}" for k, v in missing.most_common(40)]
report += ["", "most frequent unsupported constructs:"] + [f"  {v:4}  {k}" for k, v in unsupported.most_common(25)]
open(os.path.join(base, "report.txt"), "w", encoding="utf-8", newline="\n").write("\n".join(report) + "\n")
open(os.path.join(base, "failures.txt"), "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
print("\n".join(report[:2 + len(kinds) + 1 + len(by_dir) + 2]))
