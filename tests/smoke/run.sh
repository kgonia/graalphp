#!/usr/bin/env bash
# Runs each smoke test on PHP 8.5 (the reference container) and on Truffle PHP, and diffs the outputs. Truffle PHP
# runs each test twice: in one context, and in three contexts on one shared engine (a server's mode: shared
# translations and compiled code, per-context state), whose last output must match too.
# Usage: tests/smoke/run.sh [name...]   (default: every tests/smoke/*.php). Build first: mvn package -DskipTests
set -uo pipefail
shopt -s nullglob
cd "$(dirname "$0")/../.."
names=("$@")
if [ ${#names[@]} -eq 0 ]; then
  for f in tests/smoke/*.php; do names+=("$(basename "$f" .php)"); done
fi
mkdir -p target/smoke
files=()
for n in "${names[@]}"; do files+=("tests/smoke/$n.php"); done
# with the files they include (tests/smoke/*.inc)
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast "${files[@]}" tests/smoke/*.inc
failed=0
for n in "${names[@]}"; do
  MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php "tests/smoke/$n.php" > "target/smoke/$n.expected" 2>&1
  ./bin-php.sh --ast-root=target/ast "tests/smoke/$n.php" > "target/smoke/$n.actual" 2>&1
  ./bin-php.sh --ast-root=target/ast --contexts=3 "tests/smoke/$n.php" > "target/smoke/$n.shared" 2>&1
  # where errors are must not change when a tool wraps the statements
  if [ "$n" = exception-location ]; then
    JAVA_OPTS="-Dpolyglot.cputracer=true -Dpolyglot.cputracer.TraceStatements=true       -Dpolyglot.cputracer.OutputFile=target/smoke/$n.cputracer"       ./bin-php.sh --ast-root=target/ast "tests/smoke/$n.php" > "target/smoke/$n.traced" 2>&1
  else
    cp "target/smoke/$n.actual" "target/smoke/$n.traced"
  fi
  if ! diff -q "target/smoke/$n.expected" "target/smoke/$n.actual" > /dev/null; then
    echo "FAIL $n (diff target/smoke/$n.expected target/smoke/$n.actual)"
    failed=1
  elif ! diff -q "target/smoke/$n.expected" "target/smoke/$n.shared" > /dev/null; then
    echo "FAIL $n in 3 contexts (diff target/smoke/$n.expected target/smoke/$n.shared)"
    failed=1
  elif ! diff -q "target/smoke/$n.expected" "target/smoke/$n.traced" > /dev/null; then
    echo "FAIL $n with statements instrumented (diff target/smoke/$n.expected target/smoke/$n.traced)"
    failed=1
  else
    echo "ok   $n"
  fi
done
exit $failed
