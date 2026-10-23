"""Compares Truffle PHP against PHP on the extracted php-src tests (tests/phpt/run.sh).

Usage: python compare.py <phpt dir>      (reads <dir>/expected and <dir>/actual, writes <dir>/report.txt)

A case passes when stdout is byte-identical and both engines agree on whether the script died (PHP exit code
255 on a fatal error or uncaught exception). When both died, the strict score also wants the same reason: PHP's
fatal message (from its log, expected/<case>.err) against the one Truffle PHP died with, compared without the
exception class, file paths and line numbers. A case that passes only leniently is "fatal-reason"; the report
gives both scores. Failures are classified by their first cause: no AST export (the file doesn't parse), an
unsupported construct or built-in, a timeout, an internal (Java) error, a fatal error on one side only, a
different reason to die, or different output.
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


def reason(message):
    """A fatal message without "Uncaught", the exception class, paths, line numbers and the stack trace."""
    # PHP's log line ends at a NUL (an anonymous class's name has one)
    message = message.strip().split("\n")[0].split("\0")[0]
    message = re.sub(r" in \S*:\d+$| in \S+ on line \d+$", "", message)
    if message.startswith("Uncaught "):
        # "Uncaught Class: message", or "Uncaught Class" for an exception without a message
        message = re.sub(r"^[\w\\]+$|^[\w\\]+: ", "", message[len("Uncaught "):])
    message = re.sub(r"^User error: |^[A-Z][\w\\]*: ", "", message)
    message = re.sub(r"(/[\w.\-]+)+\.(php|inc)", "%s", message)
    message = re.sub(r"(on line |:)\d+", r"\1%d", message)
    return message.strip()


def php_reason(case):
    """PHP's last fatal error message, or None if its log has none."""
    path = os.path.join(base, "expected", case + ".err")
    if not os.path.exists(path):
        return None
    log = open(path, encoding="utf-8", errors="replace").read()
    found = re.findall(r"PHP Fatal error:  (.*)", log)
    return found[-1] if found else None


expected = results(os.path.join(base, "expected", "results.txt"))
actual = {k.replace("target/phpt/cases/", "", 1): v for k, v in results(os.path.join(base, "actual", "results.txt")).items()}

kinds = collections.Counter()
by_dir = collections.defaultdict(collections.Counter)
missing = collections.Counter()
unsupported = collections.Counter()
lines = []
reasons = []
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
            wanted = php_reason(case) if php_died else None
            if wanted is not None and reason(wanted) != reason(message):
                kind = "fatal-reason"
                reasons.append(f"{case}\n    php:  {reason(wanted)}\n    ours: {reason(message)}")
        elif php_died != we_died:
            kind = "fatal-mismatch"
        else:
            kind = "diff"
    kinds[kind] += 1
    by_dir[top][kind] += 1
    if kind != "pass":
        lines.append(f"{case}\t{kind}\t{actual.get(case, ['-'])[0][:160]}")

ran = sum(kinds.values())
lenient = kinds["pass"] + kinds["fatal-reason"]
report = [f"cases compared: {ran}, passing: {kinds['pass']} ({kinds['pass'] * 100 / max(ran, 1):.1f}%) strict, "
          f"{lenient} ({lenient * 100 / max(ran, 1):.1f}%) lenient (dying for any reason when PHP dies)", ""]
report += [f"{v:6}  {k}" for k, v in kinds.most_common()]
report += ["", "by directory (pass / total):"]
for d, c in sorted(by_dir.items()):
    total = sum(c.values())
    report.append(f"  {d:45} {c['pass']:5} / {total:5}  {c['pass'] * 100 / total:5.1f}%")
report += ["", "most-needed missing functions:"] + [f"  {v:4}  {k}" for k, v in missing.most_common(40)]
report += ["", "most frequent unsupported constructs:"] + [f"  {v:4}  {k}" for k, v in unsupported.most_common(25)]
open(os.path.join(base, "report.txt"), "w", encoding="utf-8", newline="\n").write("\n".join(report) + "\n")
open(os.path.join(base, "failures.txt"), "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
open(os.path.join(base, "fatal-reasons.txt"), "w", encoding="utf-8", newline="\n").write("\n".join(reasons) + "\n")
print("\n".join(report[:2 + len(kinds) + 1 + len(by_dir) + 2]))
