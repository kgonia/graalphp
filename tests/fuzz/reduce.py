"""Shrinks a program that tests/fuzz/run.sh found differing to a small one that still differs.

Removes one line at a time (then pairs of adjacent lines), keeping a removal when PHP 8.5 and Truffle PHP still
disagree under the same compare mode and PHP neither fails to compile the result nor ends it with a fatal error. PHP runs in one reference container
kept for the whole reduction (it also writes the AST export Truffle PHP reads); Truffle PHP runs through
bin-php.sh with CLASSES/LIB/JAVA_OPTS as for run.sh. Both log errors rather than display them, as in run.sh, and the
logs' messages are compared too.

Usage: python -I tests/fuzz/reduce.py PROGRAM.php OUT.php [--mode=before-end|full|no-destructors]
"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare import LOG, cut  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WORK = "target/fuzz/reduce"
CONTAINER = "fuzz-reduce-php"
# Git Bash, not the WSL bash.exe that a bare "bash" can resolve to on Windows
BASH = os.environ.get("FUZZ_BASH", "C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else "bash")
INI = ["-d", "display_errors=0", "-d", "log_errors=1"]
# as run.sh: OPcache's optimizer drops stores to variables never read (php-src frees the value early then)
PHP_INI = ["-d", "opcache.enable_cli=0"] + INI
# run.sh's CLASSES/LIB are bin-php.sh's PHP_CLASSES/PHP_LIB
TRUFFLE_ENV = {new: os.environ[old] for old, new in (("CLASSES", "PHP_CLASSES"), ("LIB", "PHP_LIB"))
               if old in os.environ}
LOG_MARK = "--log--"


def sh(args, env=None, **kw):
    env = dict(os.environ, MSYS_NO_PATHCONV="1", **(env or {}))
    return subprocess.run(args, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace",
                          env=env, **kw)


def start():
    sh(["docker", "rm", "-f", CONTAINER])
    sh(["docker", "run", "-d", "--name", CONTAINER, "-v", f"{ROOT.replace(os.sep, '/')}:/w", "-w", "/w", "gate1-php:8.5", "sleep", "infinity"])


def log_messages(text):
    return [f"{m.group(1)}: {m.group(2)}" for m in map(LOG.match, text.splitlines()) if m]


def outputs(lines):
    """Each side's output lines, LOG_MARK, then its error log's messages; None if PHP doesn't compile the program."""
    path = f"{WORK}/candidate.php"
    with open(os.path.join(ROOT, path), "w", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    php = sh(["docker", "exec", CONTAINER, "sh", "-c",
              f"php -l {path} > /dev/null 2>&1 || exit 7; php {' '.join(PHP_INI)} {path}; "
              f"php tools/ast-export.php target/ast {path} > /dev/null 2>&1"])
    # a removal that breaks the program (a call to a function whose definition went) isn't the difference sought
    if php.returncode == 7 or "Fatal error" in php.stderr:
        return None
    truffle = sh([BASH, "bin-php.sh", "--ast-root=target/ast", *INI, path], env=TRUFFLE_ENV, timeout=120)
    return (php.stdout.splitlines() + [LOG_MARK] + log_messages(php.stderr),
            truffle.stdout.splitlines() + [LOG_MARK] + log_messages(truffle.stderr))


def cut_both(out, mode):
    i = out.index(LOG_MARK)
    return cut(out[:i], mode) + out[i:]


def differs(lines, mode):
    out = outputs(lines)
    return out is not None and cut_both(out[0], mode) != cut_both(out[1], mode)


def main():
    src, out = sys.argv[1], sys.argv[2]
    mode = next((a.split("=", 1)[1] for a in sys.argv[3:] if a.startswith("--mode=")), "before-end")
    os.makedirs(os.path.join(ROOT, WORK), exist_ok=True)
    lines = open(src, encoding="utf-8").read().splitlines()
    start()
    try:
        if not differs(lines, mode):
            sys.exit("the program doesn't differ (or doesn't compile): nothing to reduce")
        # gen.py's prelude (class D and the helpers, up to thrower) stays: without it the difference found would be
        # the test hook's against PHP's destructor, not a count's
        start_at = next((i + 1 for i, l in enumerate(lines) if l.startswith("function thrower")), 1)
        for width in (2, 1):
            i = start_at
            # the last line, `echo "@end\n";`, stays too: before-end compares up to it, shutdown is apart
            while i < len(lines) - 1:
                candidate = lines[:i] + lines[i + width:]
                if candidate[-1] != lines[-1]:
                    i += 1
                    continue
                if differs(candidate, mode):
                    lines = candidate
                    print(f"{len(lines)} lines", flush=True)
                else:
                    i += 1
        with open(out, "w", newline="\n") as fh:
            fh.write("\n".join(lines) + "\n")
        php, truffle = outputs(lines)
        print("".join(f"php     | {l}\n" for l in cut_both(php, mode))
              + "".join(f"truffle | {l}\n" for l in cut_both(truffle, mode)))
    finally:
        sh(["docker", "rm", "-f", CONTAINER])


if __name__ == "__main__":
    main()
